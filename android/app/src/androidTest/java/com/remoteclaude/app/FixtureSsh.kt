package com.remoteclaude.app

import com.trilead.ssh2.Connection

/**
 * Canal de control del harness contra el fixture (test/e2e).
 *
 * Entra con password —habilitado sólo para el usuario `tester` del contenedor— y sirve
 * para dos cosas: autorizar la clave del Keystore de la app (sin flavors de debug ni
 * leerla por la UI) y verificar del lado servidor lo que la app dice haber hecho.
 */
class FixtureSsh(private val host: String, private val port: Int) {

    private fun <T> withConn(block: (Connection) -> T): T {
        val c = Connection(host, port)
        return try {
            c.connect({ _, _, _, _ -> true }, 8000, 8000)   // fixture: no interesa pinnear
            check(c.authenticateWithPassword(USER, PASS)) { "el fixture rechazó la password" }
            block(c)
        } finally {
            try { c.close() } catch (_: Exception) {}
        }
    }

    fun exec(cmd: String): String = withConn { c ->
        val s = c.openSession()
        s.execCommand(cmd)
        val out = String(s.stdout.readBytes(), Charsets.UTF_8)
        s.close()
        out
    }

    /** Deja la clave pública de la app en authorized_keys del fixture. */
    fun authorize(pub: String) {
        exec("mkdir -p ~/.ssh && printf '%s\\n' ${ShellQuote.sq(pub)} > ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys")
    }

    fun tmuxSessions(): List<String> =
        exec("tmux ls -F '#{session_name}' 2>/dev/null")
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    fun killAllTmux() { exec("tmux kill-server 2>/dev/null; true") }

    fun capturePane(session: String): String =
        exec("tmux capture-pane -p -t ${ShellQuote.sq(session)} 2>/dev/null")

    companion object {
        const val USER = "tester"
        private const val PASS = "e2e"
    }
}
