package com.remoteclaude.app

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * M6: visor del navegador headed. Embebe el noVNC del contenedor `remoteclaude-display`
 * (servido por HTTP en la tailnet) en un WebView a pantalla completa. El navegador corre
 * EN EL HOST y dibuja en el Xvfb del contenedor; acá solo se ve/controla.
 *
 * noVNC necesita JS + WebSockets + canvas. La URL lleva autoconnect, resize=scale (encaja
 * la pantalla 1360x768 al WebView) y reconnect (se re-engancha solo si el VNC se cae).
 */
class DisplayActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private val monoFont by lazy { resources.getFont(R.font.mononoki) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = PETROL

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true   // pinch para acercar el canvas
            settings.displayZoomControls = false
            settings.mediaPlaybackRequiresUserGesture = false
            setBackgroundColor(PETROL)             // petróleo mientras carga (sin flash blanco)
            webViewClient = WebViewClient()        // mantener la navegación dentro del WebView
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PETROL)
        }
        root.addView(buildBar(), LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(web, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)

        web.loadUrl(URL)
    }

    private fun buildBar(): LinearLayout {
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
        bar.addView(barButton("↻", GREEN) { web.reload() })
        bar.addView(ImageView(this).apply {
            setImageResource(R.drawable.marvin_iso)
            adjustViewBounds = true
            setPadding(dp(6), 0, dp(12), 0)
        }, LinearLayout.LayoutParams(WRAP, dp(20)))
        return bar
    }

    private fun barButton(label: String, color: Int, onTap: () -> Unit) = TextView(this).apply {
        text = label
        typeface = monoFont
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setOnClickListener { onTap() }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        // Mismo host de MagicDNS que usa el SSH; noVNC en :6080 del contenedor display.
        private const val URL =
            "http://remoteclaude:6080/vnc.html?autoconnect=1&resize=scale&reconnect=1&reconnect_delay=2000"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val PETROL = Color.parseColor("#0F232D")   // verde petróleo (fondo)
        private val BAR_BG = Color.parseColor("#0A1A20")   // petróleo más oscuro (barra)
        private val FG = Color.parseColor("#F2F2F2")       // gris claro
        private val GREEN = Color.parseColor("#71BF44")    // verde CTR (acentos)
    }
}
