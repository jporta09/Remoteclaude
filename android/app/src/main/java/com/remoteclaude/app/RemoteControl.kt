package com.remoteclaude.app

import com.trilead.ssh2.Connection

/**
 * Comandos de control de tmux (listar / matar sesiones) por una conexión SSH de un
 * solo uso al gateway. Separado de las sesiones de terminal. BLOQUEA: llamar siempre
 * fuera del hilo principal.
 */
class RemoteControl(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val key: CharArray,
) {
    private fun exec(command: String): String {
        val c = Connection(host, port)
        return try {
            c.connect({ _, _, _, _ -> true }, 10000, 10000)
            if (!c.authenticateWithPublicKey(user, key, null)) return ""
            val s = c.openSession()
            s.execCommand(command)
            val out = s.stdout.readBytes()
            s.close()
            String(out, Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        } finally {
            try { c.close() } catch (_: Exception) {}
        }
    }

    /** Nombres de las sesiones tmux vivas en el gateway. */
    fun listSessions(): List<String> =
        exec("tmux ls -F '#{session_name}' 2>/dev/null")
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    /** Mata una sesión tmux (destruye lo que estuviera corriendo). */
    fun killSession(name: String) {
        exec("tmux kill-session -t '$name' 2>/dev/null")
    }
}
