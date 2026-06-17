package com.remoteclaude.app

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
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
 * Visor del navegador headed (noVNC). Barra superior slim (volver / título / isologo) y
 * un cajón colapsable abajo-izquierda (flecha, estilo noVNC) con los controles:
 *  - Ajustada (resize=remote): el escritorio LLENA el visor.
 *  - Escritorio (resize=scale + display 1280x720 landscape vía xrandr): se ve apaisado.
 *  - Zoom: habilita el pinch de 2 dedos (sólo útil en Escritorio).
 * Orientación libre (no se fuerza).
 */
class DisplayActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var btnFit: TextView
    private lateinit var btnZoom: TextView
    private lateinit var panel: LinearLayout
    private lateinit var arrow: TextView
    private var fitMode = true
    private var zoomOn = false
    private var drawerOpen = false
    private val monoFont by lazy { resources.getFont(R.font.mononoki) }
    private val control by lazy { RemoteControl("remoteclaude", 22, "root", KeyStoreSsh.getOrCreateKeyPair()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = PETROL

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mediaPlaybackRequiresUserGesture = false
            setBackgroundColor(PETROL)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) = applyViewportZoom()
            }
        }

        val root = FrameLayout(this).apply { setBackgroundColor(PETROL) }
        root.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(buildTopBar(), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        root.addView(buildDrawer(), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START))
        setContentView(root)

        updateButtons()
        web.loadUrl(url())
    }

    private fun url() =
        "http://remoteclaude:6080/vnc.html?autoconnect=1&reconnect=1&reconnect_delay=2000&resize=" +
            if (fitMode) "remote" else "scale"

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

    // --- cajón colapsable (abajo-izquierda, estilo noVNC) ---
    private fun buildDrawer(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        arrow = TextView(this).apply {
            text = "▸"
            typeface = monoFont
            setTextColor(GREEN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setBackgroundColor(BAR_BG)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { toggleDrawer() }
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BAR_BG)
            visibility = View.GONE
        }
        btnFit = barButton("⛶ Ajustada", GREEN) { toggleFit() }
        btnZoom = barButton("🔍 Zoom", FG) { toggleZoom() }
        panel.addView(btnFit)
        panel.addView(btnZoom)
        panel.addView(barButton("↻", GREEN) { web.reload() })
        row.addView(arrow)
        row.addView(panel)
        return row
    }

    private fun toggleDrawer() {
        drawerOpen = !drawerOpen
        panel.visibility = if (drawerOpen) View.VISIBLE else View.GONE
        arrow.text = if (drawerOpen) "◂" else "▸"
    }

    // --- modos ---
    private fun toggleFit() {
        fitMode = !fitMode
        if (fitMode) zoomOn = false
        updateButtons()
        if (fitMode) {
            web.loadUrl(url())
            Toast.makeText(this, "Ajustado a pantalla", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Escritorio — activá Zoom para pellizcar", Toast.LENGTH_SHORT).show()
            thread {
                control.setDisplayMode("1280x720")
                runOnUiThread { web.loadUrl(url()) }
            }
        }
    }

    private fun toggleZoom() {
        if (fitMode) return
        zoomOn = !zoomOn
        updateButtons()
        applyViewportZoom()   // cambia user-scalable en vivo (sin recargar)
        Toast.makeText(this, if (zoomOn) "Zoom: pellizcá con 2 dedos" else "Zoom off", Toast.LENGTH_SHORT).show()
    }

    /** Permite o no el pinch del WebView reescribiendo el viewport. Se reaplica con delays
     *  porque noVNC fija user-scalable=no durante su init (después del onPageFinished). */
    private fun applyViewportZoom() {
        val yes = zoomOn && !fitMode
        val js = "var m=document.querySelector('meta[name=viewport]');" +
            "if(m){m.setAttribute('content','width=device-width, initial-scale=1.0, " +
            "maximum-scale=${if (yes) "10.0" else "1.0"}, user-scalable=${if (yes) "yes" else "no"}');}"
        val h = Handler(Looper.getMainLooper())
        listOf(0L, 1200L, 2500L).forEach { d -> h.postDelayed({ web.evaluateJavascript(js, null) }, d) }
    }

    private fun updateButtons() {
        btnFit.text = if (fitMode) "⛶ Ajustada" else "🖥 Escritorio"
        btnFit.setTextColor(if (fitMode) GREEN else FG)
        btnZoom.visibility = if (fitMode) View.GONE else View.VISIBLE
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
        web.destroy()
        super.onDestroy()
    }

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val PETROL = Color.parseColor("#0F232D")
        private val BAR_BG = Color.parseColor("#0A1A20")
        private val FG = Color.parseColor("#F2F2F2")
        private val GREEN = Color.parseColor("#71BF44")
    }
}
