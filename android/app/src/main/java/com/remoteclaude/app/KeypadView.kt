package com.remoteclaude.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout

/**
 * El teclado de teclas extra: la fila de Esc/Tab/Ctrl/Alt (con overflow a Home/End/PgUp/PgDn),
 * las flechas, y la fila de ⇧Shift / ⧉Sel / 🎤Dictar.
 *
 * Ctrl, Alt y Shift son MODIFICADORES pegajosos, no teclas: se tocan, quedan activos y el
 * siguiente carácter sale modificado. Por eso su estado vive acá y lo lee quien procesa las
 * teclas ([TerminalClients]), en vez de estar suelto en la activity.
 */
class KeypadView(ctx: Context, private val io: Io) : LinearLayout(ctx) {

    /** Lo que el teclado necesita del resto de la app. */
    interface Io {
        /** Tecla especial (flechas, Esc, Tab…) al motor de la terminal. */
        fun enviarTecla(keyCode: Int)

        /** Bytes crudos a la sesión activa. */
        fun escribir(bytes: ByteArray)

        /** Modo selección: el dedo marca texto en vez de scrollear. */
        fun modoSeleccion(activo: Boolean)

        /** Push-to-talk: se delega entero porque el ciclo de grabación no es del teclado. */
        fun tocaronMicrofono(ev: MotionEvent): Boolean

        /** Devolver el foco a la terminal después de cada tecla. */
        fun volverElFoco()
    }

    var ctrlActivo = false; private set
    var altActivo = false; private set
    var shiftActivo = false; private set
    private var modoSel = false
    private var overflow = false
    private var tecladoArriba = false

    private val mono: Typeface = ctx.resources.getFont(R.font.mononoki)
    private val filaSuperior = LinearLayout(ctx).apply { orientation = HORIZONTAL }
    private val filaShift = LinearLayout(ctx).apply { orientation = HORIZONTAL }
    private val chevron: Button = crearChevron()
    private var botonCtrl: Button? = null
    private var botonAlt: Button? = null
    private var botonShift: Button? = null
    private var botonSel: Button? = null
    private var botonMic: Button? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Paleta.KEYPAD_BG)

        val flechas = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        flechas.addView(teclaAncha("←") { io.enviarTecla(KeyEvent.KEYCODE_DPAD_LEFT) })
        flechas.addView(teclaAncha("↓") { io.enviarTecla(KeyEvent.KEYCODE_DPAD_DOWN) })
        flechas.addView(teclaAncha("↑") { io.enviarTecla(KeyEvent.KEYCODE_DPAD_UP) })
        flechas.addView(teclaAncha("→") { io.enviarTecla(KeyEvent.KEYCODE_DPAD_RIGHT) })

        poblarFilaSuperior()
        poblarFilaShift()

        addView(filaSuperior, LayoutParams(Paleta.MATCH, Paleta.WRAP))
        addView(flechas, LayoutParams(Paleta.MATCH, Paleta.WRAP))
        addView(filaShift, LayoutParams(Paleta.MATCH, Paleta.WRAP))
    }

    /**
     * El teclado QWERTY tapó (o liberó) la pantalla. Con el QWERTY arriba la fila de Shift se
     * esconde para no robar alto, y el Shift se suelta: si no, quedaría activo e invisible y
     * la próxima tecla saldría modificada sin que se vea por qué.
     */
    fun tecladoDelSistema(arriba: Boolean) {
        if (arriba == tecladoArriba) return
        tecladoArriba = arriba
        if (arriba) shiftActivo = false
        poblarFilaShift()
    }

    /** Suelta Ctrl y Alt: se llama después de consumir un carácter modificado. */
    fun soltarModificadores() {
        if (ctrlActivo) alternarCtrl()
        if (altActivo) alternarAlt()
    }

    /** Estado visible del botón de dictado (lo maneja [DictationController]). */
    fun estadoMicrofono(texto: String, color: Int) {
        botonMic?.let { it.text = texto; it.setTextColor(color) }
    }

    /** Tab, o Shift+Tab si el modificador está activo (y en ese caso lo suelta). */
    private fun tab() {
        if (shiftActivo) {
            io.escribir(TerminalKeys.SHIFT_TAB)
            shiftActivo = false
            botonShift?.setTextColor(Paleta.KEY_FG)
        } else {
            io.enviarTecla(KeyEvent.KEYCODE_TAB)
        }
    }

    private fun alternarCtrl() {
        ctrlActivo = !ctrlActivo
        botonCtrl?.setTextColor(if (ctrlActivo) Paleta.ACCENT else Paleta.KEY_FG)
    }

    private fun alternarAlt() {
        altActivo = !altActivo
        botonAlt?.setTextColor(if (altActivo) Paleta.ACCENT else Paleta.KEY_FG)
    }

    private fun alternarShift() {
        shiftActivo = !shiftActivo
        botonShift?.setTextColor(if (shiftActivo) Paleta.ACCENT else Paleta.KEY_FG)
    }

    private fun alternarSeleccion() {
        modoSel = !modoSel
        botonSel?.setTextColor(if (modoSel) Paleta.ACCENT else Paleta.KEY_FG)
        io.modoSeleccion(modoSel)
    }

    private fun alternarOverflow() {
        overflow = !overflow
        chevron.text = if (overflow) "‹" else "›"
        poblarFilaSuperior()
    }

    private fun poblarFilaSuperior() {
        filaSuperior.removeAllViews()
        if (!overflow) {
            filaSuperior.addView(teclaAncha("Esc") { io.enviarTecla(KeyEvent.KEYCODE_ESCAPE) })
            filaSuperior.addView(teclaAncha("Tab") { tab() })
            botonCtrl = teclaAncha("Ctrl") { alternarCtrl() }
                .also { it.setTextColor(if (ctrlActivo) Paleta.ACCENT else Paleta.KEY_FG) }
            botonAlt = teclaAncha("Alt") { alternarAlt() }
                .also { it.setTextColor(if (altActivo) Paleta.ACCENT else Paleta.KEY_FG) }
            filaSuperior.addView(botonCtrl)
            filaSuperior.addView(botonAlt)
        } else {
            filaSuperior.addView(teclaAncha("Home") { io.enviarTecla(KeyEvent.KEYCODE_MOVE_HOME) })
            filaSuperior.addView(teclaAncha("End") { io.enviarTecla(KeyEvent.KEYCODE_MOVE_END) })
            filaSuperior.addView(teclaAncha("PgUp") { io.enviarTecla(KeyEvent.KEYCODE_PAGE_UP) })
            filaSuperior.addView(teclaAncha("PgDn") { io.enviarTecla(KeyEvent.KEYCODE_PAGE_DOWN) })
        }
        filaSuperior.addView(chevron)
    }

    @SuppressLint("ClickableViewAccessibility")   // el mic es push-to-talk: necesita DOWN/UP
    private fun poblarFilaShift() {
        filaShift.removeAllViews()
        if (tecladoArriba) return
        botonShift = teclaAncha("⇧ Shift") { alternarShift() }
            .also { it.setTextColor(if (shiftActivo) Paleta.ACCENT else Paleta.KEY_FG) }
        filaShift.addView(botonShift)
        botonSel = teclaAncha("⧉ Sel") { alternarSeleccion() }
            .also { it.setTextColor(if (modoSel) Paleta.ACCENT else Paleta.KEY_FG) }
        filaShift.addView(botonSel)
        botonMic = teclaAncha("🎤 Dictar") {}.also {
            it.contentDescription = "Dictar: mantené apretado para hablar"
            it.setOnTouchListener { _, ev -> io.tocaronMicrofono(ev) }
        }
        filaShift.addView(botonMic)
    }

    private fun crearChevron() = Button(context).apply {
        text = "›"
        contentDescription = "Más teclas"
        isAllCaps = false
        setTextColor(Paleta.CHEV_FG)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setBackgroundColor(Paleta.CHEV_BG)
        minWidth = 0
        minHeight = 0
        setPadding(context.dp(4), context.dp(10), context.dp(4), context.dp(10))
        layoutParams = LayoutParams(context.dp(34), Paleta.MATCH)
        setOnClickListener { alternarOverflow(); io.volverElFoco() }
    }

    private fun tecla(etiqueta: String, alTocar: () -> Unit) = Button(context).apply {
        text = etiqueta
        isAllCaps = false
        typeface = mono
        setTextColor(Paleta.KEY_FG)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setBackgroundColor(Color.TRANSPARENT)
        minWidth = 0
        minHeight = 0
        setPadding(context.dp(8), context.dp(10), context.dp(8), context.dp(10))
        setOnClickListener { alTocar(); io.volverElFoco() }
    }

    private fun teclaAncha(etiqueta: String, alTocar: () -> Unit): Button =
        tecla(etiqueta, alTocar).apply { layoutParams = LayoutParams(0, Paleta.WRAP, 1f) }
}
