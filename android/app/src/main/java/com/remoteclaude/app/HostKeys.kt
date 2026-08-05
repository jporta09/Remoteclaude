package com.remoteclaude.app

import android.content.Context
import android.util.Base64
import com.trilead.ssh2.ServerHostKeyVerifier
import java.security.MessageDigest

/**
 * Fijado (pinning) de claves de host SSH, estilo TOFU: la primera vez se acepta y se
 * recuerda; si después cambia, se RECHAZA la conexión.
 *
 * Antes el verificador era `{ _, _, _, _ -> true }` en las 5 rutas SSH: aceptaba
 * cualquier clave, así que un intermediario en la red podía hacerse pasar por el host y
 * quedarse con toda la sesión de terminal. Tailscale no alcanza como defensa: la app
 * también conecta directo cuando el nodo embebido está apagado.
 *
 * IDENTIDAD = el hostname y puerto CONFIGURADOS, no los que ve el socket. Con el
 * Tailscale embebido la conexión real va a `127.0.0.1:<puerto efímero>`, distinto en cada
 * arranque: fijar por ahí daría diferencias falsas todo el tiempo.
 *
 * Se guarda una clave por algoritmo, como hace `known_hosts`: si el server ofrece un
 * algoritmo que todavía no vimos, se fija ese también (y no se lo toma como cambio).
 */
object HostKeys {

    private const val PREFS = "remotemarvin"

    private fun entry(host: String, port: Int, algo: String) = "hostkey_${host}_${port}_$algo"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Qué hacer con la clave que presentó el server. */
    enum class Decision { PIN, MATCH, MISMATCH }

    /**
     * Núcleo de la decisión, sin Android: [stored] es lo que teníamos fijado para ese
     * (host, puerto, algoritmo) y [presented] lo que mandó el server, ambos en base64.
     * Se extrae para poder testearlo sin emulador.
     */
    fun decide(stored: String?, presented: String): Decision = when (stored) {
        null -> Decision.PIN
        presented -> Decision.MATCH
        else -> Decision.MISMATCH
    }

    /** Huella estilo OpenSSH: SHA256:<base64 sin padding>. */
    fun fingerprint(key: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(key)
        return "SHA256:" + Base64.encodeToString(d, Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** Olvida TODO lo fijado para un host (reinstalaciones legítimas del server). */
    fun forget(ctx: Context, host: String, port: Int) {
        val p = prefs(ctx)
        val pref = "hostkey_${host}_${port}_"
        p.edit().apply { p.all.keys.filter { it.startsWith(pref) }.forEach { remove(it) } }.apply()
    }

    /** ¿Hay al menos una clave fijada para este host? */
    fun hasPinned(ctx: Context, host: String, port: Int): Boolean {
        val pref = "hostkey_${host}_${port}_"
        return prefs(ctx).all.keys.any { it.startsWith(pref) }
    }

    /**
     * Verificador para `Connection.connect()`.
     *
     * @param onNew se invoca al fijar una clave por primera vez (para avisar en la UI).
     * @param onMismatch se invoca cuando la clave cambió; la conexión se rechaza igual.
     *   Los caminos no interactivos (documentos, dictado, visor) no pasan nada acá: fallan
     *   y listo. Sólo la terminal pregunta, para que la decisión de confiar sea explícita
     *   y no la dispare una tarea de fondo.
     */
    fun verifier(
        ctx: Context,
        host: String,
        port: Int,
        onNew: ((String) -> Unit)? = null,
        onMismatch: ((old: String, new: String) -> Unit)? = null,
    ) = ServerHostKeyVerifier { _, _, algo, key ->
        val k = entry(host, port, algo)
        val p = prefs(ctx)
        val seen = p.getString(k, null)
        val now = Base64.encodeToString(key, Base64.NO_WRAP)
        when (decide(seen, now)) {
            Decision.PIN -> {
                p.edit().putString(k, now).apply()
                onNew?.invoke(fingerprint(key))
                true
            }
            Decision.MATCH -> true
            Decision.MISMATCH -> {
                val old = try {
                    fingerprint(Base64.decode(seen!!, Base64.NO_WRAP))
                } catch (_: Exception) {
                    "desconocida"
                }
                onMismatch?.invoke(old, fingerprint(key))
                false
            }
        }
    }
}
