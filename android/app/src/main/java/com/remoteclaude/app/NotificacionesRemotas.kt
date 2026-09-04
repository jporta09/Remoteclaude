package com.remoteclaude.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.trilead.ssh2.Connection
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.KeyPair
import org.json.JSONObject

/**
 * Alerta de "Claude te espera" originada en el HOST, no en el buffer.
 *
 * Claude Code emite un hook `Notification` con `notification_type: "permission_prompt"` cuando
 * queda esperando que apruebes una herramienta (con un debounce de ~6 s: "parece que te fuiste").
 * El hook del plugin (`marvin-notify.sh`) appendea una línea JSON a `~/.config/marvin/notify.jsonl`;
 * acá abrimos un canal SSH persistente que `tail -F`ea ese archivo y, ante un `permission_prompt`
 * con la app en segundo plano, postea una notificación Android.
 *
 * Corre en UNO de dos lugares, nunca los dos a la vez (diseño ADITIVO):
 *  - Por defecto (toggle "Avisos en segundo plano" OFF): la lanza la [MainActivity] mientras la app
 *    vive. Es **best-effort** — en background Android puede estrangular el hilo o cortar la conexión,
 *    así que el aviso puede demorar o perderse (el techo conocido).
 *  - Con el toggle ON: la hostea [AvisosService] (foreground service), así el canal sobrevive a que la
 *    app esté en background o cerrada y la notif llega en tiempo real. En ese caso la Activity NO la
 *    lanza (para no duplicar el tail).
 * Señal PRECISA (host-side, sin parsear la pantalla); token-free (el hook es shell local). Complementa
 * al "modo lectura" del [TerminalClients], que baja el teclado al instante en foreground.
 */
class NotificacionesRemotas(
    private val ctx: Context,
    private val host: String,
    private val port: Int,
    private val user: String,
    private val key: KeyPair,
    private val enPrimerPlano: () -> Boolean,
    private val etiqueta: String = host,
    // Sesiones tmux que son PESTAÑAS de la app en este host. El hook del host escribe por CUALQUIER
    // Claude del config (uno lanzado en un tmux ajeno también aparece en notify.jsonl); notificar
    // eso confunde ("¿de dónde vino esto?"). Sólo avisamos por lo que la app maneja.
    private val sesionesDeLaApp: () -> Set<String> = { emptySet() },
) {
    @Volatile private var corriendo = false
    private var hilo: Thread? = null
    private val notifManager get() = ctx.getSystemService(NotificationManager::class.java)

    // Cursor por timestamp: última `ts` (epoch en segundos) que ya procesamos, persistida por host.
    // -1 = "sin sembrar" (primer arranque): se siembra con la última línea del archivo para no
    // re-emitir el historial. Con el cursor, al reconectar recuperamos el hueco (releemos y filtramos
    // por ts > cursor) y la rotación (`mv` + relectura de 200 viejas) no re-emite nada.
    @Volatile private var cursor: Long = -1L

    private fun prefs() = ctx.getSharedPreferences("avisos", Context.MODE_PRIVATE)
    private fun cargarCursor(): Long = prefs().getLong("cursor_ts_$host", -1L)
    private fun guardarCursor(ts: Long) = prefs().edit().putLong("cursor_ts_$host", ts).apply()

    fun iniciar() {
        if (corriendo) return
        corriendo = true
        cursor = cargarCursor()
        hilo = Thread({ bucle() }, "notifs-remotas").apply { isDaemon = true; start() }
    }

    fun detener() {
        corriendo = false
        hilo?.interrupt()
        hilo = null
    }

    // Reconecta con backoff mientras `corriendo`. Cada canal exec tailea
    // el archivo de notificaciones y bloquea leyendo líneas hasta que se cae.
    private fun bucle() {
        var backoffMs = 2_000L
        var fallosSeguidos = 0
        var estabaConectado = false
        // Registrar sólo TRANSICIONES (conectado <-> caído) y un latido cada 10 reintentos: bajo un
        // nodo vencido esto escribía "canal caído, reintento" cada 30 s durante horas y empujaba el
        // histórico útil fuera del Diagnóstico (SRE-5-3, 21 de 27 filas en 12 min).
        fun caido() {
            fallosSeguidos++
            if (estabaConectado) {
                estabaConectado = false
                Diagnostico.registrar(Diagnostico.Nivel.AVISO, "avisos", "canal caído ($etiqueta), reintentando")
            } else if (fallosSeguidos % 10 == 0) {
                Diagnostico.registrar(Diagnostico.Nivel.AVISO, "avisos", "canal sigue caído ($etiqueta): $fallosSeguidos reintentos")
            }
        }
        while (corriendo) {
            var c: Connection? = null
            try {
                val (h, p) = TailscaleBridge.endpoint(host, port)
                val conn = Connection(h, p).also { c = it }
                conn.connect(HostKeys.verifier(ctx.applicationContext, host, port), 10_000, 10_000)
                if (!conn.authenticateWithPublicKey(user, key)) {
                    caido(); esperar(backoffMs); backoffMs = proximoBackoff(backoffMs, fallosSeguidos, Math.random()); continue
                }
                // Primer arranque: sembrar el cursor con la última línea (no re-emitir el historial).
                if (cursor < 0L) { cursor = semillaCursor(conn); guardarCursor(cursor) }
                val s = conn.openSession()
                // Releemos las últimas 200 y seguimos; `procesar` filtra por ts > cursor. Al reconectar
                // esto RECUPERA lo escrito mientras estábamos caídos (antes se perdía con -n0), y tras la
                // rotación (`mv`) el cursor saltea las 200 viejas re-leídas: sin re-emitir.
                s.execCommand(
                    "mkdir -p ~/.config/marvin && touch ~/.config/marvin/notify.jsonl && " +
                        "exec tail -n 200 -F ~/.config/marvin/notify.jsonl",
                )
                backoffMs = 2_000L   // conexión sana: resetear el backoff
                fallosSeguidos = 0
                if (!estabaConectado) {
                    estabaConectado = true
                    Diagnostico.registrar(Diagnostico.Nivel.INFO, "avisos", "canal conectado ($etiqueta)")
                }
                val r = BufferedReader(InputStreamReader(s.stdout, Charsets.UTF_8))
                while (corriendo) {
                    val linea = r.readLine() ?: break   // EOF = se cayó el canal
                    procesar(linea)
                }
                s.close()
                if (corriendo) caido()
            } catch (_: InterruptedException) {
                break
            } catch (_: Exception) {
                // caída de red / auth / endpoint: reconectar con backoff
                if (corriendo) caido()
            } finally {
                try { c?.close() } catch (_: Exception) {}
            }
            if (corriendo) { esperar(backoffMs); backoffMs = proximoBackoff(backoffMs, fallosSeguidos, Math.random()) }
        }
    }

    private fun esperar(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}

    // Lee la última línea del archivo para inicializar el cursor sin re-emitir el historial.
    private fun semillaCursor(conn: Connection): Long = try {
        val r = SshExec.leer(conn, "tail -n1 ~/.config/marvin/notify.jsonl 2>/dev/null", timeoutMs = 5_000, maxBytes = 65_536)
        val linea = r.salida.lineSequence().firstOrNull { it.isNotBlank() }
        linea?.let { try { JSONObject(it).optLong("ts", 0L) } catch (_: Exception) { 0L } } ?: 0L
    } catch (_: Exception) { 0L }

    private fun procesar(linea: String) {
        val obj = try { JSONObject(linea) } catch (_: Exception) { return }
        if (obj.optString("type") != "permission_prompt") return
        // Cursor por ts: descartar lo ya visto (historial al reconectar, relectura tras rotación).
        val ts = obj.optLong("ts", 0L)
        if (ts <= cursor) return
        cursor = ts
        guardarCursor(ts)
        Diagnostico.registrar(Diagnostico.Nivel.INFO, "avisos", "aviso recibido ($etiqueta)")
        // Si estás mirando la app, el "modo lectura" ya bajó el teclado: no molestamos con notif
        // (pero el cursor ya avanzó: no se re-notifica después).
        if (enPrimerPlano()) return
        val msg = obj.optString("message").ifBlank { "Claude está esperando una decisión" }
        val sesion = obj.optString("session")
        // Sólo sesiones DE LA APP. `session` viene del hook (`tmux display-message '#S'`); las pestañas
        // de la app SIEMPRE corren dentro de tmux con su nombre ("term N" o renombrada), así que un
        // aviso sin sesión (Claude fuera de tmux) o con una sesión que no es pestaña es de un Claude
        // ajeno: se descarta (el cursor ya avanzó, no se re-emite).
        if (sesion.isBlank() || sesion !in sesionesDeLaApp()) {
            Diagnostico.registrar(
                Diagnostico.Nivel.INFO, "avisos",
                "aviso ignorado (sesión ajena: ${sesion.ifBlank { "sin tmux" }})",
            )
            return
        }
        notificar(msg, sesion)
    }

    // --- notificación (misma plomería que tenía F7) -------------------------------------------

    private fun puedeNotificar(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun asegurarCanal() {
        val canal = NotificationChannel(
            CANAL, "Claude te espera", NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Avisa cuando Claude queda esperando que decidas algo y la app no está abierta."
        }
        notifManager?.createNotificationChannel(canal)
    }

    private fun notificar(mensaje: String, sesion: String = "") {
        if (!puedeNotificar()) return   // sin permiso, degrada en silencio
        asegurarCanal()
        // singleTop + REORDER_TO_FRONT: trae la MainActivity viva al frente (con su sesión).
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Identidad: de qué host (y sesión, si el hook la manda) viene el aviso — así dos Claude
        // esperando no colapsan en una notif ambigua.
        val subtexto = if (sesion.isNotBlank()) "$etiqueta · $sesion" else etiqueta
        val n = Notification.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Claude te espera")
            .setContentText(mensaje)
            .setSubText(subtexto)
            .setStyle(Notification.BigTextStyle().bigText(mensaje).setSummaryText(subtexto))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        notifManager?.notify(NOTIF_ID, n)
    }

    companion object {
        private const val CANAL = "claude-espera"
        private const val NOTIF_ID = 4207

        /** Baja la notif "Claude te espera" desde afuera (p.ej. la Activity al volver a primer plano),
         *  sin tener la instancia: el canal lo mantiene [AvisosService] en otro componente. */
        fun cancelar(ctx: Context) {
            try {
                ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
            } catch (_: Exception) {}
        }
    }
}
