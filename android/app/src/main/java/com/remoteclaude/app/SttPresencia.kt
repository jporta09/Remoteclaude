package com.remoteclaude.app

import android.content.Context
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPair
import kotlin.concurrent.thread

/**
 * Mantiene DESPIERTO al server de dictado en vivo mientras la app está al frente.
 *
 * El truco: el idle-exit del server (marvin-stt-live.py) cuenta una conexión ESTABLISHED
 * contra su puerto como "en uso" — y uvicorn no cierra conexiones que no mandan nada (su
 * timer de keep-alive recién se arma después de responder un request, verificado en el
 * h11_impl instalado). Entonces la "presencia" es literal: un socket ocioso, tendido por
 * el mismo túnel SSH que usa el visor ([PortTunnel]), sin protocolo ni escrituras.
 *
 * Sin estado en el host: si la app muere o pierde red, la conexión cae sola y el server
 * se libera a los 10 min, como siempre. Si la conexión se corta (Doze, cambio de red),
 * acá se reintenta con una pausa fija — no hace falta backoff agresivo: perder la
 * presencia un rato sólo arriesga el idle-exit, que es de 10 minutos.
 */
class SttPresencia(
    ctx: Context,
    private val host: String,
    private val sshPort: Int,
    private val user: String,
    private val key: KeyPair,
) {
    private val appCtx = ctx.applicationContext

    @Volatile private var activa = false
    private var hilo: Thread? = null
    // Un read() bloqueado en socket NO se despierta con Thread.interrupt(): para frenar el
    // hilo hay que CERRARLE el socket (el read salta con excepción). Por eso viven acá.
    @Volatile private var socket: Socket? = null
    @Volatile private var tunel: PortTunnel? = null

    /** Idempotente: si ya hay presencia, no hace nada. */
    @Synchronized fun abrir() {
        if (activa) return
        activa = true
        hilo = thread(name = "stt-presencia", isDaemon = true) { bucle() }
    }

    @Synchronized fun cerrar() {
        activa = false
        try { socket?.close() } catch (_: Exception) {}
        try { tunel?.close() } catch (_: Exception) {}
        hilo?.interrupt()   // por si estaba en el sleep del reintento
        hilo = null
    }

    private fun bucle() {
        while (activa) {
            val t = PortTunnel(appCtx, host, sshPort, user, key).also { tunel = it }
            try {
                val local = t.open(PUERTO_LIVE)
                if (local != null && activa) {
                    Socket().use { s ->
                        socket = s
                        s.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), local), 4_000)
                        Diagnostico.registrar(Diagnostico.Nivel.INFO, "dictado", "presencia STT abierta")
                        // Ocioso a propósito: el read sólo vuelve si alguien cierra (el
                        // server, el túnel, o cerrar()) — o sea, cuando la presencia se
                        // perdió y hay que re-tenderla.
                        while (activa && s.getInputStream().read() >= 0) { /* el server no manda nada */ }
                    }
                }
            } catch (_: Exception) {
                // caída de red/túnel o cierre pedido: se decide abajo
            } finally {
                socket = null
                t.close(); tunel = null
            }
            if (!activa) break
            try { Thread.sleep(REINTENTO_MS) } catch (_: InterruptedException) { break }
        }
    }

    private companion object {
        const val PUERTO_LIVE = 6092
        const val REINTENTO_MS = 30_000L
    }
}
