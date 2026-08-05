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
                // Android bloquea net.Interfaces() de Go (netlink) -> tsnet no levanta. Le
                // pasamos las interfaces enumeradas desde Java, que sí funciona en Android.
                marvints.Marvints.setInterfaces(enumerateInterfaces())
                marvints.Marvints.start(key, dir.absolutePath, hostname)
                started = true
            } catch (e: Exception) {
                lastError = e.message
            } finally {
                readyLatch.countDown()
            }
        }
    }

    /**
     * Enumera las interfaces de red vía java.net.NetworkInterface (funciona en Android, a
     * diferencia de net.Interfaces() de Go) en el formato que espera marvints.SetInterfaces:
     *   name;index;mtu;flags;hwaddrHex;cidr1,cidr2,...
     * flags = bits de net.Flags (Up=1, Broadcast=2, Loopback=4, P2P=8, Multicast=16, Running=32).
     */
    private fun enumerateInterfaces(): String {
        val sb = StringBuilder()
        try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return ""
            for (ni in ifaces) {
                val up = runCatching { ni.isUp }.getOrDefault(false)
                val loop = runCatching { ni.isLoopback }.getOrDefault(false)
                val p2p = runCatching { ni.isPointToPoint }.getOrDefault(false)
                val multi = runCatching { ni.supportsMulticast() }.getOrDefault(false)
                var flags = 0
                if (up) flags = flags or 1 or 32
                if (loop) flags = flags or 4
                if (p2p) flags = flags or 8
                if (multi) flags = flags or 16 or 2
                val hwHex = runCatching { ni.hardwareAddress }.getOrNull()
                    ?.joinToString("") { "%02x".format(it) } ?: ""
                val cidrs = ni.interfaceAddresses.mapNotNull { ia ->
                    val addr = ia.address?.hostAddress?.substringBefore('%') ?: return@mapNotNull null
                    "$addr/${ia.networkPrefixLength}"
                }.joinToString(",")
                sb.append("${ni.name};${ni.index};${ni.mtu};$flags;$hwHex;$cidrs\n")
            }
        } catch (_: Exception) {
        }
        return sb.toString()
    }

    /** Reconfigura la auth key (la guarda) y reinicia el nodo. */
    fun configure(ctx: Context, authKey: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("ts_authkey", authKey.trim()).apply()
        synchronized(this) {
            // Siempre cerrar el nodo anterior (aunque aún esté conectando con una key
            // consumida) para liberar el stateDir antes de reiniciar con la nueva.
            try { marvints.Marvints.stop() } catch (_: Exception) {}
            started = false
            // Sin esto, tras re-escanear el QR el cache seguía devolviendo los puertos
            // del nodo VIEJO (ya cerrado): la app decía "conectado" y NADA funcionaba.
            forwards.clear(); enabled = false; initialized = false
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
