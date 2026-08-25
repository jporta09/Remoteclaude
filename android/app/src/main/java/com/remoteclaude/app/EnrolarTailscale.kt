package com.remoteclaude.app

import android.content.Context
import android.widget.Toast
import com.journeyapps.barcodescanner.ScanOptions

/**
 * Enrolar (o re-enrolar) el nodo Tailscale embebido escaneando el QR de `ts-link-qr`.
 *
 * Vive acá y no en una Activity porque ahora se escanea desde DOS lugares: la pantalla de
 * hosts (diálogo de la VPN) y la barra de la terminal cuando el acceso venció (⟲). Ambos
 * launchers usan las mismas opciones y la misma validación/aplicación de la key.
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
            key.startsWith("tskey-") -> {
                TailscaleBridge.configure(ctx, key)
                true
            }
            else -> {
                Toast.makeText(ctx, "El QR no es una auth key de Tailscale", Toast.LENGTH_LONG).show()
                false
            }
        }
    }
}
