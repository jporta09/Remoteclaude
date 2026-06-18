package com.remoteclaude.app

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * CaptureActivity de ZXing forzada a vertical (la default abre apaisada). Se declara en el
 * manifest con screenOrientation=sensorPortrait y se referencia desde ScanOptions.
 */
class PortraitCaptureActivity : CaptureActivity()
