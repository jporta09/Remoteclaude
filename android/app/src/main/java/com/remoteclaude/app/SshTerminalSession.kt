package com.remoteclaude.app

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.trilead.ssh2.Connection
import com.trilead.ssh2.Session
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * M5: sesión SSH con tmux + auto-reconexión (sobrevive al bloqueo / cortes de red).
 *
 * Abre SSH al gateway → shell del host (vía nsenter) → `tmux new -A -D -s <name>`, que
 * persiste del lado del server. Si la conexión cae (bloqueo, cambio de red), el loop
 * reconecta solo y vuelve a engancharse al mismo tmux (todo intacto). El
 * ConnectivityManager (desde la Activity) avisa caída/vuelta de red para reaccionar rápido.
 */
class SshTerminalSession(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val privateKeyPem: CharArray,
    private val tmuxSession: String,
    client: TerminalSessionClient,
) : TerminalSession(5000, client) {

    @Volatile private var userClosed = false
    @Volatile private var cols = 80
    @Volatile private var rows = 24

    @Volatile private var conn: Connection? = null
    @Volatile private var sshSession: Session? = null
    @Volatile private var stdin: OutputStream? = null

    private val reconnectLock = Object()
    @Volatile private var waitingToReconnect = false

    override fun onEmulatorInitialized(
        columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int
    ) {
        cols = columns
        this.rows = rows
        thread(name = "ssh-loop") { connectLoop() }
    }

    private fun connectLoop() {
        var attempt = 0
        while (!userClosed) {
            status(if (attempt == 0) "Conectando a $user@$host:$port ...\r\n" else "\r\n[reconectando…]\r\n")
            try {
                val c = Connection(host, port)
                c.connect({ _, _, _, _ -> true }, 15000, 15000)
                conn = c
                if (!c.authenticateWithPublicKey(user, privateKeyPem, null)) {
                    status("\r\n[autenticación SSH falló]\r\n")
                    userClosed = true
                    break
                }
                val s = c.openSession()
                s.requestPTY("xterm-256color", cols, rows, 0, 0, null)
                // tmux corre en el contenedor (default-command = host-shell, que nsenter
                // al host). -u fuerza UTF-8 (sshd no pasa LANG, si no tmux manda los
                // acentos como '_'). Crea o reengancha la sesión persistente (-D desengancha).
                s.execCommand("tmux -u new -A -D -s $tmuxSession")
                sshSession = s
                stdin = s.stdin
                attempt = 0

                val out = s.stdout
                val buf = ByteArray(8192)
                while (true) {
                    val n = out.read(buf)
                    if (n == -1) break
                    onTransportInput(buf, 0, n)
                }
            } catch (_: Exception) {
                // cayó la conexión; se reintenta abajo
            } finally {
                closeCurrent()
            }

            if (userClosed) break
            attempt++
            val waitMs = minOf(1000L * attempt, 8000L)
            synchronized(reconnectLock) {
                waitingToReconnect = true
                try {
                    reconnectLock.wait(waitMs)
                } catch (_: InterruptedException) {
                }
                waitingToReconnect = false
            }
        }
        onTransportClosed(null)
    }

    /** La red volvió: despertar el backoff para reconectar ya. */
    fun onNetworkAvailable() {
        synchronized(reconnectLock) {
            if (waitingToReconnect) reconnectLock.notifyAll()
        }
    }

    /** La red se cayó: cerrar el socket muerto para que el read se desbloquee y entremos al backoff. */
    fun onNetworkLost() {
        closeCurrent()
    }

    private fun closeCurrent() {
        try { sshSession?.close() } catch (_: Exception) {}
        try { conn?.close() } catch (_: Exception) {}
        sshSession = null
        conn = null
        stdin = null
    }

    override fun writeToTransport(data: ByteArray, offset: Int, count: Int) {
        val s = stdin ?: return
        s.write(data, offset, count)
        s.flush()
    }

    override fun onSizeChanged(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        cols = columns
        this.rows = rows
        try {
            sshSession?.resizePTY(columns, rows, 0, 0)
        } catch (_: Exception) {
        }
    }

    override fun closeTransport() {
        userClosed = true
        onNetworkAvailable() // romper el backoff si está esperando
        closeCurrent()
    }

    private fun status(message: String) {
        val b = message.toByteArray(Charsets.UTF_8)
        onTransportInput(b, 0, b.size)
    }
}
