package com.remoteclaude.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
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
 * Es best-effort, atado a la conexión viva (igual que el terminal): si el proceso muere en
 * background no llega —el techo conocido, que cerraría un foreground service/FCM—. Señal PRECISA
 * (host-side, sin parsear la pantalla ni los 6 s importan acá); token-free (el hook es shell local).
 * Complementa al "modo lectura" del [TerminalClients], que baja el teclado al instante en foreground.
 */
class NotificacionesRemotas(
    private val act: AppCompatActivity,
    private val host: String,
    private val port: Int,
    private val user: String,
    private val key: KeyPair,
    private val enPrimerPlano: () -> Boolean,
) {
    @Volatile private var corriendo = false
    private var hilo: Thread? = null
    private val notifManager get() = act.getSystemService(NotificationManager::class.java)

    fun iniciar() {
        if (corriendo) return
        corriendo = true
        hilo = Thread({ bucle() }, "notifs-remotas").apply { isDaemon = true; start() }
    }

    fun detener() {
        corriendo = false
        hilo?.interrupt()
        hilo = null
    }

    /** La app volvió a primer plano: bajar el aviso (ya estás mirando). */
    fun alPrimerPlano() = cancelarAviso()

    // Reconecta con backoff mientras `corriendo`. Cada conexión abre UN canal exec que tailea
    // el archivo de notificaciones y bloquea leyendo líneas hasta que se cae.
    private fun bucle() {
        var backoffMs = 2_000L
        while (corriendo) {
            var c: Connection? = null
            try {
                val (h, p) = TailscaleBridge.endpoint(host, port)
                val conn = Connection(h, p).also { c = it }
                conn.connect(HostKeys.verifier(act.applicationContext, host, port), 10_000, 10_000)
                if (!conn.authenticateWithPublicKey(user, key)) { esperar(backoffMs); continue }
                val s = conn.openSession()
                // -n0: arrancar en el final (no re-emitir viejas al reconectar). Se pierde lo escrito
                // mientras estábamos caídos: best-effort, aceptado.
                s.execCommand(
                    "mkdir -p ~/.config/marvin && touch ~/.config/marvin/notify.jsonl && " +
                        "exec tail -n0 -F ~/.config/marvin/notify.jsonl",
                )
                backoffMs = 2_000L   // conexión sana: resetear el backoff
                val r = BufferedReader(InputStreamReader(s.stdout, Charsets.UTF_8))
                while (corriendo) {
                    val linea = r.readLine() ?: break   // EOF = se cayó el canal
                    procesar(linea)
                }
                s.close()
            } catch (_: InterruptedException) {
                break
            } catch (_: Exception) {
                // caída de red / auth / endpoint: reconectar con backoff
            } finally {
                try { c?.close() } catch (_: Exception) {}
            }
            if (corriendo) { esperar(backoffMs); backoffMs = (backoffMs * 2).coerceAtMost(30_000L) }
        }
    }

    private fun esperar(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}

    private fun procesar(linea: String) {
        val obj = try { JSONObject(linea) } catch (_: Exception) { return }
        if (obj.optString("type") != "permission_prompt") return
        // Si estás mirando la app, el "modo lectura" ya bajó el teclado: no molestamos con notif.
        if (enPrimerPlano()) return
        val msg = obj.optString("message").ifBlank { "Claude está esperando una decisión" }
        notificar(msg)
    }

    // --- notificación (misma plomería que tenía F7) -------------------------------------------

    private fun puedeNotificar(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            act.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun asegurarCanal() {
        val canal = NotificationChannel(
            CANAL, "Claude te espera", NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Avisa cuando Claude queda esperando que decidas algo y la app no está abierta."
        }
        notifManager?.createNotificationChannel(canal)
    }

    private fun notificar(mensaje: String) {
        if (!puedeNotificar()) return   // sin permiso, degrada en silencio
        asegurarCanal()
        // singleTop + REORDER_TO_FRONT: trae la MainActivity viva al frente (con su sesión).
        val intent = Intent(act, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        val pi = PendingIntent.getActivity(
            act, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(act, CANAL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Claude te espera")
            .setContentText(mensaje)
            .setStyle(Notification.BigTextStyle().bigText(mensaje))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        notifManager?.notify(NOTIF_ID, n)
    }

    private fun cancelarAviso() {
        try { notifManager?.cancel(NOTIF_ID) } catch (_: Exception) {}
    }

    companion object {
        private const val CANAL = "claude-espera"
        private const val NOTIF_ID = 4207
    }
}
