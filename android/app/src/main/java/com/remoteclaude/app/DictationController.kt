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
    private val burbuja: View,             // contenedor de la burbuja (show/hide)
    private val burbujaTexto: TextView,    // texto: parciales en vivo + transcripción final
    private val burbujaBotones: View,      // fila Descartar/Insertar (visible sólo en el preview)
    private val btnInsertar: View,
    private val btnDescartar: View,
    private val teclado: () -> KeypadView?,
    private val sesionActiva: () -> SshTerminalSession?,
    private val sesionAbierta: (SshTerminalSession) -> Boolean,
) {
    private val grabador = WavRecorder()
    @Volatile private var transcribiendo = false
    @Volatile private var previewPendiente = false
    // @Volatile: lo toca el callback onChunk del grabador (otro hilo) además del hilo de UI.
    @Volatile private var vivo: LiveDictation? = null

    // Invariante del botón: HABILITADO ⇔ hay un motor con el modelo cargado en el host.
    // Arranca PREPARANDO (deshabilitado); prepararStt() lo resuelve al conectar.
    @Volatile var motor: MotorStt = MotorStt.PREPARANDO
        private set
    @Volatile private var preparando = false
    // Conexión ociosa que sostiene despierto al server en vivo mientras la app está al
    // frente (su idle-exit cuenta una conexión establecida como uso).
    private val presencia = SttPresencia(act, host, port, user, keyPair)

    // La pestaña a la que está atado el dictado en curso (grabando o con preview pendiente). Si la
    // cerrás, hay que cancelar: si no, el texto quedaba huérfano o iba a la pestaña equivocada.
    @Volatile private var sesionEnJuego: SshTerminalSession? = null

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
        presencia.cerrar()
    }

    /**
     * Despierta el motor de dictado del host y habilita el mic recién cuando el modelo está
     * CARGADO (el server en vivo escucha, o el batch respondió el prewarm). Llamar desde un
     * thread al conectar (MainActivity); reentrante-seguro y re-ejecutable (reconexiones).
     */
    fun prepararStt() {
        if (preparando) return
        preparando = true
        try {
            act.runOnUiThread {
                teclado()?.estadoMicrofono("Preparando…", Paleta.KEY_FG, habilitado = false)
            }
            // Reintentos: "timeout"/error no son veredicto — el server puede estar todavía
            // cargando (frío total medido: ~105s) y el próximo intento lo encuentra listo.
            // Sin esto, un timeout dejaba el mic muerto hasta la próxima reconexión.
            for (intento in 1..3) {
                val r = try { control.despertarStt() } catch (_: Exception) { "" }
                motor = motorDeStt(r)
                Diagnostico.registrar(
                    if (motor == MotorStt.SIN_STT) Diagnostico.Nivel.AVISO else Diagnostico.Nivel.INFO,
                    "dictado", "motor de dictado: $motor (host respondió '$r', intento $intento)",
                )
                if (motor != MotorStt.PREPARANDO) break
                Thread.sleep(20_000)
            }
            act.runOnUiThread {
                when (motor) {
                    MotorStt.VIVO, MotorStt.BATCH -> micEnReposo()
                    // Sin motor: queda deshabilitado con el rótulo de siempre (atenuado por
                    // el disabled del botón); el porqué vive en Diagnóstico.
                    else -> teclado()?.estadoMicrofono("Dictar", Paleta.KEY_FG, habilitado = false)
                }
            }
            if (motor == MotorStt.VIVO) presencia.abrir() else presencia.cerrar()
        } finally {
            preparando = false
        }
    }

    /** La app volvió al frente / se fue atrás: la presencia sigue al foreground. */
    fun enPrimerPlano(alFrente: Boolean) {
        if (!alFrente) presencia.cerrar()
        else if (motor == MotorStt.VIVO) presencia.abrir()
    }

    fun avisarPermisoConcedido() {
        Toast.makeText(act, "Listo: mantené 🎤 apretado para dictar", Toast.LENGTH_SHORT).show()
    }

    private fun empezar() {
        if (transcribiendo) return
        // Cinturón además del botón deshabilitado (el estado del teclado se re-crea y podría
        // perder un frame): sin motor cargado no se graba.
        if (!puedeGrabar(motor)) return
        // Si había un preview pendiente y el usuario vuelve a dictar, ese preview se descarta
        // (no lo dejamos colgado ni lo insertamos solo).
        if (previewPendiente) { ocultarBurbuja(); micEnReposo() }
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
                // sólo mientras se escucha (no piso el preview si ya se está mostrando)
                if (burbuja.visibility == View.VISIBLE && burbujaBotones.visibility != View.VISIBLE) {
                    burbujaTexto.text = txt.takeLast(220)
                }
            }
        }
        vivo = l
        if (!grabador.start(onChunk = { chunk -> vivo?.feed(chunk) })) {
            vivo = null
            Toast.makeText(act, "No pude abrir el micrófono", Toast.LENGTH_SHORT).show()
            return
        }
        sesionEnJuego = sesionActiva()   // la pestaña en la que estás dictando
        teclado()?.estadoMicrofono("Grabando", Paleta.REC_FG)
        thread {
            if (l.start()) {
                act.runOnUiThread {
                    if (grabador.isRecording) {
                        burbujaTexto.text = "🎙 Escuchando…"
                        burbujaBotones.visibility = View.GONE
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
        sesionEnJuego = destino   // a partir de acá el dictado queda atado a la pestaña de destino
        transcribiendo = true
        teclado()?.estadoMicrofono("…", Paleta.ACCENT)
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
                // Se libera DENTRO del post al main: soltándolo en el worker, el main podía no
                // ver el cambio y el botón quedaba inutilizable el resto de la sesión. El STT ya
                // volvió, así que el mic queda libre aunque el preview siga abierto.
                transcribiendo = false
                // Si cerraste la pestaña del dictado mientras se transcribía, no muestres un preview
                // colgado atado a una sesión que ya no existe.
                if (sesionEnJuego !== destino || !sesionAbierta(destino)) {
                    ocultarBurbuja(); micEnReposo()
                    return@runOnUiThread
                }
                val limpio = sanitizarDictado(texto)
                if (limpio.isBlank()) {
                    ocultarBurbuja(); micEnReposo()
                    return@runOnUiThread
                }
                // Preview no-modal (F6): en vez de escribir directo al prompt, mostramos la
                // transcripción con Insertar/Descartar. Insertar escribe (sin Enter, ya saneado);
                // Descartar no escribe nada; la edición fina se hace en el prompt tras insertar.
                mostrarPreview(limpio, destino)
                micEnReposo()
            }
        }
    }

    /** Muestra la transcripción en la burbuja con Descartar/Insertar (no-modal). Insertar escribe
     *  al destino (el que se fijó al soltar el mic), SIN Enter; Descartar no escribe nada. */
    private fun mostrarPreview(texto: String, destino: SshTerminalSession) {
        previewPendiente = true
        burbujaTexto.text = texto
        burbujaBotones.visibility = View.VISIBLE
        burbuja.visibility = View.VISIBLE
        btnInsertar.setOnClickListener {
            // Si cerraste el tab destino entre el dictado y el Insertar, escribirle mandaría el texto
            // a una sesión que ya no es una pestaña (se perdía o iba a la equivocada). Chequeamos que
            // el destino SIGA abierto — no el flag `cerrada` del transport, que no se prende al cerrar
            // un tab SSH (finishIfRunning es no-op sin shell local).
            if (!sesionAbierta(destino)) {
                Toast.makeText(act, "La sesión se cerró; no se pudo insertar el dictado", Toast.LENGTH_LONG).show()
                ocultarBurbuja()
                return@setOnClickListener
            }
            val bytes = "$texto ".toByteArray(Charsets.UTF_8)
            destino.write(bytes, 0, bytes.size)   // sin Enter: se revisa/edita en el prompt
            ocultarBurbuja()
        }
        btnDescartar.setOnClickListener { ocultarBurbuja() }
    }

    private fun ocultarBurbuja() {
        burbuja.visibility = View.GONE
        burbujaBotones.visibility = View.GONE
        burbujaTexto.text = ""
        previewPendiente = false
        sesionEnJuego = null
    }

    private fun micEnReposo() = teclado()?.estadoMicrofono("Dictar", Paleta.KEY_FG)

    /** Cerraron una pestaña. Si es la del dictado en juego (grabando o con preview), cancelarlo y
     *  sacar la burbuja: si no, el texto quedaba huérfano o iba a la pestaña equivocada. */
    fun tabCerrado(cerrada: SshTerminalSession) {
        if (sesionEnJuego !== cerrada) return
        vivo?.cancel(); vivo = null
        if (grabador.isRecording) grabador.stop()
        // NO tocamos `transcribiendo`: si hay un worker de transcripción en vuelo, es SUYO (lo pone en
        // false al terminar). Limpiarlo acá abría una ventana para que un `empezar()` arranque un
        // segundo worker concurrente. `ocultarBurbuja` pone `sesionEnJuego = null`, que es la señal por
        // la que ese worker se auto-cancela (chequeo `sesionEnJuego !== destino`) sin pisar nada.
        ocultarBurbuja()          // limpia sesionEnJuego
        micEnReposo()
        Toast.makeText(act, "Cerraste la pestaña del dictado; se descartó", Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** Código del pedido de permiso RECORD_AUDIO. */
        const val REQ_MIC = 71
    }
}

/** Saca los caracteres de control ANTES de que el texto del dictado toque el prompt: un `\n`
 *  del STT sería un Enter IMPLÍCITO (mandaría el comando sin que lo revises) — el gate de Enter
 *  no lo cubría. Reemplaza todo control char (incl. \n \r \t) por espacio y colapsa espacios.
 *  Top-level para poder testearlo sin instanciar la Activity. */
fun sanitizarDictado(texto: String?): String =
    (texto ?: "").replace(Regex("\\p{Cntrl}"), " ").replace(Regex(" +"), " ").trim()

/** Qué motor de dictado tiene el host. El invariante del botón: habilitado ⇔ VIVO o BATCH
 *  (modelo cargado). PREPARANDO = despertando; SIN_STT = el host no tiene dictado. */
enum class MotorStt { PREPARANDO, VIVO, BATCH, SIN_STT }

/** Mapea la respuesta de `despertarStt()` del host. "timeout" o error ("") quedan en
 *  PREPARANDO: no es un veredicto — un reintento (reconexión) puede resolverlo.
 *  Top-level para testearlo sin Android. */
fun motorDeStt(respuesta: String): MotorStt = when (respuesta.trim()) {
    "vivo" -> MotorStt.VIVO
    "batch" -> MotorStt.BATCH
    "sin-stt" -> MotorStt.SIN_STT
    else -> MotorStt.PREPARANDO
}

/** La decisión del gate de grabación, pura y testeable. */
fun puedeGrabar(motor: MotorStt): Boolean = motor == MotorStt.VIVO || motor == MotorStt.BATCH
