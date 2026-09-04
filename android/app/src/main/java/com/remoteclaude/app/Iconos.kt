package com.remoteclaude.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ImageSpan
import android.util.TypedValue
import androidx.core.graphics.withSave

/**
 * Los íconos de la interfaz, como `VectorDrawable` propios.
 *
 * Historia: primero eran emoji (se veían distintos según el fabricante, y ⧉ ni existía en
 * algunos equipos); después una fuente propia de 8 glifos (`marvin_icons.ttf`), que unificó
 * pero seguía siendo tipografía ajena. Ahora son vectores dibujados con la gramática del
 * manual de marca: grilla de 24, trazo uniforme de 2, sólo rectas y diagonales, las
 * esquinas cortadas de la cápsula del isotipo, y las firmas de la línea `[▲\\▼]` — el ▲
 * macizo (shift, punta del reenganchar) y la doble barra (visor, docs, micrófono).
 *
 * Control total del trazo, nítidos a cualquier tamaño y tintables con la paleta.
 */
object Iconos {
    val VISOR = R.drawable.ic_marvin_visor
    val DOCS = R.drawable.ic_marvin_docs
    val CLAVE = R.drawable.ic_marvin_clave
    val MICROFONO = R.drawable.ic_marvin_microfono
    val SELECCION = R.drawable.ic_marvin_seleccion
    val SHIFT = R.drawable.ic_marvin_shift
    val ENTER = R.drawable.ic_marvin_enter
    val REENGANCHAR = R.drawable.ic_marvin_reenganchar
    val CERRAR = R.drawable.ic_marvin_cerrar
    // 5ª pasada (DG-5): los "íconos" que eran glifos de texto/emoji (↺ ↻ ⟳ ⓘ 🔒 ⧉ 🖥 🔍 ⛶ 📄 📎 📕 🖼)
    // los dibujaba el fallback del sistema (ninguna fuente bundleada los tiene) — distintos en cada
    // equipo y con el color del emoji. Ahora son vectores con la misma gramática.
    val REESCANEAR = R.drawable.ic_marvin_reescanear
    val RECARGAR = R.drawable.ic_marvin_recargar
    val INFO = R.drawable.ic_marvin_info
    val CANDADO = R.drawable.ic_marvin_candado
    val COPIAR = R.drawable.ic_marvin_copiar
    val PANTALLA = R.drawable.ic_marvin_pantalla
    val ZOOM = R.drawable.ic_marvin_zoom
    val AJUSTAR = R.drawable.ic_marvin_ajustar
    val DOC = R.drawable.ic_marvin_doc
    val ADJUNTO = R.drawable.ic_marvin_adjunto
    val PDF = R.drawable.ic_marvin_pdf
    val IMAGEN = R.drawable.ic_marvin_imagen

    /**
     * El drawable medido, listo para usar. Con `color` se tinta MONOCROMO (pisa el acento:
     * es el estado activo/grabando, como la variante monocroma del manual); con null se ve
     * a dos tonos, con el acento de marca horneado en el XML.
     */
    fun drawable(ctx: Context, id: Int, color: Int?, tamanoDp: Float): Drawable {
        val d = requireNotNull(ctx.getDrawable(id)).mutate()
        color?.let { d.setTint(it) }
        val px = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, tamanoDp, ctx.resources.displayMetrics,
        ).toInt()
        d.setBounds(0, 0, px, px)
        return d
    }

    /**
     * "Ícono + palabra" como UN solo texto (el patrón "🎤 Dictar" de siempre): el vector va
     * embebido como ImageSpan, así el conjunto se centra junto en el botón. Con compound
     * drawables no servía: en un botón ancho el ícono quedaba pegado al borde izquierdo y
     * el texto centrado, cada uno por su lado.
     */
    fun etiqueta(ctx: Context, id: Int, color: Int?, tamanoDp: Float, texto: String): CharSequence {
        val s = SpannableString("\uFFFC $texto")
        s.setSpan(
            SpanCentrado(drawable(ctx, id, color, tamanoDp)),
            0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return s
    }

    /**
     * ImageSpan alineado al CENTRO vertical del renglón. El ALIGN_CENTER de la plataforma
     * recién existe en API 29 y la app soporta 26; con ALIGN_BASELINE el ícono quedaba
     * sentado sobre la línea base, flotando alto respecto del texto.
     */
    private class SpanCentrado(d: Drawable) : ImageSpan(d) {
        override fun draw(
            canvas: Canvas, text: CharSequence?, start: Int, end: Int,
            x: Float, top: Int, y: Int, bottom: Int, paint: Paint,
        ) {
            val alto = bottom - top
            val dy = top + (alto - drawable.bounds.height()) / 2f
            canvas.withSave {
                translate(x, dy)
                drawable.draw(this)
            }
        }
    }
}
