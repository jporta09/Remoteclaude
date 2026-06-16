package com.remoteclaude.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * M4: terminal completamente usable — fila de teclas extra (Esc/Ctrl/Alt/Tab/flechas/
 * símbolos), Ctrl/Alt como modificadores de una sola pulsación, y zoom de fuente (pinch).
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

        // Layout: terminal (ocupa todo) + fila de teclas extra abajo.
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            terminalView,
            LinearLayout.LayoutParams(MATCH, 0, 1f)   // weight 1 -> ocupa el resto
        )
        root.addView(buildExtraKeysRow(), LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(root)

        terminalView.requestFocus()
        terminalView.post { showKeyboard() }
    }

    private fun showKeyboard() {
        terminalView.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    // --- Fila de teclas extra -------------------------------------------------

    private fun buildExtraKeysRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#11151c"))
        }
        ctrlButton = keyButton("Ctrl") { toggleCtrl() }
        altButton = keyButton("Alt") { toggleAlt() }

        row.addView(keyButton("Esc") { sendKey(KeyEvent.KEYCODE_ESCAPE) })
        row.addView(keyButton("Tab") { sendKey(KeyEvent.KEYCODE_TAB) })
        row.addView(ctrlButton)
        row.addView(altButton)
        row.addView(keyButton("←") { sendKey(KeyEvent.KEYCODE_DPAD_LEFT) })
        row.addView(keyButton("↓") { sendKey(KeyEvent.KEYCODE_DPAD_DOWN) })
        row.addView(keyButton("↑") { sendKey(KeyEvent.KEYCODE_DPAD_UP) })
        row.addView(keyButton("→") { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) })
        row.addView(keyButton("^C") { session.write(byteArrayOf(3), 0, 1) })
        row.addView(keyButton("|") { sendStr("|") })
        row.addView(keyButton("/") { sendStr("/") })
        row.addView(keyButton("-") { sendStr("-") })
        row.addView(keyButton("~") { sendStr("~") })
        row.addView(keyButton("Home") { sendKey(KeyEvent.KEYCODE_MOVE_HOME) })
        row.addView(keyButton("End") { sendKey(KeyEvent.KEYCODE_MOVE_END) })
        row.addView(keyButton("PgUp") { sendKey(KeyEvent.KEYCODE_PAGE_UP) })
        row.addView(keyButton("PgDn") { sendKey(KeyEvent.KEYCODE_PAGE_DOWN) })

        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun keyButton(label: String, onTap: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.parseColor("#d8dee9"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            minWidth = dp(40)
            minHeight = 0
            setOnClickListener {
                onTap()
                terminalView.requestFocus()
            }
        }
    }

    private fun sendKey(keyCode: Int) = terminalView.handleKeyCode(keyCode, 0)
    private fun sendStr(s: String) = session.write(s)

    private fun toggleCtrl() {
        ctrlActive = !ctrlActive
        ctrlButton.setTextColor(if (ctrlActive) ACCENT else Color.parseColor("#d8dee9"))
    }

    private fun toggleAlt() {
        altActive = !altActive
        altButton.setTextColor(if (altActive) ACCENT else Color.parseColor("#d8dee9"))
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
        // Zoom de fuente con pinch. La view nos pasa el factor acumulado; lo aplicamos
        // y devolvemos 1.0 para resetear (así el próximo gesto arranca de cero).
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
        override fun readControlKey(): Boolean = false   // Ctrl lo manejamos en onCodePoint
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false

        // Aplica los modificadores Ctrl/Alt (una sola pulsación) al carácter tipeado.
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
                    return true   // consumido
                }
            }
            if (altActive) {
                resetModifiers()
                // Alt = prefijo ESC; dejamos que la view escriba el carácter normal después.
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
    }
}
