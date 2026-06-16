package com.remoteclaude.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * M4: terminal completamente usable — teclado de teclas extra fijo (sin scroll):
 *   Esc  Tab  ›        (› revela Home/End/PgUp/PgDn)
 *   Ctrl Alt
 *   ←  ↓  ↑  →         (flechas siempre visibles)
 * Ctrl/Alt como modificadores de una sola pulsación; zoom de fuente (pinch).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var terminalView: TerminalView
    private lateinit var session: TerminalSession

    private var fontSizePx = 0
    private var minFontPx = 0
    private var maxFontPx = 0

    // Modificadores de una sola pulsación.
    private var ctrlActive = false
    private var altActive = false
    private lateinit var ctrlButton: Button
    private lateinit var altButton: Button

    // Teclado: bloque superior conmutable (principal <-> overflow Home/End/PgUp/PgDn).
    private var overflowShown = false
    private lateinit var rowTop: LinearLayout
    private lateinit var chevron: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )

        fontSizePx = sp(15f)
        minFontPx = sp(8f)
        maxFontPx = sp(28f)

        terminalView = TerminalView(this, null)
        terminalView.setTerminalViewClient(viewClient)
        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true
        terminalView.setTextSize(fontSizePx)
        terminalView.setTypeface(Typeface.MONOSPACE)

        val keyChars = assets.open("m2_test_key").bufferedReader().use { it.readText() }.toCharArray()
        session = SshTerminalSession("remoteclaude", 22, "root", keyChars, sessionClient)
        terminalView.attachSession(session)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(terminalView, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(buildKeypad(), LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(root)

        terminalView.requestFocus()
        terminalView.post { showKeyboard() }
    }

    private fun showKeyboard() {
        terminalView.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    // --- Teclado de teclas extra ---------------------------------------------

    private fun buildKeypad(): View {
        val pad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(KEYPAD_BG)
        }

        rowTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chevron = chevronButton()

        val arrows = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        arrows.addView(weightKey("←") { sendKey(KeyEvent.KEYCODE_DPAD_LEFT) })
        arrows.addView(weightKey("↓") { sendKey(KeyEvent.KEYCODE_DPAD_DOWN) })
        arrows.addView(weightKey("↑") { sendKey(KeyEvent.KEYCODE_DPAD_UP) })
        arrows.addView(weightKey("→") { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) })

        populateTopBlock()

        pad.addView(rowTop, LinearLayout.LayoutParams(MATCH, WRAP))
        pad.addView(arrows, LinearLayout.LayoutParams(MATCH, WRAP))
        return pad
    }

    private fun toggleOverflow() {
        overflowShown = !overflowShown
        chevron.text = if (overflowShown) "‹" else "›"
        populateTopBlock()
        terminalView.requestFocus()
    }

    /** Una sola fila: Esc Tab Ctrl Alt (o Home End PgUp PgDn) + chevron angosto. */
    private fun populateTopBlock() {
        rowTop.removeAllViews()
        if (!overflowShown) {
            rowTop.addView(weightKey("Esc") { sendKey(KeyEvent.KEYCODE_ESCAPE) })
            rowTop.addView(weightKey("Tab") { sendKey(KeyEvent.KEYCODE_TAB) })
            ctrlButton = weightKey("Ctrl") { toggleCtrl() }
            altButton = weightKey("Alt") { toggleAlt() }
            rowTop.addView(ctrlButton)
            rowTop.addView(altButton)
            ctrlButton.setTextColor(if (ctrlActive) ACCENT else KEY_FG)
            altButton.setTextColor(if (altActive) ACCENT else KEY_FG)
        } else {
            rowTop.addView(weightKey("Home") { sendKey(KeyEvent.KEYCODE_MOVE_HOME) })
            rowTop.addView(weightKey("End") { sendKey(KeyEvent.KEYCODE_MOVE_END) })
            rowTop.addView(weightKey("PgUp") { sendKey(KeyEvent.KEYCODE_PAGE_UP) })
            rowTop.addView(weightKey("PgDn") { sendKey(KeyEvent.KEYCODE_PAGE_DOWN) })
        }
        rowTop.addView(chevron)
    }

    /** Chevron angosto y de color más oscuro (afordance secundaria). */
    private fun chevronButton(): Button {
        return Button(this).apply {
            text = "›"
            isAllCaps = false
            setTextColor(CHEV_FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setBackgroundColor(CHEV_BG)
            minWidth = 0
            minHeight = 0
            setPadding(dp(4), dp(10), dp(4), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(34), MATCH)
            setOnClickListener {
                toggleOverflow()
                terminalView.requestFocus()
            }
        }
    }

    private fun keyButton(label: String, onTap: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(KEY_FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setBackgroundColor(Color.TRANSPARENT)
            minWidth = 0
            minHeight = 0
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnClickListener {
                onTap()
                terminalView.requestFocus()
            }
        }
    }

    /** Botón que reparte el ancho por igual en su fila (sin scroll). */
    private fun weightKey(label: String, onTap: () -> Unit): Button {
        return keyButton(label, onTap).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
    }

    private fun sendKey(keyCode: Int) = terminalView.handleKeyCode(keyCode, 0)

    private fun toggleCtrl() {
        ctrlActive = !ctrlActive
        ctrlButton.setTextColor(if (ctrlActive) ACCENT else KEY_FG)
    }

    private fun toggleAlt() {
        altActive = !altActive
        altButton.setTextColor(if (altActive) ACCENT else KEY_FG)
    }

    private fun resetModifiers() {
        if (ctrlActive) toggleCtrl()
        if (altActive) toggleAlt()
    }

    // --- Clientes -------------------------------------------------------------

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) { terminalView.onScreenUpdated() }
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            val newSize = (fontSizePx * scale).toInt().coerceIn(minFontPx, maxFontPx)
            if (newSize != fontSizePx) {
                fontSizePx = newSize
                terminalView.setTextSize(newSize)
            }
            return 1.0f
        }

        override fun onSingleTapUp(e: MotionEvent?) = showKeyboard()
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
        override fun onLongPress(event: MotionEvent?): Boolean = false
        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
            if (ctrlActive) {
                resetModifiers()
                val b = when (codePoint) {
                    in 'a'.code..'z'.code -> codePoint - 'a'.code + 1
                    in 'A'.code..'Z'.code -> codePoint - 'A'.code + 1
                    ' '.code, '@'.code -> 0
                    '['.code -> 27
                    '\\'.code -> 28
                    ']'.code -> 29
                    else -> -1
                }
                if (b >= 0) {
                    this@MainActivity.session.write(byteArrayOf(b.toByte()), 0, 1)
                    return true
                }
            }
            if (altActive) {
                resetModifiers()
                this@MainActivity.session.write(byteArrayOf(27), 0, 1)
            }
            return false
        }

        override fun onEmulatorSet() {}
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    // --- utils ----------------------------------------------------------------
    private fun sp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics).toInt()
    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val ACCENT = Color.parseColor("#8fbcbb")
        private val KEY_FG = Color.parseColor("#d8dee9")
        private val KEYPAD_BG = Color.parseColor("#11151c")
        private val CHEV_FG = Color.parseColor("#6b7280")
        private val CHEV_BG = Color.parseColor("#0a0d12")
    }
}
