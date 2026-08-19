package com.remoteclaude.app

import android.os.Bundle
import android.view.WindowManager
import com.journeyapps.barcodescanner.CaptureActivity

/**
 * CaptureActivity de ZXing forzada a vertical (la default abre apaisada). Se declara en el
 * manifest con screenOrientation=sensorPortrait y se referencia desde ScanOptions.
 *
 * FLAG_SECURE: el QR que se escanea ES la auth key de Tailscale (un secreto de un solo uso) y
 * aparece en el preview de la cámara; con FLAG_SECURE no queda en capturas de pantalla ni en el
 * thumbnail del task-switcher. Es la única pantalla con FLAG_SECURE: en la terminal/visor/docs se
 * dejó libre a propósito para poder sacar capturas (reportes de bug).
 */
class PortraitCaptureActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
    }
}
