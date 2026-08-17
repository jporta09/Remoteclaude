package com.remoteclaude.app

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * Demo de primer uso: burbujas que resaltan un lugar de la pantalla y lo explican, una
 * secuencia corta por pantalla, disparada la primera vez que el usuario entra a cada una.
 *
 * El overlay bloquea y narra (tocar avanza, "Saltar demo" corta): dejar pasar el toque al
 * elemento real dispararía acciones a mitad de demo, y la barra de pestañas se
 * reconstruye entera en cada cambio, así que los blancos son lambdas que se re-evalúan
 * al mostrar cada paso — nunca referencias guardadas.
 *
 * La marca de "visto" se persiste al CERRAR (terminar o saltar), no al arrancar: si la
 * pantalla rota y se recrea a mitad de demo, la demo corta rearranca desde el principio.
 */
object Tour {
    private const val PREFS = "remotemarvin"

    /** Kill-switch global: lo siembran los E2E para que la demo no interfiera. */
    private const val APAGADO = "tour_off"

    class Paso(
        val titulo: String,
        val texto: String,
        /** Se re-evalúa al mostrar el paso; null o vista no visible → el paso se salta. */
        val blanco: (() -> View?)? = null,
        /** Precondición barata antes de medir (ej: scrollear la barra hasta el botón). */
        val preparar: (() -> Unit)? = null,
    )

    fun visto(ctx: Context, id: String): Boolean =
        prefs(ctx).getBoolean("tour_$id", false)

    fun pendiente(ctx: Context, id: String): Boolean =
        !prefs(ctx).getBoolean(APAGADO, false) && !visto(ctx, id)

    /**
     * Muestra la demo si está pendiente. Devuelve si la lanzó. El overlay se agrega con
     * addContentView (cubre todo el content frame, keypad incluido, sin tocar el root
     * LinearLayout de cada pantalla).
     */
    fun lanzar(act: Activity, id: String, pasos: List<Paso>, alTerminar: (() -> Unit)? = null): Boolean {
        if (!pendiente(act, id)) return false
        val content = act.findViewById<ViewGroup>(android.R.id.content)
        for (i in 0 until content.childCount) {
            if (content.getChildAt(i) is TourOverlay) return false   // ya hay una en curso
        }
        val overlay = TourOverlay(act, pasos) {
            prefs(act).edit().putBoolean("tour_$id", true).apply()
            alTerminar?.invoke()
        }
        act.addContentView(overlay, ViewGroup.LayoutParams(Paleta.MATCH, Paleta.MATCH))
        return true
    }

    /** Borra todas las marcas de visto (el replay de long-press en "_hosts"). */
    fun reiniciar(ctx: Context) {
        val p = prefs(ctx)
        val e = p.edit()
        p.all.keys.filter { it.startsWith("tour_") && it != APAGADO }.forEach { e.remove(it) }
        e.apply()
    }

    // --- Lógica pura, testeable en JVM -------------------------------------------------

    /** Índice del primer paso mostrable desde [desde], o -1 si no queda ninguno. */
    fun proximoPaso(disponibles: List<Boolean>, desde: Int): Int {
        for (i in desde until disponibles.size) if (disponibles[i]) return i
        return -1
    }

    /**
     * Y de la burbuja: debajo del blanco si entra, si no arriba, y si tampoco (blanco
     * gigante, ej. la terminal entera) clampeada dentro de la pantalla.
     */
    fun posicionBurbuja(
        blancoTop: Int, blancoBottom: Int, altoOverlay: Int, altoBurbuja: Int, margen: Int,
    ): Int {
        val debajo = blancoBottom + margen
        if (debajo + altoBurbuja <= altoOverlay - margen) return debajo
        val arriba = blancoTop - margen - altoBurbuja
        if (arriba >= margen) return arriba
        return (altoOverlay - altoBurbuja).coerceAtLeast(margen) / 2
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
