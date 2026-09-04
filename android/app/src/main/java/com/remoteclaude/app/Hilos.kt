package com.remoteclaude.app

import android.os.Looper
import kotlin.concurrent.thread

/**
 * Cierres de red FUERA del hilo principal (5ª pasada, QA5-1 / SRE-5-4).
 *
 * `Connection.close()` de trilead es `synchronized` y hace I/O: si otro hilo tiene el lock
 * colgado en un connect() contra un endpoint muerto (el forward de un nodo vencido), el main
 * se queda esperando ese lock y a los 5 s salta el ANR. Pasaba en onPause (presencia del
 * dictado), onDestroy, cerrar pestañas, reconectar y al perder la red. El patrón seguro ya
 * existía en forzarReconexion (hilo aparte): acá se generaliza.
 */
object Hilos {
    fun enMain(): Boolean = Looper.myLooper() != null && Looper.myLooper() == Looper.getMainLooper()

    /** Corre `bloque` en un hilo daemon si estamos en el main; inline si ya estamos en un hilo. */
    fun cerrarEnFondo(nombre: String, bloque: () -> Unit) {
        if (enMain()) thread(name = nombre, isDaemon = true) { runCatching(bloque) }
        else runCatching(bloque)
    }
}
