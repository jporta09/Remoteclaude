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
    // Si ya esperamos el timeout completo sin que el nodo levante (mala red / nodo trabado),
    // las llamadas siguientes NO vuelven a colgar 15s cada una: esperan poco y caen al directo.
    @Volatile private var esperaExpiro = false
    // A4-1: momento del último re-enrol (configure() con key). Es GLOBAL y no un flag de
    // Activity porque el re-enrol se hace en HostsActivity (pegar la key / Escanear QR) o en
    // la terminal (↺), y el mismatch de host-key salta en MainActivity: el flag local
    // `reVinculando` de MainActivity sólo lo pone su propio scanner, así que tras re-enrolar
    // por pegado el diálogo salía "ablandado" (hallazgo en vivo, 2026-09-03). 0 = nunca.
    @Volatile private var ultimoReEnrolMs = 0L
    private var readyLatch = CountDownLatch(1)
    private val forwards = HashMap<String, Int>()
    private var nextPort = 21000

    fun isEnabled(): Boolean = enabled
    /**
     * ¿El nodo embebido está VIVO (backend Running)? v1.31.2: lo dice el observador del bus IPN
     * del bridge (atómico, sin JNI bloqueante), no el latch `started`, que sólo bajaba al
     * re-enrolar: con la node key vencida a mitad de sesión el backend cae a NeedsLogin y la app
     * seguía creyendo "listo" — mandaba hasta la LAN por el netstack muerto y pintaba hosts
     * en verde (UX5-1/UF5-1/UF5-2, 5ª pasada).
     */
    fun isReady(): Boolean = enabled && started && esRunning()
    private fun esRunning(): Boolean = runCatching { marvints.Marvints.esRunning() }.getOrDefault(false)
    /** ipn.State del backend ("Running", "NeedsLogin", "Starting", "Stopped"…); barato. */
    fun backendState(): String = if (!enabled) "Directo" else runCatching { marvints.Marvints.backendState() }.getOrDefault("Desconocido")
    /** A4-1: true si hubo un re-enrol (configure con key) hace menos de `ventanaMs`. */
    fun reEnrolReciente(ventanaMs: Long = VENTANA_RE_ENROL_MS): Boolean =
        reEnrolRecienteDesde(ultimoReEnrolMs, android.os.SystemClock.elapsedRealtime(), ventanaMs)
    fun error(): String? = lastError

    /**
     * Estado del nodo embebido, para distinguir "mala red" de "el acceso de Tailscale VENCIÓ y
     * hay que re-escanear el QR" (la node key expira a los ~180 días). Devuelve el string crudo
     * de marvints.Estado(): "<backendState>;<expired 0|1>;<keyExpiryEpoch|0>". Sin nodo embebido
     * (modo directo) no aplica: "Directo;0;0".
     */
    fun estado(): String {
        if (!enabled) return "Directo;0;0"
        return runCatching { marvints.Marvints.estado() }.getOrDefault("Desconocido;0;0")
    }

    /**
     * true si el acceso de Tailscale venció y hay que re-enrolar: el backend cayó a NeedsLogin
     * o la node key expiró. Se consulta cuando las conexiones fallan seguido, para dar una causa
     * clara en vez de reconectar en silencio para siempre. Alta confianza (no grita "reescaneá"
     * por una caída de red pasajera): sólo el nodo VIVO en NeedsLogin/expired dispara true.
     */
    fun accesoVencido(): Boolean =
        enabled && (backendState() == "NeedsLogin" || accesoVencidoDeEstado(estado()))

    /**
     * A qué tailnet está vinculado el nodo embebido (nombre del tailnet, o el DNSName del nodo).
     * Para mostrar "te vinculaste a la tailnet X" tras re-enrolar (A4-1). "" si no hay nodo o no
     * se pudo consultar. Toca el nodo por JNI: llamar FUERA del hilo principal.
     */
    fun identidadRed(): String {
        if (!enabled) return ""
        return runCatching { marvints.Marvints.identidadRed() }.getOrDefault("")
    }

    /** Arranca el nodo embebido si hay auth key. Idempotente. Llamar al iniciar la app. */
    @Synchronized
    fun init(ctx: Context) {
        if (initialized) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // La auth key vivía en claro en el XML de prefs (y salía del dispositivo en los
        // backups). Ahora va cifrada con una clave del Keystore; esto migra la vieja.
        val key = SecretStore.migrate(ctx, "ts_authkey", "ts_authkey")
        if (key.isBlank()) { enabled = false; initialized = true; return }
        enabled = true
        initialized = true
        val hostname = prefs.getString("ts_hostname", null)
            ?: ("remotemarvin-" + Integer.toHexString((System.nanoTime() and 0xffffff).toInt()))
                .also { prefs.edit().putString("ts_hostname", it).apply() }
        val dir = File(ctx.filesDir, "ts-state").apply { mkdirs() }
        // Capturar el latch de ESTE intento como local: tras un configure() (re-enrol) el
        // campo readyLatch se reemplaza por uno nuevo, y si el hilo viejo contara el CAMPO en
        // su finally liberaría el latch NUEVO antes de tiempo (DEV-4C). Cada hilo cuenta el suyo.
        val latch = readyLatch
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
                latch.countDown()
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

    /**
     * Re-informa las interfaces de red al nodo embebido. Hay que llamarla al cambiar de
     * red: el getter registrado devuelve una lista fija y, sin refrescarla, tsnet no ve
     * la interfaz nueva y la conexión no se recupera.
     */
    fun refreshInterfaces() {
        if (!enabled) return
        runCatching { marvints.Marvints.setInterfaces(enumerateInterfaces()) }
    }

    /**
     * Reconfigura la auth key (la guarda) y reinicia el nodo. El reinicio (stop del nodo viejo +
     * arranque del nuevo) va en un hilo: antes corría en el hilo principal desde el diálogo
     * de hosts y el ↺ de la terminal (DEV-N2). Vuelve enseguida; el estado se sigue por
     * [isReady]/[backendState]/[error].
     */
    fun configure(ctx: Context, authKey: String) {
        SecretStore.put(ctx, "ts_authkey", authKey.trim())
        // Vacío = conexión directa, no es un re-enrol; con key sí (cualquiera de los 3 caminos).
        if (authKey.isNotBlank()) ultimoReEnrolMs = android.os.SystemClock.elapsedRealtime()
        // por las dudas: si quedaba una copia en claro de una versión anterior, fuera
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("ts_authkey").apply()
        // El error del Up ANTERIOR no describe a esta key: sin esto hosts decía "no se pudo
        // conectar" a 1 s de pegar una key nueva (QA5-2).
        lastError = null
        val app = ctx.applicationContext
        thread(name = "tailscale-configure") { reiniciar(app) }
    }

    private fun reiniciar(ctx: Context) {
        synchronized(this) {
            // Siempre cerrar el nodo anterior (aunque aún esté conectando con una key
            // consumida) para liberar el stateDir antes de reiniciar con la nueva.
            try { marvints.Marvints.stop() } catch (_: Exception) {}
            started = false
            // Sin esto, tras re-escanear el QR el cache seguía devolviendo los puertos
            // del nodo VIEJO (ya cerrado): la app decía "conectado" y NADA funcionaba.
            forwards.clear(); enabled = false; initialized = false
            esperaExpiro = false   // nodo nuevo: darle de nuevo los 15s completos
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
        if (!started) {
            // Primera vez: hasta 15s para que el nodo levante. Si ya expiró antes, sólo 1s
            // (fail-fast): en mala red la pantalla de docs/dictado no se congela 15s por llamada.
            val espera = if (esperaExpiro) 1L else 15L
            try {
                if (!readyLatch.await(espera, TimeUnit.SECONDS) && !started) esperaExpiro = true
            } catch (_: Exception) {}
        }
        // La ruta la decide el estado REAL del nodo, no el latch: con el backend fuera de Running
        // (NeedsLogin = vencido/revocado, Starting, Stopped) TODO va directo. Antes, con el nodo
        // vencido a mitad de sesión, hasta 10.0.2.2 (LAN) iba por el forward embebido, que no
        // devolvía banner y agotaba 15 s por intento (UF5-1, sondeado en vivo).
        if (!started || !esRunning()) return host to port
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

/** Parseo puro del string de marvints.Estado() ("<backendState>;<expired>;<epoch>"): true si el
 *  acceso venció (backend en NeedsLogin, o la node key marcada como expirada). Top-level para
 *  testearlo sin el nodo nativo. Un string mal formado no dispara falso positivo. */
fun accesoVencidoDeEstado(crudo: String): Boolean {
    val campos = crudo.split(";")
    return campos.getOrNull(0) == "NeedsLogin" || campos.getOrNull(1) == "1"
}

/** Ventana en la que un mismatch de host-key se considera "justo después de re-enrolar". */
const val VENTANA_RE_ENROL_MS = 60_000L

/**
 * Lógica pura de [TailscaleBridge.reEnrolReciente], separada para testearla en JVM: hubo
 * re-enrol (`ultimoMs != 0`) y pasó menos de `ventanaMs` desde entonces. Un reloj que retrocede
 * (ahora < ultimo) no cuenta como reciente.
 */
fun reEnrolRecienteDesde(ultimoMs: Long, ahoraMs: Long, ventanaMs: Long): Boolean =
    ultimoMs != 0L && ahoraMs >= ultimoMs && (ahoraMs - ultimoMs) < ventanaMs
