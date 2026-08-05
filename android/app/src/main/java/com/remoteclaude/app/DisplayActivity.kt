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
    private var tunnel: PortTunnel? = null
    private var curScale = 0.2f   // noVNC display.scale (CSS px por px del framebuffer)
    private var fitScale = 0.2f
    private var lastX = 0f
    private var lastY = 0f
    private lateinit var scaleDetector: ScaleGestureDetector

    private val density get() = resources.displayMetrics.density
    private val monoFont by lazy { resources.getFont(R.font.mononoki) }
    private val control by lazy { RemoteControl(sshHost, sshPort, sshUser, KeyStoreSsh.getOrCreateKeyPair()) }

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
            webViewClient = WebViewClient()
        }

        scaleDetector = ScaleGestureDetector(this, ScaleListener())
        zoomLayer = View(this).apply {
            visibility = View.GONE
            setOnTouchListener { _, ev -> onZoomTouch(ev) }
        }

        val root = FrameLayout(this).apply { setBackgroundColor(PETROL) }
        root.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(zoomLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(buildTopBar(), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        root.addView(buildBottomSheet(), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        setContentView(root)

        updateButtons()
        // Resolver el endpoint del noVNC (forward local del Tailscale embebido, o directo)
        // fuera del hilo principal, y recién ahí cargar la URL.
        thread {
            // Preferimos tunelizar el noVNC por SSH: así el host puede publicarlo sólo en
            // su loopback y deja de estar expuesto a la red local. Si el túnel no se puede
            // tender (host viejo, sin sshd), caemos al endpoint directo de siempre.
            val t = PortTunnel(sshHost, sshPort, sshUser, KeyStoreSsh.getOrCreateKeyPair())
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

    private fun url() =
        "http://$novncHost:$novncPort/vnc.html?autoconnect=1&reconnect=1&reconnect_delay=2000&resize=" +
            if (fitMode) "remote" else "scale"

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
        btnFit = tabButton("⛶ Pantalla") { setMode(true) }
        btnDesk = tabButton("🖥 Escritorio") { setMode(false) }
        btnZoom = tabButton("🔍 Zoom") { toggleZoom() }
        panel.addView(btnFit)
        panel.addView(btnDesk)
        panel.addView(btnZoom)                                  // reserva su lugar (INVISIBLE en Pantalla)
        panel.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))   // empuja ↻ a la derecha
        panel.addView(tabButton("↻") { web.reload() })

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
    private fun tabButton(label: String, onTap: () -> Unit) = TextView(this).apply {
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
        tunnel?.close(); tunnel = null
        // sacarla de la jerarquía antes de destruirla (requisito de WebView)
        (web.parent as? android.view.ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val FB_W = 1920f
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val PETROL = Color.parseColor("#0F232D")
        private val BAR_BG = Color.parseColor("#0A1A20")
        private val FG = Color.parseColor("#F2F2F2")
        private val MUTED = Color.parseColor("#5E8B7E")    // opción inactiva
        private val GREEN = Color.parseColor("#71BF44")
    }
}
