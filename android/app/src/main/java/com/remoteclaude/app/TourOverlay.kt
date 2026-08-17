package com.remoteclaude.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * La capa que dibuja la demo: scrim oscuro con un agujero sobre el blanco del paso y una
 * burbuja de texto al lado. Tocar en cualquier lado avanza; "Omitir demo" cierra.
 *
 * La geometría de agujero y burbuja usa el chaflán (esquinas cortadas) de la cápsula del
 * isotipo — la app no tiene ni una esquina redondeada y acá tampoco.
 */
class TourOverlay(
    ctx: Context,
    private val pasos: List<Tour.Paso>,
    private val alCerrar: () -> Unit,
) : FrameLayout(ctx) {

    private val scrim = 0xCC0A1A20.toInt()   // CHEV_BG al 80%
    private val agujero = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    // El marco vívido alrededor del blanco: adentro la app a pleno, afuera el scrim, y el
    // borde verde con el mismo chaflán marca exactamente de qué botón habla la burbuja.
    private val marco = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Paleta.ACCENT
        strokeWidth = ctx.dp(2).toFloat()
        strokeJoin = Paint.Join.ROUND
    }
    private var blanco: RectF? = null
    private var actual = -1

    private val titulo = TextView(ctx).apply {
        typeface = resources.getFont(R.font.mononoki)
        setTextColor(Paleta.ACCENT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    }
    private val cuerpo = TextView(ctx).apply {
        typeface = context.fuenteDetalle()
        setTextColor(Paleta.KEY_FG)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(0, context.dp(4), 0, context.dp(8))
    }
    private val codigo = TextView(ctx).apply {
        typeface = resources.getFont(R.font.mononoki)
        setTextColor(Paleta.ACCENT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        setBackgroundColor(Paleta.CHEV_BG)
        val p = context.dp(10)
        setPadding(p, context.dp(7), p, context.dp(7))
        visibility = GONE
    }
    private val saltar = TextView(ctx).apply {
        text = "Omitir demo"
        typeface = context.fuenteDetalle()
        setTextColor(Paleta.CHEV_FG)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, context.dp(4), context.dp(16), context.dp(4))
        setOnClickListener { cerrar() }
    }
    private val contador = TextView(ctx).apply {
        typeface = context.fuenteDetalle()
        setTextColor(Paleta.CHEV_FG)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    private val burbuja = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = FondoChaflan(Paleta.TAB_ACTIVE_BG, context.dp(10).toFloat())
        val p = context.dp(16)
        setPadding(p, context.dp(12), p, context.dp(10))
        addView(titulo)
        addView(cuerpo)
        addView(codigo, LinearLayout.LayoutParams(Paleta.MATCH, Paleta.WRAP).apply {
            bottomMargin = ctx.dp(8)
        })
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(saltar)
            addView(contador, LinearLayout.LayoutParams(0, Paleta.MATCH, 1f))
        })
    }

    // Con el IME abriéndose/cerrándose (ADJUST_RESIZE) y la rotación de MainActivity (que
    // no se recrea), el blanco se mueve: se re-mide en cada pasada de layout del decor.
    private val relayout = ViewTreeObserver.OnGlobalLayoutListener { colocar() }

    init {
        setWillNotDraw(false)
        isClickable = true
        setOnClickListener { avanzar(actual + 1) }
        addView(burbuja, LayoutParams(Paleta.MATCH, Paleta.WRAP))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rootView.viewTreeObserver.addOnGlobalLayoutListener(relayout)
        if (actual < 0) post { avanzar(0) }
    }

    override fun onDetachedFromWindow() {
        rootView.viewTreeObserver.removeOnGlobalLayoutListener(relayout)
        super.onDetachedFromWindow()
    }

    private fun avanzar(desde: Int) {
        val i = Tour.proximoPaso(pasos.map { mostrable(it) }, desde)
        if (i < 0) { cerrar(); return }
        actual = i
        val paso = pasos[i]
        titulo.text = paso.titulo
        cuerpo.text = paso.texto
        codigo.text = paso.codigo ?: ""
        codigo.visibility = if (paso.codigo != null) VISIBLE else GONE
        contador.text = "${i + 1}/${pasos.size} · toque para continuar"
        colocar()
        announceForAccessibility("${paso.titulo}. ${paso.texto}")
    }

    private fun mostrable(paso: Tour.Paso): Boolean {
        val blanco = paso.blanco ?: return true       // paso sin blanco: burbuja centrada
        paso.preparar?.invoke()
        val v = blanco() ?: return false
        return v.isAttachedToWindow && v.isShown && v.width > 0 && v.height > 0
    }

    /**
     * Re-mide el blanco del paso actual y acomoda burbuja y agujero.
     *
     * IDEMPOTENTE a propósito: corre en cada global-layout del decor, y setear
     * layoutParams (o invalidar) sin que nada haya cambiado dispara OTRO layout — el
     * listener se retroalimenta y el main thread queda girando traversals para siempre
     * (se descubrió como cuelgue del E2E: la cola siempre detrás de la barrera de sync).
     */
    private fun colocar() {
        if (actual < 0 || width == 0) return
        val paso = pasos[actual]
        val v = paso.blanco?.invoke()
        val margen = context.dp(16)
        val nuevoBlanco: RectF?
        val gravedad: Int
        val margenSup: Int
        if (v == null || !v.isAttachedToWindow || !v.isShown) {
            nuevoBlanco = null
            gravedad = Gravity.CENTER_VERTICAL
            margenSup = 0
        } else {
            val yo = IntArray(2).also { getLocationInWindow(it) }
            val el = IntArray(2).also { v.getLocationInWindow(it) }
            val aire = context.dp(6)
            // Recortado a los bordes de la pantalla: con blancos de ancho completo (la
            // barra del host, la terminal, la fila Shift) el marco se iba afuera y sus
            // lados no se veían — recortado queda como un indicador siempre visible.
            val borde = context.dp(5).toFloat()
            nuevoBlanco = RectF(
                (el[0] - yo[0] - aire).toFloat().coerceAtLeast(borde),
                (el[1] - yo[1] - aire).toFloat().coerceAtLeast(borde),
                (el[0] - yo[0] + v.width + aire).toFloat().coerceAtMost(width - borde),
                (el[1] - yo[1] + v.height + aire).toFloat().coerceAtMost(height - borde),
            )
            burbuja.measure(
                MeasureSpec.makeMeasureSpec(width - 2 * margen, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST),
            )
            gravedad = Gravity.TOP
            margenSup = Tour.posicionBurbuja(
                nuevoBlanco.top.toInt(), nuevoBlanco.bottom.toInt(),
                height, burbuja.measuredHeight, context.dp(12),
            )
        }
        val lp = burbuja.layoutParams as LayoutParams
        if (lp.gravity != gravedad || lp.topMargin != margenSup ||
            lp.leftMargin != margen || lp.rightMargin != margen
        ) {
            lp.gravity = gravedad
            lp.topMargin = margenSup
            lp.leftMargin = margen
            lp.rightMargin = margen
            burbuja.layoutParams = lp
        }
        if (nuevoBlanco != blanco) {
            blanco = nuevoBlanco
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val capa = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawColor(scrim)
        blanco?.let { canvas.drawPath(chaflan(it, context.dp(10).toFloat()), agujero) }
        canvas.restoreToCount(capa)
        blanco?.let { canvas.drawPath(chaflan(it, context.dp(10).toFloat()), marco) }
    }

    private fun cerrar() {
        (parent as? ViewGroup)?.removeView(this)
        alCerrar()
    }

    companion object {
        /** Rectángulo con las esquinas cortadas de la cápsula del isotipo. */
        fun chaflan(r: RectF, c: Float): Path = Path().apply {
            val ch = minOf(c, r.width() / 2, r.height() / 2)
            moveTo(r.left + ch, r.top)
            lineTo(r.right - ch, r.top)
            lineTo(r.right, r.top + ch)
            lineTo(r.right, r.bottom - ch)
            lineTo(r.right - ch, r.bottom)
            lineTo(r.left + ch, r.bottom)
            lineTo(r.left, r.bottom - ch)
            lineTo(r.left, r.top + ch)
            close()
        }
    }

    /** Fondo plano con chaflán para la burbuja (la app no usa esquinas redondeadas). */
    private class FondoChaflan(color: Int, private val chaflan: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

        override fun draw(canvas: Canvas) {
            canvas.drawPath(chaflan(RectF(bounds), chaflan), paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
