package com.remoteclaude.app

import android.app.Activity
import android.content.Context
import android.widget.Toast
import com.journeyapps.barcodescanner.ScanOptions
import java.util.concurrent.atomic.AtomicBoolean
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
                Diagnostico.registrar(Diagnostico.Nivel.INFO, "tailscale", "re-enrol iniciado (auth key nueva)")
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
        // Un solo sondeo vivo por vez: N toques del ↺ lanzaban N hilos y N toasts (DEV-N5).
        if (!sondeando.compareAndSet(false, true)) return
        thread(name = "ts-identidad") {
            try { sondearIdentidad(activity) } finally { sondeando.set(false) }
        }
    }

    private val sondeando = AtomicBoolean(false)

    private fun sondearIdentidad(activity: Activity) {
            val t0 = android.os.SystemClock.elapsedRealtime()
            while (android.os.SystemClock.elapsedRealtime() - t0 < VENTANA_IDENTIDAD_MS) {
                if (TailscaleBridge.isReady()) {
                    val red = TailscaleBridge.identidadRed()
                    if (red.isNotBlank()) {
                        // La identidad de red queda en Diagnóstico, no sólo en un toast de 3,5 s
                        // (UX5-5/A5-5: consentimiento con información que se evaporaba).
                        Diagnostico.registrar(Diagnostico.Nivel.AVISO, "tailscale", "re-vinculado a la tailnet: $red")
                        activity.runOnUiThread {
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                Toast.makeText(activity, "Vinculado a la tailnet: $red", Toast.LENGTH_LONG).show()
                            }
                        }
                        return
                    }
                    // Listo pero todavía sin identidad (backend en "Starting": CurrentTailnet y
                    // DNSName vacíos): seguir sondeando en vez de rendirse. Antes se cortaba acá
                    // y el toast se perdía en silencio — observado en el emulador con ráfaga de
                    // 60 frames: salía "Re-vinculando…" y nunca la identidad.
                }
                try { Thread.sleep(1000) } catch (_: InterruptedException) { return }
            }
            // Tope: no quedar "Re-vinculando…" para siempre (DEV-N3). Se dice y se registra.
            Diagnostico.registrar(
                Diagnostico.Nivel.AVISO, "tailscale",
                "re-vinculación sin confirmar tras ${VENTANA_IDENTIDAD_MS / 1000} s (backend: ${TailscaleBridge.backendState()})",
            )
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    Toast.makeText(activity, "No pude confirmar la tailnet todavía — mirá ⓘ Diagnóstico", Toast.LENGTH_LONG).show()
                }
            }
    }

    private const val VENTANA_IDENTIDAD_MS = 30_000L

    /**
     * Forma mínima esperada de una AUTH key de Tailscale: prefijo `tskey-auth-` (un `tskey-api-` o
     * `tskey-client-` son otros secretos y nunca enrolan: QA5-2), sin espacios ni caracteres de
     * control/invisibles (NUL, ZWSP, ESC…), largo acotado. No valida contra la tailnet —eso lo
     * hace el Up—, sólo descarta un QR truncado/ajeno antes de reiniciar el nodo con basura.
     */
    internal fun esAuthKeyPlausible(key: String): Boolean =
        key.startsWith("tskey-auth-") && key.length in 24..200 &&
            key.none { it.isWhitespace() || it.isISOControl() || it == '\u200B' || it == '\uFEFF' }
}
