package com.remoteclaude.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * Visor nativo de un documento compartido. Baja el archivo por SSH (base64) y lo renderiza
 * según el tipo: imágenes con pinch-zoom, PDF con PdfRenderer (páginas), texto monospace.
 */
class DocViewerActivity : AppCompatActivity() {

    private val monoFont by lazy { resources.getFont(R.font.mononoki) }
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = getColor(R.color.marvin_petrol)

        val host = intent.getStringExtra("hostname") ?: "remoteclaude"
        val port = intent.getIntExtra("port", 22)
        val user = intent.getStringExtra("user") ?: "root"
        val name = intent.getStringExtra("name") ?: run { finish(); return }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.marvin_petrol))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(8))
        }
        header.addView(TextView(this).apply {
            text = "‹ Volver"
            typeface = monoFont
            setTextColor(getColor(R.color.marvin_green))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(4), dp(8), dp(16), dp(8))
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = name
            typeface = monoFont
            setTextColor(getColor(R.color.marvin_fg))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isSingleLine = true
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        root.addView(header)

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)

        // El tamaño ya venía por Intent pero no se usaba: bajar un doc grande cuesta
        // ~8x su tamaño en memoria (base64 -> String UTF-16 -> replace -> decode) y el
        // OutOfMemoryError NO lo atrapaba el catch(Exception) -> se moría el proceso.
        val size = intent.getLongExtra("size", 0L)
        if (size > MAX_DOC_BYTES) {
            message("El documento pesa ${size / 1024 / 1024} MB; el visor admite hasta " +
                "${MAX_DOC_BYTES / 1024 / 1024} MB. Bajalo por la terminal.")
            return
        }

        message("Cargando $name…")
        val control = RemoteControl(this, host, port, user, KeyStoreSsh.getOrCreateKeyPair())
        thread {
            val bytes = try {
                val b64 = control.readDocBase64(name)
                if (b64.isBlank()) null else Base64.decode(b64, Base64.DEFAULT)
            } catch (_: Throwable) {
                null   // Throwable, no Exception: OutOfMemoryError es un Error
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (bytes == null || bytes.isEmpty()) { message("No pude bajar el documento."); return@runOnUiThread }
                try {
                    render(name, bytes)
                } catch (_: Throwable) {
                    message("No pude mostrar el documento (¿demasiado grande?).")
                }
            }
        }
    }

    private fun render(name: String, bytes: ByteArray) {
        content.removeAllViews()
        when (DocKind.of(name)) {
            DocKind.IMAGE -> showImage(bytes)
            DocKind.PDF -> showPdf(bytes)
            DocKind.TEXT -> showText(bytes)
            DocKind.OTHER -> message("Tipo no soportado en el visor. Bajalo por la terminal.")
        }
    }

    private fun showImage(bytes: ByteArray) {
        // Primero se leen sólo las dimensiones para elegir un submuestreo: una foto de
        // 12 MP en ARGB_8888 son ~48 MB y tumbaba la app.
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probe)
        val maxW = resources.displayMetrics.widthPixels.coerceAtLeast(1)
        var sample = 1
        while (probe.outWidth / sample > maxW * 2) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: return message("No pude decodificar la imagen.")
        content.addView(
            ZoomableImageView(this).apply { setImageBitmap(bmp) },
            LinearLayout.LayoutParams(MATCH, MATCH),
        )
    }

    private fun showPdf(bytes: ByteArray) {
        // Archivo propio por apertura: con un nombre fijo, abrir un segundo documento
        // pisaba el que todavía estaba renderizando el anterior.
        val file = File.createTempFile("view", ".pdf", cacheDir).apply {
            writeBytes(bytes); deleteOnExit()
        }
        val scroll = ScrollView(this)
        val pages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        scroll.addView(pages)
        content.addView(scroll, LinearLayout.LayoutParams(MATCH, MATCH))
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val targetW = min(resources.displayMetrics.widthPixels, 1440)
                    for (i in 0 until renderer.pageCount) {
                        renderer.openPage(i).use { page ->
                            val h = (targetW.toFloat() / page.width * page.height).toInt()
                            // RGB_565 = la mitad de memoria por página; sin alfa no se
                            // pierde nada visible en un PDF.
                            val bmp = Bitmap.createBitmap(targetW, h, Bitmap.Config.RGB_565)
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            pages.addView(ImageView(this).apply {
                                setImageBitmap(bmp)
                                adjustViewBounds = true
                                val lp = LinearLayout.LayoutParams(MATCH, WRAP)
                                lp.setMargins(0, 0, 0, dp(8))
                                layoutParams = lp
                            })
                        }
                    }
                }
            }
        } catch (e: Exception) {
            message("No pude abrir el PDF: ${e.message}")
        }
    }

    private fun showText(bytes: ByteArray) {
        val text = String(bytes, Charsets.UTF_8)
        val tv = TextView(this).apply {
            this.text = text
            typeface = monoFont
            setTextColor(getColor(R.color.marvin_fg))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextIsSelectable(true)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        // Scroll vertical + horizontal (para CSV/líneas largas).
        val hs = android.widget.HorizontalScrollView(this).apply { addView(tv) }
        content.addView(ScrollView(this).apply { addView(hs) },
            LinearLayout.LayoutParams(MATCH, MATCH))
    }

    private fun message(msg: String) {
        content.removeAllViews()
        content.addView(TextView(this).apply {
            text = msg
            typeface = monoFont
            setTextColor(getColor(R.color.marvin_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        })
    }

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    /** ImageView con pinch-zoom + arrastre (matriz). */
    private class ZoomableImageView(ctx: android.content.Context) : AppCompatImageView(ctx) {
        private val m = Matrix()
        private var scale = 1f
        private var fitScale = 1f
        private var lastX = 0f
        private var lastY = 0f
        private val sgd = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val f = (scale * d.scaleFactor).coerceIn(fitScale, fitScale * 8f) / scale
                m.postScale(f, f, d.focusX, d.focusY)
                scale *= f
                imageMatrix = m
                return true
            }
        })

        init {
            scaleType = ScaleType.MATRIX
        }

        override fun setImageBitmap(bm: Bitmap?) {
            super.setImageBitmap(bm)
            post { fit() }
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); fit() }

        private fun fit() {
            val d = drawable ?: return
            if (width == 0 || height == 0) return
            fitScale = min(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
            scale = fitScale
            m.reset()
            m.postScale(fitScale, fitScale)
            // centrar
            m.postTranslate(
                (width - d.intrinsicWidth * fitScale) / 2f,
                (height - d.intrinsicHeight * fitScale) / 2f,
            )
            imageMatrix = m
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            sgd.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
                MotionEvent.ACTION_MOVE -> if (!sgd.isInProgress && scale > fitScale) {
                    m.postTranslate(e.x - lastX, e.y - lastY)
                    lastX = e.x; lastY = e.y
                    imageMatrix = m
                }
            }
            parent?.requestDisallowInterceptTouchEvent(scale > fitScale || sgd.isInProgress)
            return true
        }
    }

    companion object {
        /** Tope de descarga: arriba de esto el pico de memoria tumba el proceso. */
        private const val MAX_DOC_BYTES = 8L * 1024 * 1024
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    }
}
