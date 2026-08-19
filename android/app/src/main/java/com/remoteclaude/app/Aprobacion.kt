package com.remoteclaude.app

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * WS-F — Pantalla de aprobación asistida.
 *
 * En el celular, el prompt de aprobación de Claude Code se aprueba casi a ciegas: con el
 * teclado arriba el diff se empuja fuera de la pantalla y sólo quedan visibles/tappables las
 * opciones "1. Sí / 2. No" (hallazgo ASI09). Esto lee el prompt del buffer de la terminal
 * —que SÍ captura la pantalla alternada del modo fullscreen (spike confirmado)— y muestra una
 * hoja nativa con el comando/diff scrollable y botones grandes que inyectan la elección.
 */

/** Parser PURO del prompt de aprobación (testeable en la JVM, sin Android). */
object AprobacionParser {

    data class Opcion(val numero: Int, val etiqueta: String)

    data class Prompt(
        val pregunta: String,
        val contexto: String,
        val opciones: List<Opcion>,
    ) {
        /** Identidad del prompt: si no cambia, es el mismo (no re-mostrar la hoja). */
        val firma: String
            get() = pregunta + "" + opciones.joinToString("") { "${it.numero}.${it.etiqueta}" }
    }

    // "❯ 1. Yes" / "  2. Yes, and don't ask again" — el cursor ❯/> es opcional.
    private val opcionRe = Regex("""^\s*[❯>]?\s*(\d+)\.\s+(.+?)\s*$""")

    /**
     * Detecta el prompt de aprobación en el texto del buffer, o null si no hay uno.
     * Ancla en la última línea "Do you want ..." (así arrancan todos los prompts de Claude:
     * "Do you want to proceed?", "Do you want to make this edit?", etc.) seguida de opciones
     * numeradas. Menos de 2 opciones = no es un select de aprobación.
     */
    fun detectar(screen: String): Prompt? {
        val lineas = screen.split("\n")
        val qIdx = lineas.indexOfLast { it.contains("Do you want", ignoreCase = true) }
        if (qIdx < 0) return null

        val opciones = mutableListOf<Opcion>()
        var i = qIdx + 1
        while (i < lineas.size && opciones.size < 4) {
            val l = lineas[i]
            val m = opcionRe.find(l)
            when {
                m != null -> opciones.add(Opcion(m.groupValues[1].toInt(), limpiar(m.groupValues[2])))
                opciones.isEmpty() -> if (!l.isBlank()) break   // sin opciones tras la pregunta
                l.isBlank() -> break                            // blanco tras las opciones = fin
                else -> {                                       // continuación (wrap) de la última
                    val last = opciones.removeAt(opciones.lastIndex)
                    opciones.add(last.copy(etiqueta = limpiar("${last.etiqueta} ${l.trim()}")))
                }
            }
            i++
        }
        if (opciones.size < 2) return null

        val desde = maxOf(0, qIdx - 60)
        val contexto = lineas.subList(desde, qIdx).joinToString("\n").trim()
        return Prompt(lineas[qIdx].trim(), contexto, opciones)
    }

    /** Saca el "(esc)" final y espacios que Claude agrega a algunas opciones. */
    private fun limpiar(s: String) = s.replace(Regex("""\s*\(esc\)\s*$"""), "").trim()
}

/**
 * Controlador de la hoja de aprobación. MainActivity lo alimenta con cada cambio de texto de
 * la terminal; él decide si hay un prompt, muestra la hoja e inyecta la elección con
 * `session.write` (la misma vía que el teclado). Todo en el hilo de UI.
 */
class AprobacionController(
    private val act: AppCompatActivity,
    private val sesionActiva: () -> SshTerminalSession?,
    private val screenText: () -> String,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: Dialog? = null
    private var firmaMostrada: String? = null
    private val revisarRunnable = Runnable { revisar() }

    /** La terminal cambió: re-evaluar (debounced) si hay/desaparece un prompt de aprobación. */
    fun alCambiarTexto() {
        handler.removeCallbacks(revisarRunnable)
        handler.postDelayed(revisarRunnable, 200)
    }

    fun cerrarYolvidar() {
        cerrar()
        firmaMostrada = null
    }

    private fun revisar() {
        if (act.isFinishing || act.isDestroyed) return
        val prompt = AprobacionParser.detectar(screenText())
        if (prompt == null) {
            // el prompt se fue (Claude siguió, o se respondió por la terminal): cerrar la hoja
            cerrar()
            firmaMostrada = null
            return
        }
        if (prompt.firma == firmaMostrada) return   // el mismo que ya está mostrado/resuelto
        mostrar(prompt)
    }

    private fun mostrar(p: AprobacionParser.Prompt) {
        cerrar()
        firmaMostrada = p.firma

        fun dp(v: Int) =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), act.resources.displayMetrics).toInt()

        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F232D"))
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        box.addView(TextView(act).apply {
            text = p.pregunta
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.parseColor("#F2F2F2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        // El comando/diff completo, monospace y SCROLLABLE (acotado en alto para dejar ver
        // los botones): lo que en la terminal quedaba empujado fuera de pantalla.
        if (p.contexto.isNotBlank()) {
            box.addView(ScrollView(act).apply {
                setPadding(0, dp(10), 0, dp(10))
                addView(TextView(act).apply {
                    text = p.contexto
                    typeface = Typeface.MONOSPACE
                    setTextColor(Color.parseColor("#C7D3D0"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextIsSelectable(true)
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))
        }
        for (op in p.opciones) {
            box.addView(Button(act).apply {
                text = "${op.numero}. ${op.etiqueta}"
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextColor(Color.parseColor("#0F232D"))
                setBackgroundColor(Color.parseColor("#71BF44"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, dp(6), 0, 0)
                layoutParams = lp
                setOnClickListener { elegir(op.numero) }
            })
        }
        // Salida: cerrar la hoja y responder a mano en la terminal.
        box.addView(TextView(act).apply {
            text = "Ver en la terminal"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#5E8B7E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(8), dp(14), dp(8), dp(6))
            setOnClickListener { cerrar() }   // firmaMostrada queda: no re-abre este mismo prompt
        })

        dialog = Dialog(act).apply {
            setContentView(ScrollView(act).apply { addView(box) })
            setOnDismissListener { if (dialog === this) dialog = null }
            window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor("#0F232D")))
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.BOTTOM)
            }
            show()
        }
    }

    private fun elegir(numero: Int) {
        val s = sesionActiva()
        if (s != null) {
            // La misma vía que el teclado: la tecla del número selecciona la opción en el
            // select de Claude Code (igual que tocarla en el keypad de la terminal).
            val b = numero.toString().toByteArray(Charsets.UTF_8)
            s.write(b, 0, b.size)
        }
        cerrar()   // firmaMostrada queda para no re-mostrar hasta que el prompt cambie
    }

    private fun cerrar() {
        try { dialog?.dismiss() } catch (_: Exception) {}
        dialog = null
    }

    // --- test hooks ---
    @androidx.annotation.VisibleForTesting
    fun hojaVisibleParaTest() = dialog?.isShowing == true

    @androidx.annotation.VisibleForTesting
    fun elegirParaTest(numero: Int) = act.runOnUiThread { elegir(numero) }
}
