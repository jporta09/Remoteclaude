package com.remoteclaude.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Vigía del hilo de UI (v1.15.0) — para cazar el freeze intermitente "al volver, en Claude" (#1)
 * que NO se puede reproducir a mano.
 *
 * Un hilo de fondo le toma el pulso al hilo principal cada [INTERVALO] posando un runnable trivial;
 * si el main no lo corre en [UMBRAL] (está colgado), captura su **stack** y lo deja en tres lados:
 * el Diagnóstico (F8, que ya se puede Compartir), un archivo en `filesDir` (sobrevive al force-close)
 * y logcat. Así, cuando se cuelgue en uso real, queda grabado DÓNDE — sin adb, sin reproducirlo.
 *
 * Sólo vigila en PRIMER PLANO: con la app atrás el main queda idle/congelado por Android y eso no es
 * un cuelgue real. [reanudar] (onResume) y [pausar] (onPause) lo prenden y apagan.
 */
class VigiaUi(private val ctx: Context) {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var ultimoLatido = SystemClock.uptimeMillis()
    @Volatile private var activo = false
    @Volatile private var reportado = false
    private var hilo: Thread? = null

    // Corre en el main: marca que sigue vivo. Si el main está colgado, no llega a correr.
    private val latir = Runnable { ultimoLatido = SystemClock.uptimeMillis() }

    fun reanudar() {
        ultimoLatido = SystemClock.uptimeMillis()
        reportado = false
        activo = true
        if (hilo == null) {
            hilo = Thread({ bucle() }, "vigia-ui").apply { isDaemon = true; start() }
        }
    }

    fun pausar() { activo = false }

    fun detener() {
        activo = false
        hilo?.interrupt()
        hilo = null
    }

    private fun bucle() {
        while (true) {
            try {
                Thread.sleep(INTERVALO)
            } catch (_: InterruptedException) {
                return
            }
            if (!activo) {
                ultimoLatido = SystemClock.uptimeMillis()  // en background no se cuenta como cuelgue
                reportado = false
                continue
            }
            handler.post(latir)
            val atraso = SystemClock.uptimeMillis() - ultimoLatido
            if (atraso >= UMBRAL) {
                if (!reportado && activo) {
                    reportar(atraso)
                    reportado = true
                }
            } else {
                reportado = false  // el main respondió: rearmar para el próximo episodio
            }
        }
    }

    private fun reportar(atrasoMs: Long) {
        val stack = try {
            Looper.getMainLooper().thread.stackTrace
        } catch (_: Exception) {
            emptyArray()
        }
        val txt = buildString {
            append("UI colgada ~").append(atrasoMs).append(" ms — stack del hilo principal:\n")
            for (f in stack.take(MAX_FRAMES)) append("  at ").append(f).append('\n')
        }
        Log.w("VigiaUi", txt)
        Diagnostico.registrar(Diagnostico.Nivel.ERROR, "cuelgue-ui", txt)
        persistir(txt)
    }

    /** Guarda el cuelgue en disco para que sobreviva a un force-close (el ring buffer es en memoria). */
    private fun persistir(txt: String) {
        try {
            val f = File(ctx.filesDir, ARCHIVO)
            if (f.length() > MAX_ARCHIVO) f.writeText("")  // tope: quedarse con lo nuevo
            f.appendText("=== ${System.currentTimeMillis()} ===\n$txt\n")
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val INTERVALO = 1000L     // pulso cada 1 s
        private const val UMBRAL = 4000L        // colgado ≥ 4 s = cuelgue (por debajo del ANR de 5 s)
        private const val MAX_FRAMES = 45
        private const val MAX_ARCHIVO = 64 * 1024L
        private const val ARCHIVO = "cuelgues-ui.log"

        /** Al abrir la app: subir al Diagnóstico los cuelgues persistidos (de una corrida anterior en
         *  la que la app se cerró/mató en el freeze) y borrar el archivo. Así se ven y se comparten. */
        fun cargarPersistidos(ctx: Context) {
            try {
                val f = File(ctx.filesDir, ARCHIVO)
                if (!f.exists()) return
                val contenido = f.readText()
                if (contenido.isNotBlank()) {
                    Diagnostico.registrar(
                        Diagnostico.Nivel.ERROR, "cuelgue-ui (previo)",
                        "Cuelgue(s) de UI registrados antes de este arranque:\n$contenido",
                    )
                }
                f.delete()
            } catch (_: Exception) {
            }
        }
    }
}
