package com.remoteclaude.app

import android.content.Context
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.trilead.ssh2.Connection
import com.trilead.ssh2.Session
import java.io.OutputStream
import java.security.KeyPair
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.concurrent.thread

/**
 * M5: sesión SSH con tmux + auto-reconexión (sobrevive al bloqueo / cortes de red).
 *
 * Abre SSH al sshd del HOST como tu usuario → `tmux new -A -D -s <name>`, que
 * persiste del lado del server. Si la conexión cae (bloqueo, cambio de red), el loop
 * reconecta solo y vuelve a engancharse al mismo tmux (todo intacto). El
 * ConnectivityManager (desde la Activity) avisa caída/vuelta de red para reaccionar rápido.
 */
class SshTerminalSession(
    ctx: Context,
    private val host: String,
    private val port: Int,
    private val user: String,
    private val keyPair: KeyPair,
    @Volatile var tmuxSession: String,   // mutable: renombrar lo actualiza (lo lee el reconnect)
    client: TerminalSessionClient,
    private val onAuthFailed: (() -> Unit)? = null,   // clave no autorizada -> avisar a la UI
    private val onHostKeyChanged: ((old: String, new: String, redDistinta: Boolean) -> Unit)? = null,
    // El estado REAL de la conexión, para que el chrome (barra, tour) no mienta. Antes la
    // barra se pintaba "conectado" del extra del Intent aunque la auth hubiera fallado o el
    // host estuviera caído. Corre en el hilo ssh-loop: el consumidor debe saltar a la UI.
    private val onEstadoCambio: (() -> Unit)? = null,
    // La sesión tmux se recreó (el host se reinició): avisar visible. El texto en la
    // terminal lo entierra el redibujo de tmux, así que se necesita un Toast. Pasa el
    // nombre de la sesión para que la UI sólo avise por la pestaña activa.
    private val onSesionPerdida: ((String) -> Unit)? = null,
    // El acceso de Tailscale venció (una vez por episodio, junto con el banner): la UI
    // muestra el ↺ Reescanear QR en la barra. Corre en el hilo ssh-loop.
    private val onAccesoVencido: (() -> Unit)? = null,
    // A5-1 (5ª pasada): los avisos de la APP (host-key cambió, enrolamiento vencido, expiry del
    // nodo de la PC) NO se escriben más al pty: ahí son bytes iguales a los del host, un host o un
    // agente inyectado los imita byte a byte, y tmux los borra al redibujar. Van a un banner
    // nativo bajo la barra (MainActivity) y al Diagnóstico.
    private val onAvisoApp: ((AvisoApp, String) -> Unit)? = null,
) : TerminalSession(5000, client) {

    /** Tipos de aviso de la app (para el color del banner). */
    enum class AvisoApp { HOST_KEY, VENCIDO, EXPIRY_HOST }

    private fun avisar(tipo: AvisoApp, texto: String) { onAvisoApp?.invoke(tipo, texto) }

    /** Estado real de la conexión SSH, leído por la UI. */
    enum class Estado { CONECTANDO, CONECTADO, RECONECTANDO, CAIDO }

    @Volatile var estado: Estado = Estado.CONECTANDO
        private set

    private fun cambiarEstado(nuevo: Estado) {
        if (estado == nuevo) return
        estado = nuevo
        // F8: timeline de conexión. cambiarEstado dedup por sí solo (RECONECTANDO se registra
        // una vez por episodio aunque el backoff reintente varias veces).
        val nivel = when (nuevo) {
            Estado.CAIDO -> Diagnostico.Nivel.ERROR
            Estado.RECONECTANDO -> Diagnostico.Nivel.AVISO
            else -> Diagnostico.Nivel.INFO
        }
        Diagnostico.registrar(nivel, "conexión", "$host:$port — ${nuevo.name.lowercase()}")
        onEstadoCambio?.invoke()
    }

    private val appCtx = ctx.applicationContext

    /** Si la clave del host cambió, guarda (vieja, nueva) para cortar y avisar. */
    @Volatile private var keyChanged: Triple<String, String, Boolean>? = null

    // Para no repetir "[reconectando…]" en cada intento del backoff: se anuncia una vez y se
    // resetea al reconectar con éxito.
    @Volatile private var reconexionAnunciada = false
    // Fila 550: si el acceso de Tailscale venció (node key expirada), reconectar es inútil —
    // avisamos UNA vez por episodio para que el usuario reescanee el QR, y no en cada intento.
    @Volatile private var avisoVencidoDado = false

    @Volatile private var userClosed = false
    @Volatile private var cols = 80
    @Volatile private var rows = 24

    @Volatile private var conn: Connection? = null
    @Volatile private var sshSession: Session? = null
    @Volatile private var stdin: OutputStream? = null

    // Detección de half-open EN PRIMER PLANO (F-SRE-1): si mandaste input y no volvió NADA (ni el eco)
    // por un rato, la conexión puede estar medio-abierta. En una conexión sana el eco vuelve al toque,
    // así que `ultimoEnvio > ultimoByte` sostenido es señal de freeze. No se SONDEA la Connection (eso
    // deadlockea, ver forzarReconexion) — se hace un probe fuera de banda (socket nuevo, ver sondear...).
    @Volatile private var ultimoByteMs = 0L    // último input REAL recibido del remoto
    @Volatile private var ultimoEnvioMs = 0L   // último input que mandó el usuario

    private val reconnectLock = Object()
    @Volatile private var waitingToReconnect = false
    // "Reconectá YA" (romper el backoff). Flag para que sea race-safe: si el pedido llega ANTES de
    // que el loop entre a esperar, igual se salta la espera. Lo prende: la red que vuelve y el
    // volver a primer plano (en background la conexión se cae y el backoff dejaba la terminal
    // "trabada" unos segundos al volver, hasta que expiraba la espera).
    @Volatile private var reconectarYa = false

    // Toda I/O de red va fuera del hilo principal (si no, un socket muerto cuelga la UI = ANR).
    private val ioExecutor = Executors.newSingleThreadExecutor()

    override fun onEmulatorInitialized(
        columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int
    ) {
        cols = columns
        this.rows = rows
        thread(name = "ssh-loop") { connectLoop() }
    }

    private fun connectLoop() {
        var attempt = 0
        while (!userClosed) {
            cambiarEstado(if (attempt == 0) Estado.CONECTANDO else Estado.RECONECTANDO)
            // "[reconectando…]" se imprimía en CADA intento del loop → con la PC apagada
            // llenaba la terminal de líneas repetidas. Ahora se anuncia UNA vez por episodio
            // de reconexión (el flag se resetea al reconectar). La barra ya muestra el estado.
            if (attempt == 0) {
                status("Conectando a $user@$host:$port ...\r\n")
            } else if (!reconexionAnunciada) {
                status("\r\n[reconectando…]\r\n")
                reconexionAnunciada = true
            }
            try {
                // Si el Tailscale embebido está activo, se conecta al forward local
                // (127.0.0.1:xxxx -> host:port por la tailnet); si no, directo.
                val (h, p) = TailscaleBridge.endpoint(host, port)
                val c = Connection(h, p)
                c.connect(
                    HostKeys.verifier(
                        appCtx, host, port,
                        onNew = { fp ->
                            status("\r\n[clave del host fijada — $fp]\r\n")
                            Diagnostico.registrar(
                                Diagnostico.Nivel.INFO, "clave-host", "$host:$port — clave fijada ($fp)",
                            )
                        },
                        onMismatch = { old, new, redDist -> keyChanged = Triple(old, new, redDist) },
                        // La terminal es la ÚNICA ruta que fija la primera clave: muestra la
                        // huella por onNew, así confiar es una decisión visible del usuario.
                        permitePin = true,
                    ),
                    15000, 15000,
                )
                conn = c
                // Cerrar la pestaña mientras conectábamos dejaba el hilo colgado para
                // siempre: closeCurrent() corría ANTES de que conn existiera, así que no
                // cerraba nada y el loop seguía hasta quedar leyendo un socket vivo.
                if (userClosed) { try { c.close() } catch (_: Exception) {}; break }
                if (!c.authenticateWithPublicKey(user, keyPair)) {
                    status("\r\n[autenticación SSH falló — la clave no está autorizada]\r\n")
                    Diagnostico.registrar(
                        Diagnostico.Nivel.ERROR, "auth",
                        "$host:$port — la clave de la app no está autorizada para $user",
                    )
                    userClosed = true
                    cambiarEstado(Estado.CAIDO)
                    onAuthFailed?.invoke()
                    break
                }
                // Host CONFIGURADO es requisito para abrir la sesión: sin el marker de
                // setup-host, cada feature fallaría de a una con su propio error críptico
                // (dictado, docs, avisos). Mejor un solo mensaje claro acá. El fallback a
                // ~/.local/bin/marvin-stt cubre hosts configurados antes de que existiera
                // el marker. Se chequea en CADA conexión (barato) para que "corrí el setup
                // y reintenté" funcione sin reiniciar la app.
                if (!hostConfigurado(c)) {
                    status(
                        "\r\n[Este host no está configurado para RemoteMarvin]\r\n" +
                            "[Corré scripts/setup-host.sh en el host y reintentá.]\r\n",
                    )
                    Diagnostico.registrar(
                        Diagnostico.Nivel.ERROR, "setup",
                        "$host:$port — sin marker de setup-host: conexión bloqueada",
                    )
                    userClosed = true
                    cambiarEstado(Estado.CAIDO)
                    break
                }
                // Aviso proactivo del expiry del nodo del HOST (SRE-4p-1): el celu no lo ve,
                // pero el host sí, y en las semanas previas al corte todavía es alcanzable.
                avisarSiExpiraHostPronto(c)
                // Aviso de sesión perdida: si esto es una RECONEXIÓN y la sesión tmux ya no
                // existe en el host, el server se reinició (reboot/OOM/kill) y `tmux new -A`
                // va a crear una NUEVA vacía. Antes eso pasaba mudo en la misma pestaña y
                // parecía que no se había perdido nada (una sesión de Claude de horas, ida).
                if (attempt > 0 && !tmuxSesionVive(c)) {
                    status("\r\n[la sesión anterior se perdió — el host se reinició. Esta es nueva.]\r\n")
                    Diagnostico.registrar(
                        Diagnostico.Nivel.AVISO, "sesión",
                        "$host:$port — la sesión tmux anterior se perdió (el host se reinició)",
                    )
                    onSesionPerdida?.invoke(tmuxSession)
                }
                val s = c.openSession()
                s.requestPTY("xterm-256color", cols, rows, 0, 0, null)
                // -u fuerza UTF-8 (sshd no pasa LANG, si no tmux manda los acentos como
                // '_'). Crea o reengancha la sesión persistente (-D desengancha).
                // MARVIN_DISPLAY marca esta sesión como "de la app": queda en el global env
                // de tmux y lo heredan los panes; el ~/.ssh/config la usa (Match exec) para
                // tunelizar el display/noVNC a los servers que SSH-ees DESDE la app (headed
                // browser remoto). Una terminal/tmux abierta a mano NO lo tiene.
                //
                // CLAUDE_CODE_NO_FLICKER: Claude Code renderiza en pantalla alternativa
                // (equivale a tui=fullscreen, es la variable documentada). En un teléfono el
                // modo clásico es hostil: cada repintado empuja cuadros viejos al historial
                // de tmux, y el gesto natural de leer con el dedo (rueda → copy mode) te deja
                // mirando esa pila congelada — pantallas "mezcladas" que ninguna tecla
                // arregla. Diagnóstico completo en docs/revision-integral.md. Con fullscreen
                // el dedo scrollea la transcripción VIVA, los diálogos navegan en vivo y el
                // historial no junta basura. Sólo afecta sesiones creadas por la app (el env
                // se fija al CREAR la sesión tmux); en la PC manda el settings.json de cada
                // uno, y dentro de una sesión /tui default lo revierte si alguien lo prefiere.
                // EDITOR con default: el Ctrl+G de Claude Code (abrir el plan en el editor —
                // la forma cómoda de LEER un plan largo en 46 columnas) y la 'v' del
                // transcript mode necesitan un editor; sshd no pasa EDITOR y la mayoría de
                // los hosts no lo setean. ${EDITOR:-nano} respeta el del usuario si existe.
                s.execCommand(
                    "EDITOR=\"\${EDITOR:-nano}\" MARVIN_DISPLAY=localhost:6099 CLAUDE_CODE_NO_FLICKER=1 " +
                        "tmux -u new -A -D -s " + ShellQuote.sq(tmuxSession)
                )
                sshSession = s
                stdin = s.stdin
                attempt = 0
                reconexionAnunciada = false   // el próximo corte volverá a anunciar una vez
                avisoVencidoDado = false      // idem para el aviso de acceso vencido
                episodioVencidoLogueado.set(false)   // reconectó: un vencido futuro es episodio nuevo
                cambiarEstado(Estado.CONECTADO)
                // Baseline limpio de los timers de half-open al (re)conectar.
                val ahoraConn = System.currentTimeMillis()
                ultimoByteMs = ahoraConn
                ultimoEnvioMs = ahoraConn

                // Keepalive: tráfico periódico para que el NAT/firewall (sobre todo en redes
                // corporativas) no descarte el mapeo y la conexión no quede "half-open". `sendIgnorePacket`
                // sólo ESCRIBE (no espera respuesta), así que NO detecta half-open — para eso, cada ciclo
                // corremos además el detector de half-open en primer plano (probe fuera de banda, F-SRE-1).
                // Atado a 'c': cuando reconectamos (conn cambia) o cerramos, este hilo termina.
                val thisConn = c
                thread(name = "ssh-keepalive", isDaemon = true) {
                    try {
                        while (!userClosed && conn === thisConn) {
                            Thread.sleep(KEEPALIVE_MS)
                            if (conn !== thisConn) break
                            thisConn.sendIgnorePacket()
                            // Half-open en PRIMER PLANO: si mandaste input y no volvió nada (ni el eco),
                            // sondeamos alcance con un socket NUEVO (NO la Connection: eso deadlockea).
                            if (deberiaSondear()) {
                                if (!sondearAlcanzable()) {
                                    forzarReconexion()   // inalcanzable -> cierre directo -> reconecta
                                    break
                                } else {
                                    ultimoByteMs = System.currentTimeMillis()   // vivo (sin eco): reset
                                }
                            }
                        }
                    } catch (_: Exception) {
                        if (conn === thisConn) closeCurrent()
                    }
                }

                val out = s.stdout
                val buf = ByteArray(8192)
                while (!userClosed) {
                    val n = out.read(buf)
                    if (n <= 0) break     // -1 = fin; 0 no debería pasar, pero no lo tratamos como dato
                    ultimoByteMs = System.currentTimeMillis()   // input real del remoto (para el detector)
                    onTransportInput(buf, 0, n)
                }
            } catch (e: Exception) {
                // cayó la conexión; se reintenta abajo. Sólo se registra si estábamos conectados
                // (si no, ya lo cuenta el estado RECONECTANDO y no llenamos el buffer de ruido).
                if (estado == Estado.CONECTADO) {
                    Diagnostico.registrar(
                        Diagnostico.Nivel.AVISO, "conexión",
                        "$host:$port — se cortó (${e.message ?: e.javaClass.simpleName})",
                    )
                }
            } finally {
                closeCurrent()
            }

            keyChanged?.let { (old, new, redDist) ->
                avisar(AvisoApp.HOST_KEY, "La clave del host cambió — conexión bloqueada (antes $old · ahora $new)")
                Diagnostico.registrar(
                    Diagnostico.Nivel.ERROR, "clave-host",
                    "$host:$port — LA CLAVE DEL HOST CAMBIÓ (antes $old / ahora $new) — bloqueado",
                )
                userClosed = true
                cambiarEstado(Estado.CAIDO)
                onHostKeyChanged?.invoke(old, new, redDist)
            }
            if (userClosed) break
            attempt++
            // Tras un par de intentos fallidos, si el nodo embebido dice que el acceso venció,
            // reconectar no va a lograr nada: se avisa una vez con una causa accionable.
            if (!avisoVencidoDado && attempt >= 2 && TailscaleBridge.accesoVencido()) {
                // Honesto con la LAN: los hosts por tailnet no conectan hasta reescanear, los
                // directos siguen (y este loop sigue reintentando: endpoint() ya cae a directo).
                avisar(
                    AvisoApp.VENCIDO,
                    "El enrolamiento del celu venció — los hosts por tailnet no conectan hasta reescanear " +
                        "el QR (↺); los de LAN siguen. ¿No estás en la PC? Mantené apretado el ↺ para pegar " +
                        "una auth key.",
                )
                // Diagnóstico: una sola vez por episodio (no una por pestaña) — ver companion.
                if (episodioVencidoLogueado.compareAndSet(false, true)) {
                    Diagnostico.registrar(
                        Diagnostico.Nivel.ERROR, "tailscale",
                        "el acceso de Tailscale venció (node key expirada) — hay que reescanear el QR",
                    )
                }
                avisoVencidoDado = true
                onAccesoVencido?.invoke()
            }
            val waitMs = esperaRetry(attempt, Math.random())
            synchronized(reconnectLock) {
                waitingToReconnect = true
                // Si ya pidieron reconectar (p. ej. volviste a la app mientras caíamos), no esperar.
                if (!reconectarYa) {
                    try {
                        reconnectLock.wait(waitMs)
                    } catch (_: InterruptedException) {
                    }
                }
                reconectarYa = false
                waitingToReconnect = false
            }
        }
        onTransportClosed(null)
    }

    /** Pedir reconexión YA (romper el backoff). Race-safe: setea el flag Y notifica, así funciona
     *  tanto si el loop ya está esperando como si todavía no entró a la espera. */
    fun reintentarConexionYa() {
        synchronized(reconnectLock) {
            reconectarYa = true
            reconnectLock.notifyAll()
        }
    }

    /** La red volvió: reconectar ya. */
    fun onNetworkAvailable() = reintentarConexionYa()

    /** La red se cayó: cerrar el socket muerto para que el read se desbloquee y entremos al backoff. */
    fun onNetworkLost() {
        closeCurrent()
    }

    @Volatile private var forzando = false

    /**
     * Al VOLVER a primer plano tras un rato en background: forzar reconexión. En background la
     * conexión suele quedar "media" (muerta pero SIN RST): el kernel del server sigue ACKeando el
     * TCP, así el keepalive (que sólo escribe, no espera respuesta) NO falla, y la app cree que
     * sigue conectada → terminal congelada SIN avisar "reconectando", a veces para siempre. El
     * backoff no aplica (nunca se detectó la caída), así que [reintentarConexionYa] no alcanza.
     *
     * NO sondeamos la conexión: cualquier operación de trilead toma el lock del `Connection` y se
     * cuelga contra el server congelado, y después `closeCurrent()` no puede tomar ese lock
     * (deadlock, verificado). En vez de eso cerramos directo → el read colgado se desbloquea y el
     * loop reconecta (tmux `-A` reengancha la sesión intacta, así que es barato y sin pérdida).
     * Va en un hilo aparte porque `Connection.close()` hace I/O. Sólo lo llama el resume tras superar
     * un umbral de tiempo en background (un vistazo corto no tira la conexión).
     */
    fun forzarReconexion() {
        if (userClosed || conn == null || forzando) return
        forzando = true
        thread(name = "ssh-force-reconnect", isDaemon = true) {
            try {
                reintentarConexionYa()   // que no espere el backoff después de cerrar
                closeCurrent()           // rompe el read colgado -> el loop reconecta
            } finally {
                forzando = false
            }
        }
    }

    private fun closeCurrent() {
        val s = sshSession; val c = conn
        sshSession = null
        conn = null
        stdin = null
        // Desde el ssh-loop/keepalive/forzarReconexion ya estamos en un hilo (inline). Desde el main
        // (cerrar pestaña, reconectar, onDestroy, red perdida) el close synchronized de trilead va en
        // fondo: con un connect en vuelo contra un endpoint colgado era un ANR (SRE-5-4).
        Hilos.cerrarEnFondo("ssh-cierre") {
            try { s?.close() } catch (_: Exception) {}
            try { c?.close() } catch (_: Exception) {}
        }
    }

    /** ¿Correr el probe de half-open? Sólo en primer plano y conectado, y si mandaste input pero no
     *  volvió NADA (ni el eco) por más de [UMBRAL_SIN_ECO_MS]. Ver [sospechaHalfOpen]. */
    private fun deberiaSondear(): Boolean =
        sospechaHalfOpen(
            EstadoApp.enPrimerPlano, estado == Estado.CONECTADO,
            ultimoEnvioMs, ultimoByteMs, System.currentTimeMillis(), UMBRAL_SIN_ECO_MS,
        )

    /** Probe FUERA DE BANDA (socket nuevo, NO la Connection): ¿se alcanza el remoto? Resuelve el
     *  endpoint (con Tailscale es 127.0.0.1:<forward>) y lee el banner SSH end-to-end. */
    private fun sondearAlcanzable(): Boolean {
        val (h, p) = try { TailscaleBridge.endpoint(host, port) } catch (_: Exception) { return false }
        return sondearAlcanzableEn(h, p, PROBE_TIMEOUT_MS.toInt())
    }

    override fun writeToTransport(data: ByteArray, offset: Int, count: Int) {
        // Corre en el hilo escritor del base. Si la conexión está caída, NO propagamos
        // la excepción: así el hilo escritor sigue vivo y drena la cola (evita que el
        // write() del main thread se bloquee), y al reconectar usa el stdin nuevo.
        ultimoEnvioMs = System.currentTimeMillis()   // input del usuario (para el detector de half-open)
        val s = stdin ?: return
        try {
            s.write(data, offset, count)
            s.flush()
        } catch (_: Exception) {
            // descartado; tmux redibuja al reconectar
        }
    }

    override fun onSizeChanged(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        cols = columns
        this.rows = rows
        // resizePTY hace I/O de red -> NUNCA en el hilo principal (lo llama el layout).
        // La sesión se resuelve DENTRO del executor: si se reconectó en el medio, hay que
        // redimensionar la NUEVA. Y execute() puede tirar RejectedExecution si la pestaña
        // ya se cerró (shutdownNow) — pasaba al redimensionar justo después de cerrar.
        try {
            ioExecutor.execute {
                try {
                    sshSession?.resizePTY(cols, rows, 0, 0)
                } catch (_: Exception) {
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    override fun closeTransport() {
        userClosed = true
        onNetworkAvailable() // romper el backoff si está esperando
        closeCurrent()
        ioExecutor.shutdownNow()
    }

    /** ¿La sesión tmux sigue viva en el host? Chequeo rápido no interactivo sobre la misma
     *  conexión. Ante cualquier duda devuelve true (no avisar): mejor callar que un falso
     *  "se perdió la sesión" que asuste sin motivo. */
    // ¿El host pasó por setup-host? Marker nuevo, o el cliente de dictado (hosts con un
    // setup anterior al marker). Fail-open ante error del exec: un chequeo que no pudo
    // correr no debe bloquear una conexión que sí funciona.
    private fun hostConfigurado(c: Connection): Boolean = try {
        val r = SshExec.leer(
            c, "test -f ~/.config/marvin/setup-ok -o -x ~/.local/bin/marvin-stt && echo CONFIGURADO",
            timeoutMs = 3_000, maxBytes = 4_096,
        )
        !r.completo || r.salida.contains("CONFIGURADO")   // sin respuesta a tiempo: fail-open
    } catch (_: Exception) {
        true
    }

    // SRE-4p-1: el nodo del HOST se enrola sin tag y su key vence a ~180 días; el celu NO puede
    // detectarlo (sólo ve su propio nodo embebido, no los peers). Pero el HOST sí ve su expiry,
    // y en las SEMANAS PREVIAS al corte todavía es alcanzable — así que acá, una vez por sesión,
    // se lee el expiry del nodo del host y se avisa proactivamente si falta poco y el nodo no
    // tiene tag (con tag el expiry queda deshabilitado, no vence). Best-effort: cualquier fallo
    // (sin docker/jq/contenedor, o docker sin permiso) es SILENCIO — nunca una falsa alarma.
    @Volatile private var expiryHostChequeado = false

    private fun avisarSiExpiraHostPronto(c: Connection) {
        if (expiryHostChequeado) return
        expiryHostChequeado = true
        try {
            // Acotado: un dockerd colgado bloqueaba este hilo (ssh-loop) ANTES de abrir tmux, en
            // cada primera conexión, sin límite (SRE-5-1). 5 s y 4 KB sobran para una línea.
            val r = SshExec.leer(
                c, "docker exec remoteclaude-ts tailscale status --json 2>/dev/null | jq -r '$EXPIRY_HOST_JQ' 2>/dev/null",
                timeoutMs = 5_000, maxBytes = 4_096,
            )
            if (!r.completo) return
            val out = r.salida.trim()
            // TAGGED / NOEXP / SINSELF / vacío -> no avisar. NORUN y un entero negativo son el caso
            // que antes se callaba: el nodo del host YA venció (o fue revocado).
            val aviso = textoExpiryHost(out, UMBRAL_EXPIRY_HOST_DIAS)
            if (aviso != null) {
                avisar(AvisoApp.EXPIRY_HOST, aviso)
                Diagnostico.registrar(Diagnostico.Nivel.AVISO, "tailscale", "nodo de la PC: $aviso")
            }
        } catch (_: Exception) {
            // best-effort: sin docker/jq/permiso, no se avisa (y no se rompe la conexión)
        }
    }

    private fun tmuxSesionVive(c: Connection): Boolean = try {
        val r = SshExec.leer(
            c, "tmux has-session -t " + ShellQuote.sq(tmuxSession) + " 2>/dev/null && echo VIVE",
            timeoutMs = 3_000, maxBytes = 1_024,
        )
        !r.completo || r.salida.contains("VIVE")   // sin respuesta a tiempo: no asustar
    } catch (_: Exception) {
        true
    }

    private fun status(message: String) {
        val b = message.toByteArray(Charsets.UTF_8)
        onTransportInput(b, 0, b.size)
    }

    private companion object {
        const val KEEPALIVE_MS = 10_000L        // cada cuánto pinguea el NAT y corre el detector
        const val UMBRAL_SIN_ECO_MS = 7_000L    // input sin respuesta > esto -> sospechar half-open
        const val PROBE_TIMEOUT_MS = 5_000L     // connect+read del probe fuera de banda
        const val UMBRAL_EXPIRY_HOST_DIAS = 21  // avisar si el nodo del host vence en <= esto

        // El ERROR de "acceso vencido" a Diagnóstico se registra UNA vez por EPISODIO (no por
        // pestaña): N pestañas pierden la tailnet a la vez y llenaban el post-mortem con N
        // entradas idénticas, justo bajo el storm de reconexión (QA4-4). El banner en la
        // terminal sí es per-pestaña (feedback local de cada pane). Se rearma cuando una sesión
        // vuelve a CONECTADO (arranca un episodio nuevo).
        val episodioVencidoLogueado = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}

/** ¿Se sospecha half-open? Puro y testeable: mandaste input DESPUÉS del último byte recibido y ya
 *  pasó [umbralMs] sin que volviera nada (ni el eco). Sólo aplica en primer plano y conectado. */
fun sospechaHalfOpen(
    enPrimerPlano: Boolean,
    conectado: Boolean,
    ultimoEnvioMs: Long,
    ultimoByteMs: Long,
    now: Long,
    umbralMs: Long,
): Boolean =
    enPrimerPlano && conectado &&
        ultimoEnvioMs > ultimoByteMs &&
        (now - ultimoEnvioMs) > umbralMs

/** Probe de alcance FUERA DE BANDA: abre un socket NUEVO a host:port y lee ≥1 byte del banner SSH
 *  (bytes end-to-end del remoto — con Tailscale, un connect a secas al forward local no prueba nada).
 *  true = alcanzable; false = connect/read timeout o EOF. `use{}` + `soTimeout` garantizan que NO cuelga.
 *  Top-level para testear sin instanciar la sesión. */
fun sondearAlcanzableEn(host: String, port: Int, timeoutMs: Int): Boolean = try {
    java.net.Socket().use { s ->
        s.connect(java.net.InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = timeoutMs
        s.getInputStream().read() >= 0   // ≥1 byte del banner = remoto vivo; -1 (EOF) o excepción = no
    }
} catch (_: Exception) {
    false
}

/**
 * Filtro jq del expiry del nodo del HOST. Copia LITERAL de scripts/lib/expiry-host.jq (el canónico,
 * que también embebe marvin-doctor): test/host/test_expiry_filter_parity.py exige que las tres sean
 * idénticas, así el doctor y la app nunca vuelven a divergir (DEVOPS-5p-3 / SRE-5-1). Salida: TAGGED,
 * NOEXP, SINSELF, NORUN o un entero de días (negativo = ya venció). Recorta los nanosegundos porque
 * jq 1.7 rechaza RFC3339Nano en fromdateiso8601.
 */
internal const val EXPIRY_HOST_JQ = "if .Self == null then \"SINSELF\" elif (.BackendState != null and .BackendState != \"Running\") then \"NORUN\" elif (.Self.Tags // [] | length) > 0 then \"TAGGED\" elif .Self.KeyExpiry == null then \"NOEXP\" else (((.Self.KeyExpiry | sub(\"\\\\.[0-9]+\"; \"\") | fromdateiso8601) - now) / 86400 | floor | tostring) end"


/**
 * Texto del aviso sobre el nodo Tailscale de la PC a partir de la salida del filtro canónico
 * (scripts/lib/expiry-host.jq): NORUN, un entero de días (negativo = ya venció), o nada. Glosario
 * único (UX5-6): "el nodo de la PC" (el del host) vs "el enrolamiento del celu" (el embebido).
 */
internal fun textoExpiryHost(out: String, umbralDias: Int): String? {
    if (out == "NORUN") {
        return "El nodo Tailscale de la PC no está activo (vencido o revocado): por tailnet el celu no va " +
            "a poder conectar. Re-enrolá el nodo de la PC (con tag, así no vuelve a vencer)."
    }
    val dias = out.toIntOrNull() ?: return null
    return when {
        dias < 0 -> "El nodo Tailscale de la PC YA VENCIÓ hace ${-dias} día(s). Re-enrolalo (con tag) o " +
            "deshabilitá su key expiry en la consola; hasta entonces el celu no conecta por tailnet."
        dias <= umbralDias -> "El nodo Tailscale de la PC vence en $dias día(s). Renovalo en la PC (agregale " +
            "el tag al nodo, o deshabilitá el key expiry en la consola) o vas a perder la conexión remota."
        else -> null
    }
}
