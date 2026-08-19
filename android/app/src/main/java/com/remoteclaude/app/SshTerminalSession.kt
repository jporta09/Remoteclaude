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
    private val onHostKeyChanged: ((old: String, new: String) -> Unit)? = null,
    // El estado REAL de la conexión, para que el chrome (barra, tour) no mienta. Antes la
    // barra se pintaba "conectado" del extra del Intent aunque la auth hubiera fallado o el
    // host estuviera caído. Corre en el hilo ssh-loop: el consumidor debe saltar a la UI.
    private val onEstadoCambio: (() -> Unit)? = null,
    // La sesión tmux se recreó (el host se reinició): avisar visible. El texto en la
    // terminal lo entierra el redibujo de tmux, así que se necesita un Toast. Pasa el
    // nombre de la sesión para que la UI sólo avise por la pestaña activa.
    private val onSesionPerdida: ((String) -> Unit)? = null,
) : TerminalSession(5000, client) {

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
    @Volatile private var keyChanged: Pair<String, String>? = null

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

    private val reconnectLock = Object()
    @Volatile private var waitingToReconnect = false

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
                        onMismatch = { old, new -> keyChanged = old to new },
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
                s.execCommand(
                    "MARVIN_DISPLAY=localhost:6099 CLAUDE_CODE_NO_FLICKER=1 " +
                        "tmux -u new -A -D -s " + ShellQuote.sq(tmuxSession)
                )
                sshSession = s
                stdin = s.stdin
                attempt = 0
                reconexionAnunciada = false   // el próximo corte volverá a anunciar una vez
                avisoVencidoDado = false      // idem para el aviso de acceso vencido
                cambiarEstado(Estado.CONECTADO)

                // Keepalive: tráfico periódico para que el NAT/firewall (sobre todo en redes
                // corporativas) no descarte el mapeo y la conexión no quede "half-open". Si el
                // ping falla, la conexión está muerta -> cerrarla desbloquea el read y reconecta.
                // Atado a 'c': cuando reconectamos (conn cambia) o cerramos, este hilo termina.
                val thisConn = c
                thread(name = "ssh-keepalive", isDaemon = true) {
                    try {
                        while (!userClosed && conn === thisConn) {
                            Thread.sleep(10000)
                            if (conn === thisConn) thisConn.sendIgnorePacket()
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

            keyChanged?.let { (old, new) ->
                status(
                    "\r\n[LA CLAVE DEL HOST CAMBIÓ — conexión bloqueada]\r\n" +
                        "  antes: $old\r\n  ahora: $new\r\n" +
                        "  Puede ser una reinstalación del server… o alguien interceptando.\r\n"
                )
                Diagnostico.registrar(
                    Diagnostico.Nivel.ERROR, "clave-host",
                    "$host:$port — LA CLAVE DEL HOST CAMBIÓ (antes $old / ahora $new) — bloqueado",
                )
                userClosed = true
                cambiarEstado(Estado.CAIDO)
                onHostKeyChanged?.invoke(old, new)
            }
            if (userClosed) break
            attempt++
            // Tras un par de intentos fallidos, si el nodo embebido dice que el acceso venció,
            // reconectar no va a lograr nada: se avisa una vez con una causa accionable.
            if (!avisoVencidoDado && attempt >= 2 && TailscaleBridge.accesoVencido()) {
                status(
                    "\r\n[el acceso de Tailscale venció — reescaneá el QR desde la línea de " +
                        "estado de Tailscale para volver a habilitar la conexión]\r\n"
                )
                Diagnostico.registrar(
                    Diagnostico.Nivel.ERROR, "tailscale",
                    "el acceso de Tailscale venció (node key expirada) — hay que reescanear el QR",
                )
                avisoVencidoDado = true
            }
            val waitMs = minOf(1000L * attempt, 8000L)
            synchronized(reconnectLock) {
                waitingToReconnect = true
                try {
                    reconnectLock.wait(waitMs)
                } catch (_: InterruptedException) {
                }
                waitingToReconnect = false
            }
        }
        onTransportClosed(null)
    }

    /** La red volvió: despertar el backoff para reconectar ya. */
    fun onNetworkAvailable() {
        synchronized(reconnectLock) {
            if (waitingToReconnect) reconnectLock.notifyAll()
        }
    }

    /** La red se cayó: cerrar el socket muerto para que el read se desbloquee y entremos al backoff. */
    fun onNetworkLost() {
        closeCurrent()
    }

    private fun closeCurrent() {
        try { sshSession?.close() } catch (_: Exception) {}
        try { conn?.close() } catch (_: Exception) {}
        sshSession = null
        conn = null
        stdin = null
    }

    override fun writeToTransport(data: ByteArray, offset: Int, count: Int) {
        // Corre en el hilo escritor del base. Si la conexión está caída, NO propagamos
        // la excepción: así el hilo escritor sigue vivo y drena la cola (evita que el
        // write() del main thread se bloquee), y al reconectar usa el stdin nuevo.
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
    private fun tmuxSesionVive(c: Connection): Boolean = try {
        val chk = c.openSession()
        chk.execCommand("tmux has-session -t " + ShellQuote.sq(tmuxSession) + " 2>/dev/null && echo VIVE")
        val out = String(chk.stdout.readBytes(), Charsets.UTF_8)
        chk.close()
        out.contains("VIVE")
    } catch (_: Exception) {
        true
    }

    private fun status(message: String) {
        val b = message.toByteArray(Charsets.UTF_8)
        onTransportInput(b, 0, b.size)
    }
}
