package com.remoteclaude.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Un host al que conectarse (p.ej. "Mi PC" o "Server de un amigo"). */
data class Host(
    val id: String,
    val label: String,
    val hostname: String,
    val port: Int,
    val user: String,
    /** Mantener vivo el canal de avisos "Claude te espera" con la app en background (foreground service). */
    val avisosBg: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("label", label)
        .put("hostname", hostname).put("port", port).put("user", user)
        .put("avisosBg", avisosBg)

    companion object {
        fun fromJson(o: JSONObject) = Host(
            id = o.optString("id").ifBlank { error("host sin id") },
            label = o.optString("label").ifBlank { o.optString("hostname") },
            hostname = o.optString("hostname").ifBlank { error("host sin hostname") },
            port = o.optInt("port", 22),
            user = o.optString("user", "root"),
            avisosBg = o.optBoolean("avisosBg", false),
        )
    }
}

/** Persistencia de los hosts en SharedPreferences (JSON). */
object HostStore {
    private const val PREFS = "remotemarvin"
    private const val KEY = "hosts"

    /**
     * Lista de hosts. Tolera basura: antes, un JSON corrupto en las prefs hacía que
     * HostsActivity.onResume lanzara en CADA arranque y la app quedaba inutilizable sin
     * más salida que borrar los datos.
     */
    fun load(ctx: Context): MutableList<Host> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]").orEmpty()
        val out = mutableListOf<Host>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                runCatching { Host.fromJson(o) }.getOrNull()?.let(out::add)
            }
        } catch (_: Exception) {
            // prefs corruptas: se empieza con la lista vacía en vez de crashear
        }
        return out
    }

    fun save(ctx: Context, hosts: List<Host>) {
        val arr = JSONArray()
        hosts.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, arr.toString()).apply()
    }

    /** Inserta o reemplaza por id. */
    fun upsert(ctx: Context, host: Host) {
        val list = load(ctx)
        val i = list.indexOfFirst { it.id == host.id }
        if (i >= 0) list[i] = host else list.add(host)
        save(ctx, list)
    }

    /** Borra el host y TODO lo que quedaba colgado de él (pestañas, pin de clave). */
    fun delete(ctx: Context, id: String) {
        val host = load(ctx).firstOrNull { it.id == id }
        save(ctx, load(ctx).filterNot { it.id == id })
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().remove("tabs_$id").remove("active_$id").apply()
        host?.let { HostKeys.forget(ctx, it.hostname, it.port) }
    }
}
