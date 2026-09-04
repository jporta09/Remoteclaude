package com.remoteclaude.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.ViewGroup

/**
 * Paleta CTR de Marvin y helpers de medida.
 *
 * Vivían en el companion de MainActivity, así que cualquier pedazo que se sacara de ahí se
 * llevaba una copia de los colores y empezaban a divergir.
 */
object Paleta {
    // Los hex son `const` para que PaletaTest los compare con res/values/colors.xml en la JVM
    // (DG-4, 5ª pasada: había tres copias de la paleta y CHEV_FG/MUTED habían quedado en el
    // valor viejo #5E8B7E mientras colors.xml subía a #7FA99B). ÚNICA fuente en código: esta.
    const val ACCENT_HEX = "#71BF44"     // = marvin_green  (verde CTR)
    const val KEY_FG_HEX = "#F2F2F2"     // = marvin_fg     (gris claro)
    const val PETROL_HEX = "#0F232D"     // = marvin_petrol (fondo)
    const val MUTED_HEX = "#7FA99B"      // = marvin_muted  (verde alt apagado)
    const val AMBER_HEX = "#FDB940"      // = marvin_amber
    const val RED_HEX = "#E05555"        // = marvin_red
    const val SURFACE_HEX = "#16323D"    // = marvin_surface (panel)
    const val CHEV_BG_HEX = "#0A1A20"    // petróleo más oscuro (sólo en código: barra/teclado)
    const val BUBBLE_FG_HEX = "#A9CCE8"  // texto de la burbuja del dictado (sólo en código)

    val ACCENT: Int = Color.parseColor(ACCENT_HEX)
    val KEY_FG: Int = Color.parseColor(KEY_FG_HEX)
    val KEYPAD_BG: Int = Color.parseColor(PETROL_HEX)
    val CHEV_FG: Int = Color.parseColor(MUTED_HEX)
    val CHEV_BG: Int = Color.parseColor(CHEV_BG_HEX)
    val TAB_ACTIVE_BG: Int = Color.parseColor(SURFACE_HEX)
    val REC_FG: Int = Color.parseColor(RED_HEX)
    val AMBER: Int = Color.parseColor(AMBER_HEX)
    val BUBBLE_FG: Int = Color.parseColor(BUBBLE_FG_HEX)

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
}

/**
 * Tipografía de "detalles y comentarios" del manual de marca: texto complementario en
 * recuadros, epígrafes y estados, "para facilitar la lectura y/o desambiguar".
 *
 * El manual especifica Brandon Grotesque, que es comercial y no se puede embeber en el APK.
 * Jost es del mismo linaje (revival de Futura/Erbar, como Brandon) y sus proporciones
 * coinciden: x-height/cap 0.657 contra 0.660.
 */
private var fuenteDetalleCache: Typeface? = null

fun Context.fuenteDetalle(): Typeface? {
    if (fuenteDetalleCache == null) {
        fuenteDetalleCache = runCatching { resources.getFont(R.font.jost) }.getOrNull()
    }
    return fuenteDetalleCache
}

fun Context.dp(v: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun Context.sp(v: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics).toInt()
