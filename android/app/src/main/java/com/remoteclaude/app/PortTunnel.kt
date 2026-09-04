package com.remoteclaude.app

import android.content.Context
import com.trilead.ssh2.Connection
import com.trilead.ssh2.LocalPortForwarder
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.KeyPair

/**
 * Túnel SSH a un puerto que el host escucha SOLO en su loopback.
 *
 * Permite que servicios del host (noVNC, WhisperLiveKit) no tengan que publicarse en
 * ninguna interfaz de red: viajan dentro de la conexión SSH que ya usamos, autenticada
 * con la clave del Keystore.
 *
 * El extremo local se bindea explícitamente a 127.0.0.1 del TELÉFONO: el overload que
 * recibe sólo el puerto hace `new ServerSocket(port)`, que escucha en 0.0.0.0 y dejaría
 * el túnel accesible a cualquiera en la red del celular.
 */
class PortTunnel(
    ctx: Context,
    private val host: String,
    private val sshPort: Int,
    private val user: String,
    private val key: KeyPair,
) {
    private val appCtx = ctx.applicationContext
    private var conn: Connection? = null
    private var fwd: LocalPortForwarder? = null

    /** Puerto local ya escuchando, o null si no se pudo tender. BLOQUEA. */
    fun open(remotePort: Int): Int? = try {
        val (h, p) = TailscaleBridge.endpoint(host, sshPort)
        val c = Connection(h, p).also { conn = it }
        // 5 s (antes 8): es lo que retiene el lock de la Connection mientras un close() espera.
        c.connect(HostKeys.verifier(appCtx, host, sshPort), 5000, 5000)
        if (!c.authenticateWithPublicKey(user, key)) {
            close(); null
        } else {
            // sshlib no expone el puerto que asignaría con 0, así que se reserva uno libre
            // (misma carrera benigna de siempre: si lo toman en el medio, open() devuelve
            // null y el caller cae al camino directo).
            //
            // IPv4 EXPLÍCITO: getLoopbackAddress() devuelve ::1 cuando hay IPv6, y entonces
            // el túnel escucha en [::1] mientras la WebView pide 127.0.0.1 -> refused.
            val loopback = InetAddress.getByName("127.0.0.1")
            val local = ServerSocket(0, 1, loopback).use { it.localPort }
            val bind = InetSocketAddress(loopback, local)
            c.createLocalPortForwarder(bind, "127.0.0.1", remotePort).also { fwd = it }
            local
        }
    } catch (_: Exception) {
        close(); null
    }

    fun close() {
        try { fwd?.close() } catch (_: Exception) {}
        try { conn?.close() } catch (_: Exception) {}
        fwd = null; conn = null
    }
}
