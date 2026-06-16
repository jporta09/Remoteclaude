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

    /** Renombra una sesión tmux (para mantenerla linkeada al nombre de la pestaña). */
    fun renameSession(old: String, new: String) {
        exec("tmux rename-session -t '$old' '$new' 2>/dev/null")
    }

    /** Sesiones vivas con la última línea EJECUTADA (último comando/salida), o vacío. */
    fun sessionsWithLastLine(): List<Pair<String, String>> {
        // Por cada sesión: captura el pane, descarta líneas en blanco y las que son solo
        // prompt (terminan en `$ ` o `# ` sin comando), y toma la última que queda. Si no
        // corrió nada, queda vacío.
        val script = "tmux ls -F '#{session_name}' 2>/dev/null | while IFS= read -r s; do " +
            "line=\$(tmux capture-pane -p -t \"\$s\" 2>/dev/null | grep -vE '^[[:space:]]*\$' | grep -vE '[#\$][[:space:]]*\$' | tail -1); " +
            "printf '%s\\t%s\\n' \"\$s\" \"\$line\"; done"
        return exec(script).lineSequence()
            .mapNotNull {
                val p = it.split('\t', limit = 2)
                when {
                    p.size == 2 && p[0].isNotBlank() -> p[0].trim() to p[1].trim()
                    p.size == 1 && p[0].isNotBlank() -> p[0].trim() to ""
                    else -> null
                }
            }.toList()
    }
}
