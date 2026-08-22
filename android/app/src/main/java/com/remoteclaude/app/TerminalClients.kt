package com.remoteclaude.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
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
    // "Modo lectura": ante un prompt de DECISIÓN (opciones numeradas al pie) bajamos el teclado —
    // leer/elegir no lo necesita— para recuperar filas y no elegir a ciegas.
    private val ocultarTeclado: () -> Unit = {},
    // WS-F: cada cambio de texto de la sesión activa alimenta al watcher de aprobación.
    private val alCambiarTexto: (() -> Unit)? = null,
    // Seguridad OSC 52: ¿esta copia la iniciaste VOS (Sel / selección)? Si no, la escribió el host
    // por su cuenta y NO se copia (defensa contra secuestro de portapapeles). Ver copiarBloqueado.
    private val copiaLaPediste: () -> Boolean = { true },
    // Handshake OSC 52: se soltó un arrastre en modo Sel (tmux va a copiar por OSC 52 enseguida).
    // Abre una ventana de UN SOLO USO para aceptar esa copia y sólo esa (ver copiaIniciadaPorVos).
    private val alSoltarSelDrag: () -> Unit = {},
) {

    /** Vos pediste la copia (Sel / selección): al portapapeles, con el "Copiado" de siempre. */
    private fun copiar(text: String) {
        act.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("terminal", text))
        Toast.makeText(act, "Copiado", Toast.LENGTH_SHORT).show()
    }

    /** El host quiso escribir el portapapeles por su cuenta (OSC 52 sin que lo pidieras con Sel):
     *  se BLOQUEA. Cualquier proceso en la terminal —un Claude inyectado, la salida de un archivo no
     *  confiable— podría secuestrarte el portapapeles en silencio. Se avisa con un preview de lo que
     *  se intentó meter, para que lo notes. */
    private fun copiarBloqueado(text: String) {
        Toast.makeText(
            act,
            "Seguridad: bloqueé una copia al portapapeles que pidió el host. " +
                "Para copiar vos, usá Sel. Intento: \"${previewSeguro(text, 40)}\"",
            Toast.LENGTH_LONG,
        ).show()
    }

    // En float, no en Int: ver Zoom.kt. Con Int los incrementos chicos del pellizco se
    // truncaban y la fuente sólo sabía achicarse.
    private val fuenteMin = act.sp(8f).toFloat()
    private val fuenteMax = act.sp(28f).toFloat()
    // El tamaño se PERSISTE (prefs): sin esto arrancaba en 15sp por cada tab/sesión y había que
    // re-pellizcar siempre para poder leer. Se agranda una vez y queda.
    private var fuente = cargarFuente()
    private var fuentePx = Zoom.aPixeles(fuente)

    private fun cargarFuente(): Float =
        act.getSharedPreferences("remotemarvin", Context.MODE_PRIVATE)
            .getFloat("fuente_sp", act.sp(15f).toFloat())
            .coerceIn(fuenteMin, fuenteMax)

    private fun guardarFuente() {
        act.getSharedPreferences("remotemarvin", Context.MODE_PRIVATE)
            .edit().putFloat("fuente_sp", fuente).apply()
    }

    /** Tamaño inicial de fuente, para que la vista arranque igual que el zoom. */
    fun fuenteInicialPx(): Int = fuentePx

    // --- Modo lectura -----------------------------------------------------------------
    // Cuando aparece un prompt de DECISIÓN (opciones numeradas al pie, tipo "1) Yes / 2) No"),
    // con el teclado arriba el contexto de arriba se va de pantalla y elegís a ciegas. En ese
    // momento —y sólo ese— bajamos el teclado para que puedas leer antes de decidir. NO dispara
    // con salida grande cualquiera (para eso está el scroll/pinch). Detección Claude-independiente
    // (cualquier menú/select), y de bajo riesgo: sólo decide CUÁNDO bajar el teclado; si se
    // equivoca, tocás y vuelve — no re-renderiza nada.
    //
    // `enModoLectura` = "ya reaccioné al prompt que está en pantalla" (bajé el teclado UNA vez).
    // No significa "el teclado está abajo": si tocás para tipear, el teclado sube pero el flag
    // sigue true, así NO se lo vuelve a bajar mientras el MISMO prompt siga. Se rearma (→ false)
    // recién cuando el prompt cambia o desaparece.
    @Volatile private var enModoLectura = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val chequeoLectura = Runnable { revisarLectura() }

    val sesion: TerminalSessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            if (sesionActiva() === changedSession) {
                vista().onScreenUpdated()
                alCambiarTexto?.invoke()
                // ¿llegó un bloque grande para leer? (debounced: la salida llega en ráfagas)
                handler.removeCallbacks(chequeoLectura)
                handler.postDelayed(chequeoLectura, 150)
            }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (text.isNullOrEmpty()) return
            // Todo copiado del terminal llega acá como OSC 52 —tanto tu Sel (tmux copia y emite OSC 52
            // al soltar) como una copia que dispare un programa por su cuenta—. Sólo dejamos pasar la
            // que iniciaste VOS (Sel / selección activa); la del host se bloquea (secuestro de
            // portapapeles). El Sel es la forma recomendada de copiar; los botones "Copiar" de la app
            // NO pasan por acá (escriben el portapapeles directo).
            if (copiaLaPediste()) copiar(text) else copiarBloqueado(text)
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
                guardarFuente()   // que el tamaño elegido sobreviva a cerrar la app / cambiar de tab
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

        override fun onSingleTapUp(e: MotionEvent?) {
            // Tocar la terminal = quiero escribir: subo el teclado. NO reseteo `enModoLectura`:
            // si el selector SIGUE en pantalla, `revisarLectura` NO debe volver a bajarlo (si no,
            // se pelea con el usuario que quiere tipear la respuesta). El flag se rearma solo
            // cuando el prompt cambia o desaparece (rama else de `revisarLectura`).
            mostrarTeclado()
        }
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onSelDragReleased() = alSoltarSelDrag()
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

    /** ¿Hay en pantalla un prompt de DECISIÓN (opciones numeradas al pie)? Entonces es el momento
     *  de leer antes de elegir: bajamos el teclado UNA vez para recuperar filas. Bajo tmux (el modo
     *  default de la app) NO hay scrollback a nivel del motor, así que se mira el texto VISIBLE. */
    private fun revisarLectura() {
        val texto = sesionActiva()?.emulator?.screen?.transcriptTextWithoutJoinedLines ?: return
        if (hayPromptDeDecision(texto)) {
            if (!enModoLectura) {
                enModoLectura = true
                ocultarTeclado()   // leer/decidir no necesita el teclado: recupera filas
            }
        } else {
            // el prompt se fue (respondiste, o Claude siguió): permitir que el próximo lo baje
            enModoLectura = false
        }
    }
}

/** ¿El texto visible termina en un **selector de decisión de Claude** esperando input? Anclado en la
 *  firma REAL capturada del selector (tanto el de permiso de herramienta como el de trust):
 *    ❯ 1. Yes
 *      2. No
 *    Esc to cancel · Tab to amend · ctrl+e to explain
 *  Los invariantes robustos son (a) el **cursor `❯` sobre una opción numerada** —el sello del select
 *  de Claude, ausente en salida común— y (b) el footer con `to cancel` (que lo distingue del spinner,
 *  que dice "esc to *interrupt*"). El header ("Do you want to proceed?" / "…make this edit?") varía,
 *  así que NO se ancla ahí. Se mira sólo el texto VISIBLE (bajo tmux no hay scrollback en el motor).
 *  Top-level para testearla sin Android. */
fun hayPromptDeDecision(texto: String): Boolean {
    val cursor = Regex("""^\s*❯\s*\d+[.)]\s+\S""")
    val opcion = Regex("""^\s*[❯>]?\s*\d+[.)]\s+\S""")
    val ultimas = texto.split("\n").map { it.trimEnd() }.filter { it.isNotBlank() }.takeLast(10)
    val tieneCursor = ultimas.any { cursor.containsMatchIn(it) }
    val opciones = ultimas.count { opcion.containsMatchIn(it) }
    val footerCancelar = ultimas.any { it.contains("to cancel", ignoreCase = true) }
    // el cursor ❯-sobre-opción es específico de Claude; corroborado por el footer o una 2ª opción
    return tieneCursor && (footerCancelar || opciones >= 2)
}

/** Snippet de una línea para el preview de OSC 52 (mostrar QUÉ copió el host, no sólo el conteo):
 *  colapsa espacios/saltos/control a un espacio y trunca con "…". Top-level para testearla sin Android. */
fun previewSeguro(text: String, max: Int): String {
    val limpio = text.replace(Regex("""\s+"""), " ").trim()
    return if (limpio.length <= max) limpio else limpio.take(max) + "…"
}
