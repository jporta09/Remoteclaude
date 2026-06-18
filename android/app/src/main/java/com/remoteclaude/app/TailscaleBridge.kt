package com.remoteclaude.app

import android.content.Context
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Tailscale embebido en la app (AAR `marvints` = tsnet vía gomobile). Levanta un nodo
 * Tailscale en userspace dentro del proceso y expone forwards TCP locales hacia la tailnet,
 * así el SSH y el WebView (noVNC) se conectan a 127.0.0.1 sin depender de la app de Tailscale
 * aparte. Conserva NAT traversal, roaming y MagicDNS.
 *
 * Modo embebido = hay una auth key guardada. Si no, la app usa el host:port directo (modo
 * anterior, que dependía de la app de Tailscale del sistema).
 */
object TailscaleBridge {

    private const val PREFS = "remotemarvin"

    @Volatile private var enabled = false
    @Volatile private var started = false
    @Volatile private var initialized = false
    @Volatile private var lastError: String? = null
    private var readyLatch = CountDownLatch(1)
    private val forwards = HashMap<String, Int>()
    private var nextPort = 21000

    fun isEnabled(): Boolean = enabled
    fun isReady(): Boolean = started
    fun error(): String? = lastError

    /** Arranca el nodo embebido si hay auth key. Idempotente. Llamar al iniciar la app. */
    @Synchronized
    fun init(ctx: Context) {
        if (initialized) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = prefs.getString("ts_authkey", null)
        if (key.isNullOrBlank()) { enabled = false; initialized = true; return }
        enabled = true
        initialized = true
        val hostname = prefs.getString("ts_hostname", null)
            ?: ("remotemarvin-" + Integer.toHexString((System.nanoTime() and 0xffffff).toInt()))
                .also { prefs.edit().putString("ts_hostname", it).apply() }
        val dir = File(ctx.filesDir, "ts-state").apply { mkdirs() }
        thread(name = "tailscale-up") {
            try {
                marvints.Marvints.start(key, dir.absolutePath, hostname)
                started = true
            } catch (e: Exception) {
                lastError = e.message
            } finally {
                readyLatch.countDown()
            }
        }
    }

    /** Reconfigura la auth key (la guarda) y reinicia el nodo. */
    fun configure(ctx: Context, authKey: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("ts_authkey", authKey.trim()).apply()
        synchronized(this) {
            if (started) { try { marvints.Marvints.stop() } catch (_: Exception) {} }
            started = false; enabled = false; initialized = false
            readyLatch = CountDownLatch(1)
        }
        init(ctx)
    }

    /**
     * Endpoint a usar para conectar a host:port. En modo embebido devuelve 127.0.0.1:<forward>;
     * si no, el host:port directo. BLOQUEA hasta ~15s esperando el nodo: llamar FUERA del hilo
     * principal.
     */
    fun endpoint(host: String, port: Int): Pair<String, Int> {
        if (!enabled) return host to port
        try { readyLatch.await(15, TimeUnit.SECONDS) } catch (_: Exception) {}
        if (!started) return host to port   // fallback si el nodo no levantó
        return "127.0.0.1" to localPort(host, port)
    }

    @Synchronized
    private fun localPort(host: String, remotePort: Int): Int {
        val k = "$host:$remotePort"
        forwards[k]?.let { return it }
        val lp = nextPort++
        marvints.Marvints.forward(lp.toLong(), host, remotePort.toLong())
        forwards[k] = lp
        return lp
    }
}
