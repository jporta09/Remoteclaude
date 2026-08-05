package com.remoteclaude.app

import android.content.Context
import com.trilead.ssh2.Connection
import java.security.KeyPair

/**
 * Comandos de control de tmux (listar / matar sesiones) por una conexión SSH de un
 * solo uso al gateway. Separado de las sesiones de terminal. BLOQUEA: llamar siempre
 * fuera del hilo principal.
 */
class RemoteControl(
    ctx: Context,
    private val host: String,
    private val port: Int,
    private val user: String,
    private val key: KeyPair,
) {
    private val appCtx = ctx.applicationContext

    /** Verificador de clave de host. Este camino NO pregunta: si cambió, falla. */
    private fun verifier() = HostKeys.verifier(appCtx, host, port)
    /** Salida de un comando remoto: distingue "no hay nada" de "falló". */
    data class Exec(val out: String, val ok: Boolean, val error: String? = null) {
        val failed get() = !ok
    }

    /**
     * Corre un comando en el host y devuelve salida + estado.
     *
     * Antes esto devolvía "" ante CUALQUIER problema (red caída, clave no autorizada,
     * comando inexistente), indistinguible de un resultado vacío legítimo: por eso la
     * lista de documentos aparecía vacía en vez de decir que no se pudo conectar.
     */
    private fun execResult(command: String): Exec {
        var c: Connection? = null
        return try {
            // endpoint() puede lanzar (el forward de tsnet falla): va DENTRO del try,
            // si no la excepción escapaba hasta el thread {} del caller y mataba la app.
            val (h, p) = TailscaleBridge.endpoint(host, port)
            val conn = Connection(h, p).also { c = it }
            conn.connect(verifier(), 10000, 10000)
            if (!conn.authenticateWithPublicKey(user, key))
                return Exec("", false, "la clave de la app no está autorizada en el host")
            val s = conn.openSession()
            s.execCommand(command)
            val out = String(s.stdout.readBytes(), Charsets.UTF_8)
            val rc = s.exitStatus
            s.close()
            if (rc != null && rc != 0) Exec(out, false, "el host devolvió código $rc")
            else Exec(out, true)
        } catch (e: Exception) {
            Exec("", false, e.message ?: "no se pudo conectar al host")
        } finally {
            try { c?.close() } catch (_: Exception) {}
        }
    }

    /** Compatibilidad: solo la salida (vacía si falló). Preferir execResult(). */
    private fun exec(command: String): String = execResult(command).out

    /**
     * Fija el modo del display virtual (Xvnc :99) — p.ej. "1280x720" para el modo
     * "Escritorio" del visor. Corre en el gateway, que llega al X del display por
     * localhost:6099 (network_mode host). BLOQUEA.
     */
    fun setDisplayMode(mode: String) {
        exec("DISPLAY=localhost:99 xrandr --output VNC-0 --mode ${ShellQuote.sq(mode)} 2>/dev/null")
    }

    /** Nombres de las sesiones tmux vivas en el gateway. */
    fun listSessions(): List<String> =
        exec("tmux ls -F '#{session_name}' 2>/dev/null")
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    /** Mata una sesión tmux (destruye lo que estuviera corriendo). */
    fun killSession(name: String) {
        exec("tmux kill-session -t ${ShellQuote.sq(name)} 2>/dev/null")
    }

    /**
     * Renombra una sesión tmux. Devuelve true si el host confirmó el cambio.
     *
     * Importa el resultado: si se persiste el nombre nuevo y el host no lo aplicó, al
     * reconectar `tmux new -A` abre una sesión NUEVA vacía y la real queda huérfana.
     */
    fun renameSession(old: String, new: String): Boolean =
        execResult("tmux rename-session -t ${ShellQuote.sq(old)} ${ShellQuote.sq(new)}").ok

    // --- Documentos compartidos (~/RemoteMarvinDocs del usuario) ----------------
    // exec() corre directamente como el usuario en el host (SSH al sshd del host), así
    // que leemos ~/RemoteMarvinDocs sin saltos: el '~' lo expande el shell de login.

    /** Lista los docs compartidos: (nombre, bytes, mtimeEpoch), más nuevos primero. */
    fun listDocs(): List<Triple<String, Long, Long>> {
        val out = exec(
            "find ~/RemoteMarvinDocs -maxdepth 1 -type f -printf '%f\\t%s\\t%T@\\n' 2>/dev/null"
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
        return exec("base64 ~/RemoteMarvinDocs/${ShellQuote.sq(name)} 2>/dev/null").replace("\n", "").trim()
    }

    // --- Dictado por voz ---------------------------------------------------------

    /**
     * Manda un WAV al host y devuelve el texto transcripto (daemon marvin-stt vía el
     * cliente `marvin-stt`). El primer dictado puede tardar (arranque del daemon /
     * carga del modelo). Lanza IllegalStateException con mensaje legible si falla.
     * BLOQUEA: llamar fuera del hilo principal.
     */
    fun transcribe(wav: ByteArray): String {
        val (h, p) = TailscaleBridge.endpoint(host, port)
        val c = Connection(h, p)
        try {
            c.connect(verifier(), 10000, 10000)
            if (!c.authenticateWithPublicKey(user, key))
                throw IllegalStateException("no autenticó (clave no autorizada)")
            val s = c.openSession()
            s.execCommand("~/.local/bin/marvin-stt 2>&1")
            s.stdin.use { it.write(wav) }        // close = EOF: el cliente empieza
            val out = String(s.stdout.readBytes(), Charsets.UTF_8).trim()
            val rc = s.exitStatus
            s.close()
            // El exit status es la señal confiable: sin esto, cualquier cosa que el
            // cliente imprima al fallar (p.ej. "curl: (28) Operation timed out") se
            // devolvía como si fuera la transcripción y se tipeaba en la terminal.
            if (rc != null && rc != 0)
                throw IllegalStateException(out.ifBlank { "el dictado falló en el host (código $rc)" })
            if (out.startsWith("error:") || out.contains("command not found") ||
                out.contains("No such file")
            ) throw IllegalStateException(out.ifBlank { "falló el dictado en el host" })
            return out
        } finally {
            try { c.close() } catch (_: Exception) {}
        }
    }

    /**
     * Arranca (fire-and-forget) el server de dictado EN VIVO en el host, para que el
     * PRÓXIMO dictado ya tenga streaming (modo ondemand). No bloquea esperando la carga.
     */
    fun kickLiveStt() {
        exec("XDG_RUNTIME_DIR=/run/user/\$(id -u) systemctl --user start marvin-stt-live.service >/dev/null 2>&1 &")
    }

    /**
     * Modo de energía del daemon de dictado: "status" | "always" | "ondemand".
     * Devuelve el mensaje del cliente (o vacío si el host no tiene marvin-stt). BLOQUEA.
     */
    fun sttMode(action: String): String =
        exec("~/.local/bin/marvin-stt mode ${ShellQuote.sq(action)} 2>&1").trim()

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
