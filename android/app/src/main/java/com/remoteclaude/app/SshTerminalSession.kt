package com.remoteclaude.app

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.trilead.ssh2.Connection
import com.trilead.ssh2.Session
import java.io.OutputStream
import java.security.KeyPair
import java.util.concurrent.Executors
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
    private val keyPair: KeyPair,
    @Volatile var tmuxSession: String,   // mutable: renombrar lo actualiza (lo lee el reconnect)
    client: TerminalSessionClient,
    private val onAuthFailed: (() -> Unit)? = null,   // clave no autorizada -> avisar a la UI
) : TerminalSession(5000, client) {

    @Volatile private var userClosed = false
    @Volatile private var cols = 80
    @Volatile private var rows = 24

    @Volatile private var conn: Connection? = null
    @Volatile private var sshSession: Session? = null
    @Volatile private var stdin: OutputStream? = null

    private val reconnectLock = Object()
    @Volatile private var waitingToReconnect = false

    // Toda I/O de red va fuera del hilo principal (si no, un socket muerto cuelga la UI = ANR).
    private val ioExecutor = Executors.newSingleThreadExecutor()

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
                // Si el Tailscale embebido está activo, se conecta al forward local
                // (127.0.0.1:xxxx -> host:port por la tailnet); si no, directo.
                val (h, p) = TailscaleBridge.endpoint(host, port)
                val c = Connection(h, p)
                c.connect({ _, _, _, _ -> true }, 15000, 15000)
                conn = c
                if (!c.authenticateWithPublicKey(user, keyPair)) {
                    status("\r\n[autenticación SSH falló — la clave no está autorizada]\r\n")
                    userClosed = true
                    onAuthFailed?.invoke()
                    break
                }
                val s = c.openSession()
                s.requestPTY("xterm-256color", cols, rows, 0, 0, null)
                // tmux corre en el contenedor (default-command = host-shell, que nsenter
                // al host). -u fuerza UTF-8 (sshd no pasa LANG, si no tmux manda los
                // acentos como '_'). Crea o reengancha la sesión persistente (-D desengancha).
                s.execCommand("tmux -u new -A -D -s '$tmuxSession'")
                sshSession = s
                stdin = s.stdin
                attempt = 0

                // Keepalive: tráfico periódico para que el NAT/firewall (sobre todo en redes
                // corporativas) no descarte el mapeo y la conexión no quede "half-open". Si el
                // ping falla, la conexión está muerta -> cerrarla desbloquea el read y reconecta.
                // Atado a 'c': cuando reconectamos (conn cambia) o cerramos, este hilo termina.
                val thisConn = c
                thread(name = "ssh-keepalive", isDaemon = true) {
                    try {
                        while (!userClosed && conn === thisConn) {
                            Thread.sleep(10000)
                            if (conn === thisConn) thisConn.sendIgnorePacket()
                        }
                    } catch (_: Exception) {
                        if (conn === thisConn) closeCurrent()
                    }
                }

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
        // Corre en el hilo escritor del base. Si la conexión está caída, NO propagamos
        // la excepción: así el hilo escritor sigue vivo y drena la cola (evita que el
        // write() del main thread se bloquee), y al reconectar usa el stdin nuevo.
        val s = stdin ?: return
        try {
            s.write(data, offset, count)
            s.flush()
        } catch (_: Exception) {
            // descartado; tmux redibuja al reconectar
        }
    }

    override fun onSizeChanged(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        cols = columns
        this.rows = rows
        // resizePTY hace I/O de red -> NUNCA en el hilo principal (lo llama el layout).
        val s = sshSession ?: return
        ioExecutor.execute {
            try {
                s.resizePTY(columns, rows, 0, 0)
            } catch (_: Exception) {
            }
        }
    }

    override fun closeTransport() {
        userClosed = true
        onNetworkAvailable() // romper el backoff si está esperando
        closeCurrent()
        ioExecutor.shutdownNow()
    }

    private fun status(message: String) {
        val b = message.toByteArray(Charsets.UTF_8)
        onTransportInput(b, 0, b.size)
    }
}
