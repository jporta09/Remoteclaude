package com.remoteclaude.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Observabilidad liviana (F8): un ring buffer EN MEMORIA de hitos de conexión —
 * conectó / reconectó / auth-fail / clave del host / caídas—. Los emite [SshTerminalSession]
 * (y quien quiera) y los lee la pantalla de diagnóstico.
 *
 * No es un logcat ni se persiste: sólo hitos de red/identidad, acotado a [MAX] para no crecer
 * sin control, y nada sensible (ni claves ni contenido de la terminal). Se pierde al cerrar la
 * app — es para mirar "qué pasó recién con la conexión", no una auditoría.
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
    private val eventos = ArrayDeque<Evento>()
    private val oyentes = CopyOnWriteArrayList<() -> Unit>()

    /** Agrega un evento (thread-safe: lo llaman hilos de red). Descarta el más viejo pasado [MAX]. */
    fun registrar(nivel: Nivel, categoria: String, detalle: String) {
        synchronized(eventos) {
            eventos.addLast(Evento(System.currentTimeMillis(), nivel, categoria, detalle))
            while (eventos.size > MAX) eventos.removeFirst()
        }
        oyentes.forEach { runCatching { it.invoke() } }
    }

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
