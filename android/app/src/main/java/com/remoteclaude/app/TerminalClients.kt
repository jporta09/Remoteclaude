package com.remoteclaude.app

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * Los dos clientes que el motor de terminal vendorizado (Termux) pide para funcionar: uno
 * para la sesión (lo que llega del host) y otro para la vista (lo que hace el usuario).
 *
 * Eran dos objetos anónimos de ~100 líneas dentro de MainActivity, casi todo overrides vacíos
 * que tapaban las tres cosas que sí importan: el zoom, los modificadores pegajosos y OSC 52.
 */
class TerminalClients(
    private val act: AppCompatActivity,
    private val vista: () -> TerminalView,
    private val sesionActiva: () -> SshTerminalSession?,
    private val teclado: () -> KeypadView?,
    private val mostrarTeclado: () -> Unit,
    private val copiar: (String) -> Unit,
) {
    // En float, no en Int: ver Zoom.kt. Con Int los incrementos chicos del pellizco se
    // truncaban y la fuente sólo sabía achicarse.
    private var fuente = act.sp(15f).toFloat()
    private val fuenteMin = act.sp(8f).toFloat()
    private val fuenteMax = act.sp(28f).toFloat()
    private var fuentePx = Zoom.aPixeles(fuente)

    /** Tamaño inicial de fuente, para que la vista arranque igual que el zoom. */
    fun fuenteInicialPx(): Int = fuentePx

    val sesion: TerminalSessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            if (sesionActiva() === changedSession) vista().onScreenUpdated()
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (text.isNullOrEmpty()) return
            // OSC 52 lo dispara el HOST, no el usuario. Para una selección de tmux es lo
            // esperado, pero el buffer admite 1 MB: un proceso remoto podría escribir el
            // portapapeles del teléfono en silencio. Arriba de cierto tamaño se pregunta.
            if (text.length > OSC52_CONFIRMAR_SOBRE) {
                AlertDialog.Builder(act)
                    .setTitle("¿Copiar al portapapeles?")
                    .setMessage(
                        "El host quiere copiar ${text.length / 1024} KB al portapapeles del teléfono."
                    )
                    .setNegativeButton("Descartar", null)
                    .setPositiveButton("Copiar") { _, _ -> copiar(text) }
                    .show()
                return
            }
            copiar(text)
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val cb = act.getSystemService(android.content.ClipboardManager::class.java)
            val text = cb?.primaryClip?.getItemAt(0)?.coerceToText(act)?.toString()
            if (text.isNullOrEmpty()) return
            val bytes = text.toByteArray(Charsets.UTF_8)
            sesionActiva()?.write(bytes, 0, bytes.size)
        }

        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
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

    val vistaCliente: TerminalViewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            fuente = Zoom.escalar(fuente, scale, fuenteMin, fuenteMax)
            val nuevo = Zoom.aPixeles(fuente)
            if (nuevo != fuentePx) {
                fuentePx = nuevo
                vista().setTextSize(nuevo)
            }
            return 1.0f
        }

        /**
         * Un carácter del teclado del sistema, ya con los modificadores pegajosos aplicados.
         *
         * Con Ctrl activo se traduce a su byte de control y se consume; con Alt, se antepone
         * ESC y se deja seguir. En los dos casos el modificador se suelta después de un solo
         * carácter, que es lo que espera cualquiera que venga de una terminal de escritorio.
         */
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
            val kb = teclado()
            if (kb != null && kb.ctrlActivo) {
                kb.soltarModificadores()
                val b = TerminalKeys.ctrlByte(codePoint)
                if (b != TerminalKeys.NINGUNO) {
                    sesionActiva()?.write(byteArrayOf(b.toByte()), 0, 1)
                    return true
                }
            }
            if (kb != null && kb.altActivo) {
                kb.soltarModificadores()
                sesionActiva()?.write(TerminalKeys.ESC, 0, TerminalKeys.ESC.size)
            }
            return false
        }

        override fun onSingleTapUp(e: MotionEvent?) = mostrarTeclado()
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
        override fun onEmulatorSet() {}
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    companion object {
        /** A partir de acá, OSC 52 pide confirmación en vez de copiar solo. */
        const val OSC52_CONFIRMAR_SOBRE = 100_000
    }
}
