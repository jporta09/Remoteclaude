package com.remoteclaude.app

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * Visor del navegador headed (noVNC). Controles en cajón colapsable abajo:
 *  - Ajustada (resize=remote): el escritorio LLENA el visor.
 *  - Escritorio (resize=scale, display 1920x1080): se ve apaisado completo.
 *  - Zoom (sólo en Escritorio): mi capa intercepta el pinch (noVNC bloquea el del
 *    WebView) y maneja la ESCALA INTERNA de noVNC (display.scale + viewportChangeSize +
 *    viewportChangePos). Así el zoom es NÍTIDO (re-rasteriza del framebuffer) y noVNC
 *    mapea bien el mouse -> al apagar Zoom la vista queda y clickeás magnificado.
 * Orientación libre.
 */
class DisplayActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var zoomLayer: View
    private lateinit var errorOverlay: TextView   // mensaje traducido cuando la WebView no carga
    private lateinit var btnFit: TextView     // Pantalla (ajustada)
    private lateinit var btnDesk: TextView    // Escritorio
    private lateinit var btnZoom: TextView
    private lateinit var panel: LinearLayout
    private lateinit var handle: TextView
    private var fitMode = true
    private var zoomOn = false
    private var drawerOpen = false

    // Host al que pertenece el display (viene por Intent, igual que en DocsActivity).
    // Antes estaba hardcodeado a remoteclaude/22/root: con más de un host el visor
    // apuntaba SIEMPRE a la misma máquina y el modo Escritorio fallaba en silencio.
    private lateinit var sshHost: String
    private var sshPort = 22
    private lateinit var sshUser: String

    private var novncHost = "127.0.0.1"   // túnel SSH local; si falla, el endpoint directo
    private var novncPort = 6080
    /** Password del VNC (lo publica el contenedor, se regenera en cada arranque). */
    private var vncPass: String? = null
    private var tunnel: PortTunnel? = null
    private var curScale = 0.2f   // noVNC display.scale (CSS px por px del framebuffer)
    private var fitScale = 0.2f
    private var lastX = 0f
    private var lastY = 0f
    private lateinit var scaleDetector: ScaleGestureDetector

    private val density get() = resources.displayMetrics.density
    private val monoFont by lazy { resources.getFont(R.font.mononoki) }
    private val control by lazy { RemoteControl(this, sshHost, sshPort, sshUser, KeyStoreSsh.getOrCreateKeyPair()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sshHost = intent.getStringExtra("hostname") ?: "remoteclaude"
        sshPort = intent.getIntExtra("port", 22)
        sshUser = intent.getStringExtra("user") ?: "root"
        window.statusBarColor = PETROL

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE   // ui.js fresco (con el parche)
            setBackgroundColor(PETROL)
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?,
                ) {
                    // sólo el documento principal; los sub-recursos no deben disparar fallback
                    if (request?.isForMainFrame != true) return
                    val desc = error?.description?.toString().orEmpty()
                    when {
                        // Android bloquea HTTP en claro salvo loopback/MagicDNS. Cuando el visor
                        // cae al endpoint DIRECTO por IP cruda (Tailscale apagado + túnel SSH
                        // imposible, host viejo), Chrome tira ERR_CLEARTEXT_NOT_PERMITTED. Antes
                        // eso mostraba la página de error cruda de Chrome, incomprensible.
                        desc.contains("CLEARTEXT") -> mostrarErrorVisor(
                            "No pude abrir el visor por HTTP directo a la IP del host: Android " +
                                "bloquea el tráfico en claro fuera de la tailnet.\n\n" +
                                "Activá Tailscale (línea de estado de Tailscale en la pantalla de hosts) para verlo " +
                                "por la tailnet, o actualizá el host para publicar noVNC en loopback " +
                                "(así viaja tunelizado por SSH). Después volvé a abrir el visor."
                        )
                        // Primer fallo POR EL TÚNEL (127.0.0.1): se reintenta una vez por el
                        // endpoint directo. Si ya estábamos directos, no hay a dónde caer.
                        !triedDirect && novncHost == "127.0.0.1" -> fallbackToDirect()
                        else -> mostrarErrorVisor(
                            "No pude cargar el visor ($desc). Verificá que el navegador headed " +
                                "esté corriendo en el host (run-visible.sh) y tocá ↻ para reintentar."
                        )
                    }
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    // Cada intento arranca sin el mensaje: si falla, onReceivedError lo vuelve a
                    // mostrar. (No se puede limpiar en onPageFinished: eso también corre DESPUÉS
                    // de un load fallido y borraría el error apenas aparecido.)
                    errorOverlay.visibility = View.GONE
                }
            }
        }

        scaleDetector = ScaleGestureDetector(this, ScaleListener())
        zoomLayer = View(this).apply {
            visibility = View.GONE
            setOnTouchListener { _, ev -> onZoomTouch(ev) }
        }

        errorOverlay = TextView(this).apply {
            visibility = View.GONE
            setBackgroundColor(PETROL)
            setTextColor(FG)
            typeface = monoFont
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }

        val root = FrameLayout(this).apply { setBackgroundColor(PETROL) }
        root.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(zoomLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(errorOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(buildTopBar(), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        root.addView(buildBottomSheet(), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        setContentView(root)
        root.post {
            Tour.lanzar(this, "display", listOf(
                Tour.Paso(
                    "Controles del visor",
                    "Toque ▴ Controles para elegir cómo ver el escritorio del host.",
                    blanco = { handle },
                ),
            ))
        }

        updateButtons()
        // Resolver el endpoint del noVNC (forward local del Tailscale embebido, o directo)
        // fuera del hilo principal, y recién ahí cargar la URL.
        thread {
            // Preferimos tunelizar el noVNC por SSH: así el host puede publicarlo sólo en
            // su loopback y deja de estar expuesto a la red local. Si el túnel no se puede
            // tender (host viejo, sin sshd), caemos al endpoint directo de siempre.
            // El password del visor viaja por la conexión SSH del usuario, no por la red:
            // el archivo es 0600 suyo en el host. Sin esto habría que tipearlo a mano.
            vncPass = runCatching {
                RemoteControl(this, sshHost, sshPort, sshUser, KeyStoreSsh.getOrCreateKeyPair())
                    .vncPassword()
            }.getOrNull()

            val t = PortTunnel(this, sshHost, sshPort, sshUser, KeyStoreSsh.getOrCreateKeyPair())
            val localPort = t.open(6080)
            if (localPort != null) {
                tunnel = t
                novncHost = "127.0.0.1"; novncPort = localPort
            } else {
                t.close()
                val (h, p) = TailscaleBridge.endpoint(sshHost, 6080)
                novncHost = h; novncPort = p
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                web.loadUrl(url())
            }
        }
    }

    /**
     * Si la carga por el túnel falla (p.ej. el host todavía publica el 6080 y el forward
     * no sirve), se reintenta UNA vez por el endpoint directo. Sin esto, un túnel que se
     * crea pero no funciona deja el visor en una pantalla de error.
     */
    private var triedDirect = false

    private fun fallbackToDirect() {
        if (triedDirect || novncHost != "127.0.0.1") return
        triedDirect = true
        thread {
            tunnel?.close(); tunnel = null
            val (h, p) = TailscaleBridge.endpoint(sshHost, 6080)
            novncHost = h; novncPort = p
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                web.loadUrl(url())
            }
        }
    }

    /** Muestra un mensaje legible encima del visor cuando la WebView no puede cargar (en vez
     *  de la página de error cruda de Chrome). Se corre en el hilo de UI. */
    private fun mostrarErrorVisor(texto: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            errorOverlay.text = texto
            errorOverlay.visibility = View.VISIBLE
        }
    }

    private fun url(): String {
        val base = "http://$novncHost:$novncPort/vnc.html?autoconnect=1&reconnect=1" +
            "&reconnect_delay=2000&resize=" + if (fitMode) "remote" else "scale"
        // noVNC toma el password de la query (UI.connect -> WebUtil.getConfigVar). Queda en
        // una URL local del propio WebView, sobre un túnel SSH, y se vence al reiniciar el
        // contenedor. Si el host es viejo y no lo publica, se carga sin él: noVNC lo pide.
        val p = vncPass ?: return base
        return base + "&password=" + java.net.URLEncoder.encode(p, "UTF-8")
    }

    // --- zoom nítido vía la API interna de noVNC ---
    // El container expone UI en window.__UI (parche en ui.js); usamos su API de zoom.
    private fun js(body: String) = web.evaluateJavascript("(function(){try{var r=window.__UI&&__UI.rfb;if(r){$body}}catch(e){}})();", null)

    /** Pasa noVNC a modo recortado (clip) y fija la escala/región según curScale. */
    private fun setNovncZoom(reapplyDelays: Boolean = false) {
        val cssW = web.width / density
        val cssH = web.height / density
        val vw = cssW / curScale
        val vh = cssH / curScale
        val body = "r.scaleViewport=false;r.clipViewport=true;" +
            "r._display.scale=$curScale;r._display.viewportChangeSize($vw,$vh);"
        if (reapplyDelays) listOf(0L, 400L, 1000L).forEach { d -> web.postDelayed({ js(body) }, d) }
        else js(body)
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            // límite superior 1.0 = 1 px de framebuffer por px CSS (nítido); abajo, el fit.
            curScale = (curScale * d.scaleFactor).coerceIn(minOf(fitScale, 1.0f), 1.0f)
            setNovncZoom()
            return true
        }
    }

    private fun onZoomTouch(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP -> { lastX = ev.x; lastY = ev.y }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && ev.pointerCount == 1) {
                    // arrastre -> panear el viewport (en px de framebuffer; signo invertido)
                    val dx = -((ev.x - lastX) / density) / curScale
                    val dy = -((ev.y - lastY) / density) / curScale
                    lastX = ev.x; lastY = ev.y
                    js("r._display.viewportChangePos(${dx.toInt()},${dy.toInt()});")
                }
            }
        }
        return true
    }

    // --- barra superior ---
    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BAR_BG)
        }
        bar.addView(barButton("‹ Volver", GREEN) { finish() })
        bar.addView(TextView(this).apply {
            text = "navegador headed"
            typeface = monoFont
            setTextColor(FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            gravity = Gravity.CENTER
        })
        bar.addView(ImageView(this).apply {
            setImageResource(R.drawable.marvin_isologo_bar)
            adjustViewBounds = true
            setPadding(dp(6), dp(4), dp(12), dp(4))
        }, LinearLayout.LayoutParams(WRAP, dp(22)))
        return bar
    }

    // --- panel inferior que se despliega de abajo hacia arriba (full width) ---
    private fun buildBottomSheet(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BAR_BG)
            visibility = View.GONE
        }
        btnFit = tabButton(Iconos.etiqueta(this, Iconos.AJUSTAR, FG, 14f, "Pantalla")) { setMode(true) }
        btnDesk = tabButton(Iconos.etiqueta(this, Iconos.PANTALLA, FG, 14f, "Escritorio")) { setMode(false) }
        btnZoom = tabButton(Iconos.etiqueta(this, Iconos.ZOOM, FG, 14f, "Zoom")) { toggleZoom() }
        panel.addView(btnFit)
        panel.addView(btnDesk)
        panel.addView(btnZoom)                                  // reserva su lugar (INVISIBLE en Pantalla)
        panel.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))   // empuja ↻ a la derecha
        panel.addView(tabButton(Iconos.etiqueta(this, Iconos.RECARGAR, FG, 16f, "")) { web.reload() })

        handle = TextView(this).apply {
            text = "▴  Controles"
            typeface = monoFont
            gravity = Gravity.CENTER
            setTextColor(GREEN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setBackgroundColor(PETROL)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            setOnClickListener { toggleSheet() }
        }
        box.addView(panel, LinearLayout.LayoutParams(MATCH, WRAP))
        box.addView(handle, LinearLayout.LayoutParams(MATCH, WRAP))
        return box
    }

    /** Botón del panel: ancho fijo (wrap), posición estable (no se reacomoda). */
    private fun tabButton(label: CharSequence, onTap: () -> Unit) = TextView(this).apply {
        text = label
        typeface = monoFont
        gravity = Gravity.CENTER
        setTextColor(FG)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setOnClickListener { onTap() }
    }

    private fun toggleSheet() {
        drawerOpen = !drawerOpen
        panel.visibility = if (drawerOpen) View.VISIBLE else View.GONE
        handle.text = if (drawerOpen) "▾  Controles" else "▴  Controles"
        if (drawerOpen) panel.post {
            Tour.lanzar(this, "display_panel", listOf(
                Tour.Paso(
                    "Pantalla",
                    "El escritorio se adapta al tamaño del teléfono. Es el modo habitual.",
                    blanco = { btnFit },
                ),
                // El botón Zoom queda INVISIBLE en modo Pantalla (reserva el lugar), así
                // que su explicación va acá y no en un paso propio que casi nunca se vería.
                Tour.Paso(
                    "Escritorio",
                    "Muestra el escritorio completo en 1920×1080, como en un monitor. " +
                        "En este modo se habilita Zoom: pellizque para acercar con " +
                        "nitidez y arrastre para desplazarse.",
                    blanco = { btnDesk },
                ),
            ))
        }
    }

    // --- modos ---
    private fun setMode(fit: Boolean) {
        if (fitMode == fit) return
        fitMode = fit
        if (fitMode) { zoomOn = false; zoomLayer.visibility = View.GONE }
        updateButtons()
        if (fitMode) {
            web.loadUrl(url())
        } else {
            Toast.makeText(this, "Escritorio — activá Zoom para pellizcar", Toast.LENGTH_SHORT).show()
            thread {
                control.setDisplayMode("1920x1080")
                runOnUiThread { web.loadUrl(url()) }
            }
        }
    }

    private fun toggleZoom() {
        if (fitMode) return
        zoomOn = !zoomOn
        if (zoomOn) {
            fitScale = (web.width / density) / FB_W
            curScale = curScale.coerceIn(minOf(fitScale, 1.0f), 1.0f)
            zoomLayer.visibility = View.VISIBLE
            setNovncZoom(reapplyDelays = true)   // pasa a clip + escala
        } else {
            zoomLayer.visibility = View.GONE      // se MANTIENE la vista (clip+escala) y mapea el mouse
        }
        updateButtons()
        Toast.makeText(
            this,
            if (zoomOn) "Zoom: pellizcá (nítido), arrastrá para mover" else "Mouse (la vista queda igual)",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun updateButtons() {
        btnFit.setTextColor(if (fitMode) GREEN else MUTED)     // Pantalla
        btnDesk.setTextColor(if (!fitMode) GREEN else MUTED)   // Escritorio
        btnZoom.visibility = if (fitMode) View.INVISIBLE else View.VISIBLE   // reserva lugar, no se mueven los otros
        btnZoom.setTextColor(if (zoomOn) GREEN else FG)
    }

    private fun barButton(label: String, color: Int, onTap: () -> Unit) = TextView(this).apply {
        text = label
        typeface = monoFont
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setOnClickListener { onTap() }
    }

    override fun onDestroy() {
        val t = tunnel; tunnel = null
        Hilos.cerrarEnFondo("visor-cierre") { t?.close() }   // close de trilead fuera del main (SRE-5-4)
        // sacarla de la jerarquía antes de destruirla (requisito de WebView)
        (web.parent as? android.view.ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    /** ¿Está visible el mensaje de error traducido? (WS-J: en vez de la página cruda de Chrome). */
    @androidx.annotation.VisibleForTesting
    fun errorVisibleParaTest() = errorOverlay.visibility == View.VISIBLE

    /** Texto actual del mensaje de error del visor (para los tests). */
    @androidx.annotation.VisibleForTesting
    fun errorTextoParaTest() = errorOverlay.text?.toString().orEmpty()

    companion object {
        private const val FB_W = 1920f
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val PETROL = Paleta.KEYPAD_BG
        private val BAR_BG = Paleta.CHEV_BG
        private val FG = Paleta.KEY_FG
        private val MUTED = Paleta.CHEV_FG    // opción inactiva
        private val GREEN = Paleta.ACCENT
    }
}
