package com.remoteclaude.app

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan

/**
 * Los íconos de la interfaz, dibujados por una fuente NUESTRA.
 *
 * Ninguna de las fuentes de marca tiene estos glifos, así que hasta acá los dibujaba la
 * fuente del sistema por fallback: se veían distintos según el fabricante del teléfono, y
 * ⧉ (el de "Sel") es tan poco frecuente que ni DejaVu lo trae — en un equipo sin cobertura
 * el botón salía como un cuadrito.
 *
 * `marvin_icons.ttf` son 3 KB con exactamente estos ocho glifos (ver
 * `scripts/build-icon-font.py`).
 */
object Iconos {
    const val VISOR = "🖥"
    const val DOCS = "📄"
    const val CLAVE = "🔑"
    const val MICROFONO = "🎤"
    const val SELECCION = "⧉"
    const val SHIFT = "⇧"
    const val REENGANCHAR = "⟳"
    const val CERRAR = "✕"

    private var fuente: Typeface? = null

    private fun fuente(ctx: Context): Typeface? {
        if (fuente == null) {
            fuente = runCatching { ctx.resources.getFont(R.font.marvin_icons) }.getOrNull()
        }
        return fuente
    }

    /**
     * Aplica la fuente de íconos SÓLO a los caracteres que son íconos, dejando el resto del
     * texto en la tipografía que tenga la vista. Es lo que permite que "🎤 Dictar" salga con
     * el ícono nuestro y la palabra en mononoki.
     */
    fun conIconos(ctx: Context, texto: String): CharSequence {
        val tf = fuente(ctx) ?: return texto
        val out = SpannableString(texto)
        var i = 0
        while (i < texto.length) {
            val cp = texto.codePointAt(i)
            val ancho = Character.charCount(cp)
            if (esIcono(cp)) {
                out.setSpan(FuenteSpan(tf), i, i + ancho, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            i += ancho
        }
        return out
    }

    private fun esIcono(cp: Int): Boolean = when (cp) {
        0x1F5A5, 0x1F4C4, 0x1F511, 0x1F3A4, 0x29C9, 0x21E7, 0x27F3, 0x2715 -> true
        else -> false
    }

    /**
     * Span que cambia la tipografía de un tramo.
     *
     * No se usa `TypefaceSpan(Typeface)` porque ese constructor recién existe desde API 28 y
     * la app soporta desde 26.
     */
    private class FuenteSpan(private val tf: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(paint: TextPaint) = aplicar(paint)
        override fun updateMeasureState(paint: TextPaint) = aplicar(paint)
        private fun aplicar(paint: Paint) {
            paint.typeface = tf
        }
    }
}
