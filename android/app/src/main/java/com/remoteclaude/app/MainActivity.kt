package com.remoteclaude.app

import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * M0: esqueleto mínimo para validar el pipeline de build (APK).
 * En M1 entra el TerminalView de Termux; en M2 el transporte SSH.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = "Remoteclaude\nM0 — esqueleto que compila ✓"
            textSize = 20f
            gravity = Gravity.CENTER
        }
        setContentView(tv)
    }
}
