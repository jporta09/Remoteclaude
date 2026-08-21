package com.remoteclaude.app

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Pantalla de diagnóstico (F8): muestra el ring buffer de [Diagnostico] —hitos de conexión,
 * reconexión, auth, clave del host, caídas— más reciente arriba, y se actualiza en vivo.
 *
 * Es para responder "¿qué pasó recién con la conexión?" sin cable ni logcat: se llega con un
 * toque largo en la barra de host. "Compartir" vuelca el texto por el share sheet (para pegarlo
 * en un reporte); "Limpiar" vacía el buffer. No guarda nada en disco ni incluye contenido de la
 * terminal ni claves.
 */
class DiagnosticoActivity : AppCompatActivity() {

    private lateinit var lista: LinearLayout
    private lateinit var vacio: TextView
    private val oyente = { runOnUiThread { pintar() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Paleta.KEYPAD_BG)
        }
        raiz.addView(barra())

        vacio = TextView(this).apply {
            text = "Todavía no hay eventos de conexión.\nAparecen acá cuando la app conecta, " +
                "reconecta o algo falla."
            setTextColor(Paleta.CHEV_FG)
            typeface = fuenteDetalle()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        lista = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(16))
        }
        raiz.addView(
            ScrollView(this).apply { addView(lista) },
            LinearLayout.LayoutParams(Paleta.MATCH, 0, 1f),
        )
        raiz.addView(acciones())

        setContentView(raiz)
        pintar()
    }

    override fun onStart() {
        super.onStart()
        Diagnostico.escuchar(oyente)
        pintar()
    }

    override fun onStop() {
        Diagnostico.dejarDeEscuchar(oyente)
        super.onStop()
    }

    private fun barra(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Paleta.CHEV_BG)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        contentDescription = "Volver"
        setOnClickListener { finish() }
        addView(TextView(this@DiagnosticoActivity).apply {
            text = "‹  Diagnóstico"
            setTextColor(Paleta.ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
        })
    }

    private fun acciones(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setBackgroundColor(Paleta.CHEV_BG)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        fun boton(txt: String, cd: String, onClick: () -> Unit) = TextView(this@DiagnosticoActivity).apply {
            text = txt
            contentDescription = cd
            setTextColor(Paleta.KEY_FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnClickListener { onClick() }
        }
        addView(boton("Limpiar", "Vaciar el diagnóstico") {
            // Confirmar: "Limpiar" borra el timeline (incluidos los eventos persistidos ya cargados),
            // y sin confirmación un toque accidental se lleva la evidencia justo cuando la mirás.
            AlertDialog.Builder(this@DiagnosticoActivity)
                .setTitle("¿Vaciar el diagnóstico?")
                .setMessage("Se borran los eventos de conexión de esta pantalla.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Vaciar") { _, _ -> Diagnostico.limpiar() }
                .show()
        })
        addView(boton("Compartir", "Compartir el diagnóstico") {
            compartir()
        })
    }

    private fun compartir() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "RemoteMarvin — diagnóstico")
            putExtra(Intent.EXTRA_TEXT, Diagnostico.exportarTexto())
        }
        startActivity(Intent.createChooser(intent, "Compartir diagnóstico"))
    }

    private fun pintar() {
        val eventos = Diagnostico.instantanea()
        lista.removeAllViews()
        if (eventos.isEmpty()) {
            lista.addView(vacio)
            return
        }
        val hora = DateFormat.getTimeFormat(this)
        // más reciente arriba
        for (e in eventos.asReversed()) {
            lista.addView(fila(hora.format(java.util.Date(e.epochMs)), e))
        }
    }

    private fun fila(hhmmss: String, e: Diagnostico.Evento): View {
        val color = when (e.nivel) {
            Diagnostico.Nivel.ERROR -> Paleta.REC_FG
            Diagnostico.Nivel.AVISO -> getColor(R.color.marvin_amber)
            Diagnostico.Nivel.INFO -> Paleta.CHEV_FG
        }
        return TextView(this).apply {
            text = "$hhmmss  ${e.categoria}: ${e.detalle}"
            setTextColor(color)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, dp(4))
            setTextIsSelectable(true)
        }
    }
}
