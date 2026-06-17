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
 * Visor del navegador headed (noVNC). Controles en un cajón colapsable abajo:
 *  - Ajustada (resize=remote): el escritorio LLENA el visor.
 *  - Escritorio (resize=scale + display 1280x720 vía xrandr): se ve apaisado completo.
 *  - Zoom (sólo en Escritorio): activa una capa de pinch-zoom PROPIA por encima de
 *    noVNC. noVNC captura los toques de su canvas (preventDefault), por eso el pinch del
 *    WebView no funciona; esta capa intercepta el gesto y magnifica/panea transformando
 *    el WebView (scale + translation). Tocar Zoom de nuevo vuelve a 1x e interactúa.
 * Orientación libre.
 */
class DisplayActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var zoomLayer: View
    private lateinit var btnFit: TextView
    private lateinit var btnZoom: TextView
    private lateinit var panel: LinearLayout
    private lateinit var arrow: TextView
    private var fitMode = true
    private var zoomOn = false
    private var drawerOpen = false

    // estado del pinch-zoom propio
    private var scale = 1f
    private var panX = 0f
    private var panY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private lateinit var scaleDetector: ScaleGestureDetector

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
            settings.mediaPlaybackRequiresUserGesture = false
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
        root.addView(zoomLayer, FrameLayout.LayoutParams(MATCH, MATCH))   // capa de pinch (sobre noVNC)
        root.addView(buildTopBar(), FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        root.addView(buildDrawer(), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START))
        setContentView(root)

        updateButtons()
        web.loadUrl(url())
    }

    private fun url() =
        "http://remoteclaude:6080/vnc.html?autoconnect=1&reconnect=1&reconnect_delay=2000&resize=" +
            if (fitMode) "remote" else "scale"

    // --- pinch-zoom propio (sobre noVNC) ---
    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val old = scale
            scale = (scale * d.scaleFactor).coerceIn(1f, 6f)
            val k = scale / old
            // zoom hacia el punto focal del pinch
            panX = d.focusX - (d.focusX - panX) * k
            panY = d.focusY - (d.focusY - panY) * k
            applyTransform()
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
                    panX += ev.x - lastX
                    panY += ev.y - lastY
                    lastX = ev.x; lastY = ev.y
                    applyTransform()
                }
            }
        }
        return true
    }

    private fun applyTransform() {
        val w = web.width.toFloat()
        val h = web.height.toFloat()
        panX = panX.coerceIn(w - scale * w, 0f)   // no dejar bordes vacíos
        panY = panY.coerceIn(h - scale * h, 0f)
        web.pivotX = 0f; web.pivotY = 0f
        web.scaleX = scale; web.scaleY = scale
        web.translationX = panX; web.translationY = panY
    }

    private fun resetZoom() {
        scale = 1f; panX = 0f; panY = 0f
        web.scaleX = 1f; web.scaleY = 1f; web.translationX = 0f; web.translationY = 0f
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

    // --- cajón colapsable (abajo-izquierda) ---
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
        if (fitMode) { zoomOn = false; zoomLayer.visibility = View.GONE; resetZoom() }
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
        // ON: la capa intercepta el pinch/pan. OFF: se oculta pero se MANTIENE el zoom/pan
        // actual; los toques pasan a noVNC y Android los mapea por la transformada inversa
        // del WebView, así el mouse cae en la posición correcta dentro de la vista magnificada.
        zoomLayer.visibility = if (zoomOn) View.VISIBLE else View.GONE
        updateButtons()
        Toast.makeText(
            this,
            if (zoomOn) "Zoom: pellizcá para acercar, arrastrá para mover" else "Mouse (la vista queda igual)",
            Toast.LENGTH_SHORT,
        ).show()
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
