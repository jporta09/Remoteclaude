package com.remoteclaude.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Observabilidad liviana (F8): un ring buffer EN MEMORIA de hitos de conexión —
 * conectó / reconectó / auth-fail / clave del host / caídas—. Los emite [SshTerminalSession]
 * (y quien quiera) y los lee la pantalla de diagnóstico.
 *
 * En memoria: sólo hitos de red/identidad, acotado a [MAX], y nada sensible (ni claves ni
 * contenido de la terminal). Además, los hitos que NO son INFO (avisos/errores) se **persisten**
 * a `filesDir` para que sobrevivan a la muerte del proceso (crash/OOM/reboot) y den un post-mortem
 * al reabrir — igual que [VigiaUi] con los cuelgues de UI. Llamar [init] y [cargarPersistidos] al
 * arranque (ver `MainActivity`).
 */
object Diagnostico {

    enum class Nivel { INFO, AVISO, ERROR }

    data class Evento(
        val epochMs: Long,
        val nivel: Nivel,
        val categoria: String,
        val detalle: String,
    )

    private const val MAX = 200
    private const val ARCHIVO = "eventos-conexion.log"
    private const val MAX_ARCHIVO = 64 * 1024L
    private val eventos = ArrayDeque<Evento>()
    private val oyentes = CopyOnWriteArrayList<() -> Unit>()

    @Volatile private var archivo: File? = null

    /** Habilita la persistencia a `filesDir`. Llamar una vez al arranque, DESPUÉS de
     *  [cargarPersistidos] (para no re-persistir lo recuperado). */
    fun init(ctx: Context) {
        archivo = File(ctx.applicationContext.filesDir, ARCHIVO)
    }

    /** Agrega un evento (thread-safe: lo llaman hilos de red). Descarta el más viejo pasado [MAX]. */
    fun registrar(nivel: Nivel, categoria: String, detalle: String) {
        synchronized(eventos) {
            eventos.addLast(Evento(System.currentTimeMillis(), nivel, categoria, detalle))
            while (eventos.size > MAX) eventos.removeFirst()
        }
        // Persistir sólo lo que importa para un post-mortem (avisos/errores), no el ruido INFO.
        if (nivel != Nivel.INFO) persistir(categoria, detalle)
        oyentes.forEach { runCatching { it.invoke() } }
    }

    private fun persistir(categoria: String, detalle: String) {
        val f = archivo ?: return
        try {
            if (f.length() > MAX_ARCHIVO) f.writeText("")   // tope: quedarse con lo nuevo
            f.appendText("=== ${System.currentTimeMillis()} ===\n[$categoria] $detalle\n")
        } catch (_: Exception) {
        }
    }

    /** Re-carga al ring buffer los eventos de conexión persistidos de una corrida anterior (si el
     *  proceso murió) y borra el archivo. Se agregan directo (sin re-persistir). Llamar al arranque
     *  ANTES de [init]. */
    fun cargarPersistidos(ctx: Context) =
        cargarPersistidosDe(File(ctx.applicationContext.filesDir, ARCHIVO))

    // --- núcleo testeable (toma el File directo, sin Context) ---------------------------------

    internal fun cargarPersistidosDe(f: File) {
        try {
            if (!f.exists()) return
            val contenido = f.readText()
            f.delete()
            if (contenido.isBlank()) return
            synchronized(eventos) {
                eventos.addLast(
                    Evento(
                        System.currentTimeMillis(), Nivel.AVISO, "conexión (previo)",
                        "Eventos de conexión registrados antes de este arranque:\n$contenido",
                    ),
                )
                while (eventos.size > MAX) eventos.removeFirst()
            }
            oyentes.forEach { runCatching { it.invoke() } }
        } catch (_: Exception) {
        }
    }

    /** Sólo para tests: fija (o apaga con null) el archivo de persistencia sin un Context. */
    internal fun archivoParaTest(f: File?) { archivo = f }

    fun instantanea(): List<Evento> = synchronized(eventos) { eventos.toList() }

    fun limpiar() {
        synchronized(eventos) { eventos.clear() }
        oyentes.forEach { runCatching { it.invoke() } }
    }

    fun escuchar(cb: () -> Unit) { oyentes.add(cb) }
    fun dejarDeEscuchar(cb: () -> Unit) { oyentes.remove(cb) }

    /** Vuelca los eventos como texto plano, para compartir o pegar en un reporte. */
    fun exportarTexto(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val snap = instantanea()
        if (snap.isEmpty()) return "RemoteMarvin — diagnóstico\n(sin eventos)\n"
        val sb = StringBuilder("RemoteMarvin — diagnóstico (${snap.size} eventos)\n\n")
        for (e in snap) {
            sb.append(fmt.format(Date(e.epochMs)))
                .append("  [").append(e.nivel.name).append("] ")
                .append(e.categoria).append(": ").append(e.detalle).append('\n')
        }
        return sb.toString()
    }
}
