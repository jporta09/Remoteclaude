package com.remoteclaude.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

/** Pantalla de carga: isologo de Marvin sobre petróleo, breve, y luego al menú de hosts. */
class SplashActivity : AppCompatActivity() {

    // El timer se guarda para poder cancelarlo: si no, al rotar (o al salir con back)
    // durante el splash el runnable de la instancia destruida igual dispara y apila una
    // segunda HostsActivity, o reabre la pantalla que el usuario acababa de cerrar.
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(getColor(R.color.marvin_petrol))
        }
        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.marvin_isologo)
            adjustViewBounds = true
        }, LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.78).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        handler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            startActivity(Intent(this, HostsActivity::class.java))
            finish()
        }, 1300)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
