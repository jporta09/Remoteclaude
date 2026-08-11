package com.remoteclaude.app

import android.Manifest
import android.content.pm.PackageManager
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * Dictado por voz, push-to-talk: grabás mientras sostenés 🎤 y al soltar el texto aparece en
 * la terminal como si lo hubieras tipeado (sin Enter: lo revisás antes de mandar).
 *
 * Son dos caminos que corren juntos. El de fase 2 (en vivo) abre un WebSocket contra
 * WhisperLiveKit y va mostrando parciales en la burbuja; el de fase 1 (por lote) manda el WAV
 * entero por SSH al soltar. El primero es best-effort: si el server en vivo no está, el
 * segundo cubre igual el dictado y de paso se deja arrancando el daemon para la próxima.
 */
class DictationController(
    private val act: AppCompatActivity,
    private val host: String,
    private val port: Int,
    private val user: String,
    private val keyPair: java.security.KeyPair,
    private val control: RemoteControl,
    private val burbuja: TextView,
    private val teclado: () -> KeypadView?,
    private val sesionActiva: () -> SshTerminalSession?,
) {
    private val grabador = WavRecorder()
    @Volatile private var transcribiendo = false
    private var vivo: LiveDictation? = null

    /** Devuelve true: consume el evento del botón. */
    fun alTocarMicrofono(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> empezar()
            MotionEvent.ACTION_UP -> terminar()
            MotionEvent.ACTION_CANCEL -> {
                grabador.cancel()
                vivo?.cancel(); vivo = null
                ocultarBurbuja(); micEnReposo()
            }
        }
        return true
    }

    /** Al cerrar la pantalla: no dejar el micrófono ni el WebSocket abiertos. */
    fun soltar() {
        grabador.cancel()
        vivo?.cancel(); vivo = null
    }

    fun avisarPermisoConcedido() {
        Toast.makeText(act, "Listo: mantené 🎤 apretado para dictar", Toast.LENGTH_SHORT).show()
    }

    private fun empezar() {
        if (transcribiendo) return
        if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            act.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        // La sesión de streaming se crea ANTES del grabador para que los primeros chunks
        // queden encolados hasta que el túnel y el WebSocket abran, en vez de perderse.
        val l = LiveDictation(act, host, port, user, keyPair) { txt ->
            act.runOnUiThread {
                if (burbuja.visibility == View.VISIBLE) burbuja.text = txt.takeLast(220)
            }
        }
        vivo = l
        if (!grabador.start(onChunk = { chunk -> vivo?.feed(chunk) })) {
            vivo = null
            Toast.makeText(act, "No pude abrir el micrófono", Toast.LENGTH_SHORT).show()
            return
        }
        teclado()?.estadoMicrofono("${Iconos.MICROFONO} Grabando", Paleta.REC_FG)
        thread {
            if (l.start()) {
                act.runOnUiThread {
                    if (grabador.isRecording) {
                        burbuja.text = "🎙 Escuchando…"
                        burbuja.visibility = View.VISIBLE
                    }
                }
            } else {
                if (vivo === l) vivo = null
                // Sin server en vivo: esta vez transcribe la fase 1, y se lo deja arrancando
                // para el próximo dictado.
                try { control.kickLiveStt() } catch (_: Exception) {}
            }
        }
    }

    private fun terminar() {
        val l = vivo; vivo = null
        val wav = grabador.stop() ?: run { l?.cancel(); ocultarBurbuja(); micEnReposo(); return }
        // La sesión destino se fija ACÁ y no al terminar: el round-trip tarda segundos y si
        // mientras tanto cambiás de pestaña, el texto se escribía en la equivocada.
        val destino = sesionActiva() ?: run { l?.cancel(); ocultarBurbuja(); micEnReposo(); return }
        transcribiendo = true
        teclado()?.estadoMicrofono("${Iconos.MICROFONO} …", Paleta.ACCENT)
        thread {
            var texto: String? = null
            if (l != null && l.available) {
                texto = try { l.stop() } catch (_: Exception) { null }
            }
            if (texto.isNullOrBlank()) {
                l?.cancel()
                texto = try { control.transcribe(wav) } catch (e: Exception) {
                    act.runOnUiThread {
                        Toast.makeText(
                            act,
                            "Dictado falló: ${e.message ?: "sin conexión"}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    null
                }
            }
            act.runOnUiThread {
                if (!texto.isNullOrBlank()) {
                    val bytes = "$texto ".toByteArray(Charsets.UTF_8)
                    destino.write(bytes, 0, bytes.size)
                }
                ocultarBurbuja()
                micEnReposo()
                // Se libera DENTRO del post al main: soltándolo en el worker, el main podía no
                // ver el cambio y el botón quedaba inutilizable el resto de la sesión.
                transcribiendo = false
            }
        }
    }

    private fun ocultarBurbuja() {
        burbuja.visibility = View.GONE
        burbuja.text = ""
    }

    private fun micEnReposo() = teclado()?.estadoMicrofono("${Iconos.MICROFONO} Dictar", Paleta.KEY_FG)

    companion object {
        /** Código del pedido de permiso RECORD_AUDIO. */
        const val REQ_MIC = 71
    }
}
