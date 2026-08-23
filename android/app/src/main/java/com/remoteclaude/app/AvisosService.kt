package com.remoteclaude.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * Foreground service que mantiene VIVO el canal de avisos ([NotificacionesRemotas]) mientras el
 * host tiene "Avisos en segundo plano" activado. Sin esto, con la app en background/cerrada Android
 * estrangula el hilo del `tail -F` y la notif "Claude te espera" sólo llega al VOLVER (inútil).
 *
 * Corre sólo mientras hay una sesión con el toggle prendido — no 24/7 — y sobrevive a mandar la app
 * atrás y a cerrarla (swipe). Se apaga desde su acción "Detener", o cuando la Activity conecta a un
 * host con el toggle apagado. Es liviano: sólo el canal de avisos (poco tráfico), no el terminal.
 */
class AvisosService : Service() {

    private var notifs: NotificacionesRemotas? = null
    private var hostActual: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_DETENER) {
            // Si lo paraste VOS (botón "Detener" de la notif), recordalo: así recrear la Activity
            // (rotación) NO revive el service con el toggle ON. Se re-arma en el próximo arranque
            // fresco de la app (ver AvisosService.rearmar en MainActivity.onCreate). Si en cambio lo
            // paró la app (toggle off / cambio de host), NO se marca.
            if (intent.getBooleanExtra("porUsuario", false)) marcarDetenido(true)
            detenerTodo()
            return START_NOT_STICKY
        }
        val host = intent?.getStringExtra("hostname")
        val user = intent?.getStringExtra("user")
        if (host == null || user == null) {
            detenerTodo()
            return START_NOT_STICKY
        }
        val port = intent.getIntExtra("port", 22)
        val etiqueta = intent.getStringExtra("label") ?: host

        arrancarEnPrimerPlano(etiqueta)

        // Reconstruir el tail si es la primera vez O si cambió el host (antes el guard `notifs == null`
        // dejaba el tail en el host viejo mientras el texto de la FGS ya decía el nuevo: escuchaba mal).
        if (notifs == null || hostActual != host) {
            notifs?.detener()
            hostActual = host
            notifs = NotificacionesRemotas(
                ctx = applicationContext,
                host = host, port = port, user = user,
                key = KeyStoreSsh.getOrCreateKeyPair(),
                enPrimerPlano = { EstadoApp.enPrimerPlano },
                etiqueta = etiqueta,
            ).also { it.iniciar() }
        }
        // Si el proceso se mata, que reintente con el mismo intent (recupera host/port/user).
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        notifs?.detener()
        notifs = null
        hostActual = null
        super.onDestroy()
    }

    private fun detenerTodo() {
        notifs?.detener()
        notifs = null
        hostActual = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun marcarDetenido(v: Boolean) =
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_DETENIDO, v).apply()

    private fun arrancarEnPrimerPlano(etiqueta: String) {
        asegurarCanal()
        val abrir = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val detener = PendingIntent.getService(
            this, 2,
            Intent(this, AvisosService::class.java).setAction(ACCION_DETENER)
                .putExtra("porUsuario", true),   // lo apretaste VOS -> queda "detenido" hasta re-armar
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CANAL_FG)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("RemoteMarvin — avisos activos")
            .setContentText("Escuchando avisos de Claude en $etiqueta")
            .setContentIntent(abrir)
            .addAction(Notification.Action.Builder(null, "Detener", detener).build())
            .setOngoing(true)
            .build()
        // FORWARD-LOOKING (5d, §G): un FGS `dataSync` en Android 15 (API 35) tiene un CAP de ~6h/día —
        // el sistema lo mata con Service.onTimeout(). HOY estamos EXENTOS porque targetSdk = 34 (el cap
        // aplica sólo con targetSdk ≥ 35). No se puede manejar todavía: `Service.onTimeout` no existe en
        // el SDK 34, así que overridearlo no compila.
        // AL SUBIR targetSdk a 35+: (a) overridear `onTimeout(startId, fgsType)` para parar limpio y
        // AVISAR ("los avisos en segundo plano se detuvieron —límite del sistema—, reabrí la app"), no
        // morir en silencio; (b) NO agregar un receiver de RECEIVE_BOOT_COMPLETED para auto-arrancar tras
        // reboot —arrancar un FGS sin que el usuario abra la app es agresivo y necesita reconstruir host/
        // clave; el modelo es que el usuario reabre la app y reconecta— salvo que se decida lo contrario.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_FG_ID, n)
        }
    }

    private fun asegurarCanal() {
        val canal = NotificationChannel(
            CANAL_FG, "Avisos en segundo plano", NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Mantiene vivos los avisos de Claude cuando la app no está abierta." }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(canal)
    }

    companion object {
        private const val CANAL_FG = "avisos-fg"
        private const val NOTIF_FG_ID = 4208
        private const val ACCION_DETENER = "com.remoteclaude.app.DETENER_AVISOS"
        private const val PREFS = "avisos"
        private const val KEY_DETENIDO = "detenido"

        /** ¿El usuario paró los avisos con "Detener"? (para no revivir el FGS al recrear la Activity). */
        fun detenidoPorUsuario(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DETENIDO, false)

        /** Re-arma los avisos (los des-"detiene"): se llama en el arranque FRESCO de la app, así un
         *  "Detener" no queda pegado para siempre — abrir la app de nuevo vuelve a habilitarlos. */
        fun rearmar(ctx: Context) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DETENIDO, false).apply()

        /** Prende el service para un host (idempotente). */
        fun iniciar(ctx: Context, host: String, port: Int, user: String, label: String) {
            val i = Intent(ctx, AvisosService::class.java)
                .putExtra("hostname", host).putExtra("port", port)
                .putExtra("user", user).putExtra("label", label)
            ContextCompat.startForegroundService(ctx, i)
        }

        /** Apaga el service (si estaba corriendo). */
        fun detener(ctx: Context) {
            ctx.startService(Intent(ctx, AvisosService::class.java).setAction(ACCION_DETENER))
        }
    }
}
