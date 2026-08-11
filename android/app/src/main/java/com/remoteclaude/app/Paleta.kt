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
    val ACCENT: Int = Color.parseColor("#71BF44")         // verde CTR
    val KEY_FG: Int = Color.parseColor("#F2F2F2")         // gris claro
    val KEYPAD_BG: Int = Color.parseColor("#0F232D")      // verde petróleo
    val CHEV_FG: Int = Color.parseColor("#5E8B7E")        // verde alt apagado
    val CHEV_BG: Int = Color.parseColor("#0A1A20")        // petróleo más oscuro
    val TAB_ACTIVE_BG: Int = Color.parseColor("#16323D")  // panel
    val REC_FG: Int = 0xFFE05555.toInt()                  // rojo de "grabando"
    val BUBBLE_FG: Int = Color.parseColor("#A9CCE8")      // texto de la burbuja del dictado

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
