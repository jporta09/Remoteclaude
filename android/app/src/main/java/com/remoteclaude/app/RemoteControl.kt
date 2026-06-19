package com.remoteclaude.app

import com.trilead.ssh2.Connection
import java.security.KeyPair

/**
 * Comandos de control de tmux (listar / matar sesiones) por una conexión SSH de un
 * solo uso al gateway. Separado de las sesiones de terminal. BLOQUEA: llamar siempre
 * fuera del hilo principal.
 */
class RemoteControl(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val key: KeyPair,
) {
    private fun exec(command: String): String {
        val (h, p) = TailscaleBridge.endpoint(host, port)
        val c = Connection(h, p)
        return try {
            c.connect({ _, _, _, _ -> true }, 10000, 10000)
            if (!c.authenticateWithPublicKey(user, key)) return ""
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

    /**
     * Auto-enrolamiento (estilo ssh-copy-id): se conecta como 'enroll' con contraseña
     * y sube la clave pública de esta app al gateway. Del lado server, ese usuario sólo
     * puede agregar una clave (ForceCommand), nunca abrir shell. Devuelve la respuesta
     * del server (empieza con "OK:") si funcionó, o null si falló. BLOQUEA.
     */
    fun enrollThisDevice(password: String): String? {
        val pub = KeyStoreSsh.openSshPublicKey("remoteclaude-app")
        val (h, p) = TailscaleBridge.endpoint(host, port)
        val c = Connection(h, p)
        return try {
            c.connect({ _, _, _, _ -> true }, 10000, 10000)
            if (!c.authenticateWithPassword("enroll", password)) return null
            val s = c.openSession()
            s.execCommand(pub)   // el ForceCommand lo recibe como SSH_ORIGINAL_COMMAND
            val out = String(s.stdout.readBytes(), Charsets.UTF_8).trim()
            s.close()
            out
        } catch (_: Exception) {
            null
        } finally {
            try { c.close() } catch (_: Exception) {}
        }
    }

    /**
     * Fija el modo del display virtual (Xvnc :99) — p.ej. "1280x720" para el modo
     * "Escritorio" del visor. Corre en el gateway, que llega al X del display por
     * localhost:6099 (network_mode host). BLOQUEA.
     */
    fun setDisplayMode(mode: String) {
        exec("DISPLAY=localhost:99 xrandr --output VNC-0 --mode '$mode' 2>/dev/null")
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

    // --- Documentos compartidos (host:~/RemoteMarvinDocs) -----------------------
    // exec() corre en el contenedor; saltamos al HOST con nsenter (igual que la shell
    // interactiva) para leer ~/RemoteMarvinDocs del usuario del host.
    private val hostShell =
        "nsenter -t 1 -m -u -i -p -- su - \"\$(cat /etc/remoteclaude/host_user 2>/dev/null||echo root)\" -c"

    /** Lista los docs compartidos: (nombre, bytes, mtimeEpoch), más nuevos primero. */
    fun listDocs(): List<Triple<String, Long, Long>> {
        val out = exec(
            "$hostShell 'find ~/RemoteMarvinDocs -maxdepth 1 -type f " +
                "-printf \"%f\\t%s\\t%T@\\n\" 2>/dev/null'"
        )
        return out.lineSequence().mapNotNull {
            val p = it.split('\t')
            if (p.size >= 3 && p[0].isNotBlank())
                Triple(
                    p[0],
                    p[1].toLongOrNull() ?: 0L,
                    p[2].substringBefore('.').toLongOrNull() ?: 0L,
                )
            else null
        }.sortedByDescending { it.third }.toList()
    }

    /** Contenido de un doc en base64 (one-shot). Vacío si el nombre es inseguro. */
    fun readDocBase64(name: String): String {
        if (name.contains('\'') || name.contains('\n') || name.contains('/')) return ""
        return exec("$hostShell 'base64 ~/RemoteMarvinDocs/$name'").replace("\n", "").trim()
    }

    /** Sesiones vivas con el ÚLTIMO COMANDO tipeado (texto tras el último prompt), o vacío. */
    fun sessionsWithLastLine(): List<Pair<String, String>> {
        // Por cada sesión: captura el pane y, con sed, toma el texto que sigue al último
        // prompt (`…$ ` / `…# `) — o sea el último comando ejecutado. Vacío si no corrió nada.
        val script = "tmux ls -F '#{session_name}' 2>/dev/null | while IFS= read -r s; do " +
            "line=\$(tmux capture-pane -p -J -t \"\$s\" 2>/dev/null | sed -nE 's/.*[#\$] +([^[:space:]].*)\$/\\1/p' | tail -1); " +
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
