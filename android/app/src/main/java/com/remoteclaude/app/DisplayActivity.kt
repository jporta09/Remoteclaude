package com.remoteclaude.app

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * Visor del navegador headed (noVNC del contenedor display). Dos modos, vía dos botones:
 *  - Ajustar a pantalla (default): resize=remote -> el escritorio se adapta y LLENA el visor
 *    (en vertical u horizontal, según rotes el celu).
 *  - Mostrar escritorio: resize=scale -> el escritorio se ve completo (no se deforma al visor);
 *    ahí se habilita el botón Zoom para pellizcar con 2 dedos y agrandar/achicar un sector.
 * La orientación es libre en ambos modos (sigue el auto-giro del celu).
 */
class DisplayActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var btnFit: TextView
    private lateinit var btnZoom: TextView
    private var fitMode = true     // true = ajustar a pantalla (llena); false = mostrar escritorio
    private var zoomOn = false     // pinch-zoom (sólo en modo escritorio)
    private val monoFont by lazy { resources.getFont(R.font.mononoki) }
    // Para poner el display en landscape al mostrar "Escritorio" (el modo "ajustar" lo
    // deja vertical). El display vive en el gateway remoteclaude (mismo que el noVNC).
    private val control by lazy { RemoteControl("remoteclaude", 22, "root", KeyStoreSsh.getOrCreateKeyPair()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = PETROL

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.displayZoomControls = false
            settings.mediaPlaybackRequiresUserGesture = false
            setBackgroundColor(PETROL)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    // En modo escritorio + zoom, permitir el pinch del WebView (noVNC por
                    // defecto bloquea el zoom con user-scalable=no).
                    if (!fitMode && zoomOn) enablePageZoom()
                }
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PETROL)
        }
        root.addView(buildBar(), LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(web, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)

        applyZoomSettings()
        updateButtons()
        web.loadUrl(url())
    }

    private fun url() =
        "http://remoteclaude:6080/vnc.html?autoconnect=1&reconnect=1&reconnect_delay=2000&resize=" +
            if (fitMode) "remote" else "scale"

    /** Botón 1: ajustar a pantalla <-> mostrar escritorio. */
    private fun toggleFit() {
        fitMode = !fitMode
        if (fitMode) zoomOn = false          // al volver a "ajustada", sin zoom
        applyZoomSettings()
        updateButtons()
        if (fitMode) {
            web.loadUrl(url())               // resize=remote vuelve a llenar el visor
            Toast.makeText(this, "Ajustado a pantalla", Toast.LENGTH_SHORT).show()
        } else {
            // Escritorio: poner el display en landscape (1280x720) y mostrarlo a escala.
            Toast.makeText(this, "Escritorio — usá Zoom para pellizcar", Toast.LENGTH_SHORT).show()
            thread {
                control.setDisplayMode("1280x720")
                runOnUiThread { web.loadUrl(url()) }
            }
        }
    }

    /** Botón 2: zoom (pinch) on/off — sólo en modo escritorio. */
    private fun toggleZoom() {
        if (fitMode) return
        zoomOn = !zoomOn
        applyZoomSettings()
        updateButtons()
        web.loadUrl(url())   // recargar resetea el zoom y re-inyecta el viewport
    }

    private fun applyZoomSettings() {
        val on = zoomOn && !fitMode
        web.settings.setSupportZoom(on)
        web.settings.builtInZoomControls = on
    }

    private fun enablePageZoom() {
        web.evaluateJavascript(
            "var m=document.querySelector('meta[name=viewport]');" +
                "if(m){m.setAttribute('content'," +
                "'width=device-width, initial-scale=1.0, maximum-scale=8.0, user-scalable=yes');}",
            null,
        )
    }

    private fun updateButtons() {
        btnFit.text = if (fitMode) "⛶ Ajustada" else "🖥 Escritorio"
        btnFit.setTextColor(if (fitMode) GREEN else FG)
        btnZoom.visibility = if (fitMode) View.GONE else View.VISIBLE
        btnZoom.setTextColor(if (zoomOn) GREEN else FG)
    }

    private fun buildBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BAR_BG)
        }
        bar.addView(barButton("‹", GREEN) { finish() })
        btnFit = barButton("⛶ Ajustada", GREEN) { toggleFit() }
        bar.addView(btnFit)
        btnZoom = barButton("🔍 Zoom", FG) { toggleZoom() }
        bar.addView(btnZoom)
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))   // separador flexible
        bar.addView(barButton("↻", GREEN) { web.reload() })
        bar.addView(ImageView(this).apply {
            setImageResource(R.drawable.marvin_isologo_bar)
            adjustViewBounds = true
            setPadding(dp(6), 0, dp(12), 0)
        }, LinearLayout.LayoutParams(WRAP, dp(22)))
        return bar
    }

    private fun barButton(label: String, color: Int, onTap: () -> Unit) = TextView(this).apply {
        text = label
        typeface = monoFont
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setOnClickListener { onTap() }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val PETROL = Color.parseColor("#0F232D")   // verde petróleo (fondo)
        private val BAR_BG = Color.parseColor("#0A1A20")   // petróleo más oscuro (barra)
        private val FG = Color.parseColor("#F2F2F2")       // gris claro
        private val GREEN = Color.parseColor("#71BF44")    // verde CTR (acentos)
    }
}
