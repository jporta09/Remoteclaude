package com.remoteclaude.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Pantalla del benchmark VISIBLE de STT (sourceset debug: nunca llega al APK de release): cronómetro gigante,
 * estado y transcripción en vivo, para grabar la corrida con screenrecord y que el
 * usuario verifique los tiempos con sus propios ojos. La maneja [SttBenchmarkTest].
 */
class BenchActivity : Activity() {

    lateinit var titulo: TextView
    lateinit var timer: TextView
    lateinit var estado: TextView
    lateinit var texto: TextView
    private val main = Handler(Looper.getMainLooper())
    private var base = 0L
    private var corriendo = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(15, 35, 45))   // petrol Marvin
            setPadding(48, 96, 48, 48)
        }
        titulo = TextView(this).apply {
            setTextColor(Color.rgb(113, 191, 68)); textSize = 26f
            typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
        }
        timer = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 96f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER; text = "0.0 s"
        }
        estado = TextView(this).apply {
            setTextColor(Color.rgb(253, 185, 64)); textSize = 20f
            typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
        }
        texto = TextView(this).apply {
            setTextColor(Color.rgb(242, 242, 242)); textSize = 17f
            typeface = Typeface.MONOSPACE; setPadding(0, 48, 0, 0)
        }
        raiz.addView(titulo)
        raiz.addView(timer)
        raiz.addView(estado)
        raiz.addView(ScrollView(this).also { it.addView(texto) })
        setContentView(raiz)
    }

    fun poner(tit: String? = null, est: String? = null, txt: String? = null) = main.post {
        tit?.let { titulo.text = it }
        est?.let { estado.text = it }
        txt?.let { texto.text = it }
    }

    /** Arranca el cronómetro grande desde cero (se actualiza cada 50 ms). */
    fun arrancarTimer() = main.post {
        base = SystemClock.elapsedRealtime()
        corriendo = true
        tick()
    }

    /** Congela el cronómetro y devuelve los segundos transcurridos. */
    fun frenarTimer(): Double {
        val seg = (SystemClock.elapsedRealtime() - base) / 1000.0
        main.post { corriendo = false; timer.text = "%.1f s".format(seg) }
        return seg
    }

    private fun tick() {
        if (!corriendo) return
        timer.text = "%.1f s".format((SystemClock.elapsedRealtime() - base) / 1000.0)
        main.postDelayed({ tick() }, 50)
    }
}
