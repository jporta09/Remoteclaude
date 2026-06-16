package com.remoteclaude.app

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.trilead.ssh2.Connection
import com.trilead.ssh2.Session
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * M2: sesión alimentada por SSH (connectbot sshlib). Abre SSH al gateway, que hace
 * nsenter al host → caés en la shell real del host. El stream del canal SSH se
 * conecta al emulador de Termux (onTransportInput ↔ writeToTransport).
 *
 * Reemplaza a EchoTerminalSession. La gestión linda de claves/host viene en M7;
 * por ahora la clave de prueba se carga desde assets.
 */
class SshTerminalSession(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val privateKeyPem: CharArray,
    client: TerminalSessionClient,
) : TerminalSession(5000, client) {

    @Volatile private var conn: Connection? = null
    @Volatile private var sshSession: Session? = null
    @Volatile private var stdin: OutputStream? = null

    override fun onEmulatorInitialized(
        columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int
    ) {
        val msg = "Conectando a $user@$host:$port ...\r\n"
        msg.toByteArray(Charsets.UTF_8).let { onTransportInput(it, 0, it.size) }

        thread(name = "ssh-connect") {
            try {
                val c = Connection(host, port)
                // M2: aceptar cualquier host key (el pinning de huella viene después).
                c.connect({ _, _, _, _ -> true }, 15000, 15000)
                conn = c

                if (!c.authenticateWithPublicKey(user, privateKeyPem, null)) {
                    closeWith("\r\n[autenticación SSH falló]\r\n")
                    return@thread
                }

                val s = c.openSession()
                s.requestPTY("xterm-256color", columns, rows, 0, 0, null)
                s.startShell()
                sshSession = s
                stdin = s.stdin

                // Leer la salida del host y mandarla al emulador.
                val out = s.stdout
                val buf = ByteArray(8192)
                while (true) {
                    val n = out.read(buf)
                    if (n == -1) break
                    onTransportInput(buf, 0, n)
                }
                closeWith("\r\n[conexión cerrada]\r\n")
            } catch (e: Exception) {
                closeWith("\r\n[error SSH: ${e.message}]\r\n")
            }
        }
    }

    override fun writeToTransport(data: ByteArray, offset: Int, count: Int) {
        val s = stdin ?: return
        s.write(data, offset, count)
        s.flush()
    }

    override fun closeTransport() {
        try { sshSession?.close() } catch (_: Exception) {}
        try { conn?.close() } catch (_: Exception) {}
    }

    private fun closeWith(message: String) {
        onTransportClosed(message)
        closeTransport()
    }
}
