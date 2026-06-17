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
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("label", label)
        .put("hostname", hostname).put("port", port).put("user", user)

    companion object {
        fun fromJson(o: JSONObject) = Host(
            id = o.getString("id"),
            label = o.getString("label"),
            hostname = o.getString("hostname"),
            port = o.optInt("port", 22),
            user = o.optString("user", "root"),
        )
    }
}

/** Persistencia de los hosts en SharedPreferences (JSON). */
object HostStore {
    private const val PREFS = "remotemarvin"
    private const val KEY = "hosts"

    fun load(ctx: Context): MutableList<Host> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]").orEmpty()
        val arr = JSONArray(raw)
        return MutableList(arr.length()) { Host.fromJson(arr.getJSONObject(it)) }
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

    fun delete(ctx: Context, id: String) {
        save(ctx, load(ctx).filterNot { it.id == id })
    }
}
