package com.remoteclaude.blackbox

import com.trilead.ssh2.Connection

/**
 * Canal de control contra el fixture (test/e2e), desde el proceso de TEST.
 *
 * Es un primo del `FixtureSsh` de `:app`, a propósito duplicado y no compartido: este
 * módulo no puede depender de `:app` sin volver a atar el APK de test a las clases de la
 * app, que es justamente lo que hace falta evitar acá. Son ~40 líneas; el acoplamiento
 * costaría más que la repetición.
 */
class Fixture(private val host: String, private val port: Int) {

    private fun <T> conectado(block: (Connection) -> T): T {
        val c = Connection(host, port)
        return try {
            c.connect({ _, _, _, _ -> true }, 8000, 8000)   // fixture: no interesa pinnear
            check(c.authenticateWithPassword(USER, PASS)) { "el fixture rechazó la password" }
            block(c)
        } finally {
            try { c.close() } catch (_: Exception) {}
        }
    }

    fun exec(cmd: String): String = conectado { c ->
        val s = c.openSession()
        s.execCommand(cmd)
        val salida = String(s.stdout.readBytes(), Charsets.UTF_8)
        s.close()
        salida
    }

    /** Comillado POSIX: los nombres y claves entran a un shell remoto. */
    private fun sq(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun autorizar(clavePublica: String) {
        exec("mkdir -p ~/.ssh && printf '%s\\n' ${sq(clavePublica)} > ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys")
    }

    fun desautorizar() { exec("rm -f ~/.ssh/authorized_keys") }

    fun sesionesTmux(): List<String> =
        exec("tmux ls -F '#{session_name}' 2>/dev/null")
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    fun matarTmux() { exec("tmux kill-server 2>/dev/null; true") }

    fun panel(sesion: String): String = exec("tmux capture-pane -p -t ${sq(sesion)} 2>/dev/null")

    companion object {
        const val USER = "tester"
        private const val PASS = "e2e"
    }
}
