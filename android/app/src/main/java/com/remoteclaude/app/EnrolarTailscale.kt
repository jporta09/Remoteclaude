package com.remoteclaude.app

import android.app.Activity
import android.content.Context
import android.widget.Toast
import com.journeyapps.barcodescanner.ScanOptions
import kotlin.concurrent.thread

/**
 * Enrolar (o re-enrolar) el nodo Tailscale embebido escaneando el QR de `ts-link-qr`.
 *
 * Vive acá y no en una Activity porque el re-enrol entra por TRES caminos: el QR desde la
 * pantalla de hosts (diálogo de la VPN), el QR desde la barra de la terminal cuando el acceso
 * venció (↺), y la key pegada a mano en el diálogo de hosts. Los tres usan la misma
 * validación/aplicación de la key y el mismo feedback de re-vinculación (A4-1).
 */
object EnrolarTailscale {

    /** Opciones del scanner (vertical, sin beep, con la capture activity FLAG_SECURE). */
    fun opciones(): ScanOptions = ScanOptions().apply {
        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        setPrompt("Apuntá al QR de la PC (ts-link-qr)")
        setBeepEnabled(false)
        setOrientationLocked(false)
        setCaptureActivity(PortraitCaptureActivity::class.java)
    }

    /**
     * Valida el contenido escaneado y, si es una auth key, reconfigura el nodo embebido.
     * Devuelve true si se aplicó (el caller decide qué refrescar después). null/vacío =
     * escaneo cancelado: silencio.
     */
    fun aplicar(ctx: Context, contents: String?): Boolean {
        val key = contents?.trim().orEmpty()
        return when {
            key.isEmpty() -> false
            esAuthKeyPlausible(key) -> {
                TailscaleBridge.configure(ctx, key)
                // A4-1: el feedback del re-enrol va ACÁ, en el funnel común, y no en cada
                // caller: así lo dan los tres caminos. Antes vivía sólo en el callback del
                // scanner de la terminal, y pegar la key (o escanear desde hosts) reconfiguraba
                // la identidad de red sin ningún aviso — hallazgo de la validación en vivo de
                // v1.31.0 (S23 por USB + emulador).
                if (ctx is Activity) anunciarVinculacion(ctx)
                true
            }
            else -> {
                Toast.makeText(ctx, "No es una auth key de Tailscale válida", Toast.LENGTH_LONG).show()
                false
            }
        }
    }

    /**
     * A4-1: feedback de la re-vinculación, común a los tres caminos de re-enrol.
     *  1) "Re-vinculando…" ya, para que se note que el toque hizo algo.
     *  2) Cuando el nodo levante, a qué tailnet te vinculaste (el re-enrol reconfigura la
     *     identidad de red; sin esto es consentimiento sin información, ASI09). Corre en un
     *     hilo porque isReady()/identidadRed() tocan el estado del nodo.
     * Ventana de 30s: la reconexión desde vencido tarda ~6-8s en el celu pero ~20s en el
     * emulador, y con 20s el sondeo expiraba justo cuando el nodo quedaba listo (observado).
     */
    fun anunciarVinculacion(activity: Activity) {
        Toast.makeText(activity, "Re-vinculando Tailscale…", Toast.LENGTH_SHORT).show()
        thread(name = "ts-identidad") {
            val t0 = android.os.SystemClock.elapsedRealtime()
            while (android.os.SystemClock.elapsedRealtime() - t0 < VENTANA_IDENTIDAD_MS) {
                if (TailscaleBridge.isReady()) {
                    val red = TailscaleBridge.identidadRed()
                    if (red.isNotBlank()) {
                        activity.runOnUiThread {
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                Toast.makeText(activity, "Vinculado a la tailnet: $red", Toast.LENGTH_LONG).show()
                            }
                        }
                        return@thread
                    }
                    // Listo pero todavía sin identidad (backend en "Starting": CurrentTailnet y
                    // DNSName vacíos): seguir sondeando en vez de rendirse. Antes se cortaba acá
                    // y el toast se perdía en silencio — observado en el emulador con ráfaga de
                    // 60 frames: salía "Re-vinculando…" y nunca la identidad.
                }
                try { Thread.sleep(1000) } catch (_: InterruptedException) { return@thread }
            }
        }
    }

    private const val VENTANA_IDENTIDAD_MS = 30_000L

    /**
     * Forma mínima esperada de una auth key de Tailscale: prefijo `tskey-`, sin espacios, y un
     * largo razonable (una key real ronda 50+ caracteres). No valida contra la tailnet —eso lo
     * hace el Up—, sólo descarta un QR truncado/ajeno antes de reiniciar el nodo con basura
     * (QA4-2: antes se aceptaba cualquier string con el prefijo).
     */
    private fun esAuthKeyPlausible(key: String): Boolean =
        key.startsWith("tskey-") && key.length >= 24 && key.none { it.isWhitespace() }
}
