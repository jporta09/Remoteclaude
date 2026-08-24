package com.remoteclaude.app

import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Before
import org.junit.Test

/**
 * EXPERIMENTO (no regresión): benchmark de STT en el celu real, con las MISMAS condiciones
 * acústicas para los dos motores — el audio de prueba suena por el PARLANTE del teléfono y el
 * MICRÓFONO lo captura (bucle acústico), igual que si le hablaras al aparato.
 *
 *  - `fabricaOnDevice`: el reconocedor de fábrica de Android (SpeechRecognizer on-device).
 *  - `appHostGpu`: el camino real del botón Dictar — WavRecorder → SSH → marvin-stt (GPU del host).
 *
 * Se corre a mano con `am instrument` pasando los args (no corre en CI):
 *   -e sttHost <ip del host>  -e sttUser <usuario ssh>  [-e sttLang es-ES] [-e sttAudio viaje.wav]
 * Resultados por logcat, tag STTBENCH.
 */
class SttBenchmarkTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instr.targetContext
    private val args: Bundle get() = InstrumentationRegistry.getArguments()
    private val audio: String get() = args.getString("sttAudio") ?: "viaje.wav"

    /** Con -e sttVisible 1: lanza la pantalla del benchmark (cronómetro + estado + texto en vivo)
     *  para grabar la corrida con screenrecord. Sin el arg, corre headless como siempre. */
    private var ui: BenchActivity? = null
    private fun lanzarUiSiCorresponde(titulo: String) {
        if (args.getString("sttVisible") != "1") return
        val esc = androidx.test.core.app.ActivityScenario.launch(BenchActivity::class.java)
        val latch = CountDownLatch(1)
        esc.onActivity { ui = it; latch.countDown() }
        latch.await(10, TimeUnit.SECONDS)
        ui?.poner(tit = titulo, est = "preparando…", txt = "")
        Thread.sleep(1500)   // que la grabación arranque mostrando la pantalla lista
    }

    @Before fun permisos() {
        // Es un EXPERIMENTO manual (am instrument con -e ...), no parte de la suite: sin args,
        // se salta — así `make e2e` en el emulador (sin reconocedor ni host) no lo arrastra.
        org.junit.Assume.assumeTrue(
            "benchmark manual: correr con -e sttLang/-e sttHost",
            listOf("sttLang", "sttHost", "sttAudio").any { args.getString(it) != null },
        )
        runCatching {
            instr.uiAutomation.grantRuntimePermission(ctx.packageName, "android.permission.RECORD_AUDIO")
        }
    }

    /** Reproduce el asset por el parlante a volumen máximo. Devuelve el player ya sonando. */
    private fun reproducir(alTerminar: () -> Unit): MediaPlayer {
        val am = ctx.getSystemService(AudioManager::class.java)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        val fd = instr.context.assets.openFd("stt/$audio")   // assets del APK de test
        val mp = MediaPlayer()
        mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
        fd.close()
        mp.setOnCompletionListener { alTerminar() }
        mp.prepare()
        mp.start()
        return mp
    }

    /** Consulta el soporte on-device y dispara la DESCARGA del modelo del idioma (API 33+).
     *  Se corre una vez antes del benchmark si el idioma no está instalado (error 13). */
    @Test fun descargarModeloOnDevice() {
        val lang = args.getString("sttLang") ?: "es-ES"
        val main = Handler(Looper.getMainLooper())
        val fin = CountDownLatch(1)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        }
        lateinit var rec: SpeechRecognizer
        main.post {
            rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
            rec.checkRecognitionSupport(
                intent,
                ctx.mainExecutor,
                object : android.speech.RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: android.speech.RecognitionSupport) {
                        Log.i("STTBENCH", "instalados=${recognitionSupport.installedOnDeviceLanguages}")
                        Log.i("STTBENCH", "soportados=${recognitionSupport.supportedOnDeviceLanguages}")
                        Log.i("STTBENCH", "pendientes=${recognitionSupport.pendingOnDeviceLanguages}")
                        rec.triggerModelDownload(intent)
                        Log.i("STTBENCH", "descarga de $lang disparada")
                        fin.countDown()
                    }
                    override fun onError(error: Int) {
                        Log.i("STTBENCH", "checkRecognitionSupport error=$error")
                        fin.countDown()
                    }
                },
            )
        }
        check(fin.await(30, TimeUnit.SECONDS)) { "checkRecognitionSupport no respondió" }
        main.post { runCatching { rec.destroy() } }
    }

    @Test fun fabricaOnDevice() {
        check(SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
            "este equipo no tiene reconocedor on-device"
        }
        val lang = args.getString("sttLang") ?: "es-ES"
        lanzarUiSiCorresponde("FÁBRICA on-device ($lang)")
        val main = Handler(Looper.getMainLooper())
        val fin = CountDownLatch(1)
        val partes = mutableListOf<String>()
        var playbackTermino = false
        var tPlaybackFin = 0L
        var tUltimoResultado = 0L
        var errorFatal: Int? = null

        lateinit var rec: SpeechRecognizer
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Audio de corrido con pausas de lectura: estirar los silencios para que no corte antes.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000)
        }
        val t0Global = System.currentTimeMillis()
        fun ms() = System.currentTimeMillis() - t0Global
        var rmsLogueados = 0
        // El on-device reconoce en UNA sesión continua con segmentos internos: los parciales se
        // resetean por segmento y el onResults final sólo trae el último (o null). El transcript
        // completo se arma acumulando el MEJOR parcial de cada segmento.
        var actual = ""
        fun cerrarSegmento(nuevo: String) {
            if (actual.isNotBlank()) partes += actual.trim()
            actual = nuevo
        }
        val listener = object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                Log.i("STTBENCH", "[${ms()}ms] onResults: '${t?.take(80)}'")
                // el final puede repetir el último segmento que ya tenemos en `actual`
                if (!t.isNullOrBlank() && t.trim() != actual.trim()) cerrarSegmento(t) else cerrarSegmento("")
                tUltimoResultado = System.currentTimeMillis()
                // El reconocedor corta en las pausas: si el audio sigue sonando, re-escuchar y acumular.
                if (!playbackTermino) rec.startListening(intent) else fin.countDown()
            }
            override fun onError(error: Int) {
                Log.i("STTBENCH", "[${ms()}ms] onError: $error")
                tUltimoResultado = System.currentTimeMillis()
                when {
                    // sin match / timeout en una pausa: si el audio sigue, re-escuchar
                    !playbackTermino && (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) -> rec.startListening(intent)
                    !playbackTermino -> { errorFatal = error; fin.countDown() }
                    else -> fin.countDown()
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t.isNullOrBlank()) return
                tUltimoResultado = System.currentTimeMillis()
                // ¿Arrancó un segmento nuevo? El parcial se resetea: corto y no es prefijo del actual.
                if (actual.isNotBlank() && t.length < actual.length && !actual.startsWith(t.take(8))) {
                    Log.i("STTBENCH", "[${ms()}ms] segmento cerrado: '${actual.take(60)}'")
                    cerrarSegmento(t)
                } else {
                    actual = t
                }
                ui?.poner(txt = (partes + actual).joinToString(" "))
            }
            override fun onReadyForSpeech(params: Bundle?) { Log.i("STTBENCH", "[${ms()}ms] onReadyForSpeech") }
            override fun onBeginningOfSpeech() { Log.i("STTBENCH", "[${ms()}ms] onBeginningOfSpeech") }
            override fun onRmsChanged(rmsdB: Float) {
                if (rmsLogueados < 8 && rmsdB > 0) { rmsLogueados++; Log.i("STTBENCH", "[${ms()}ms] rms=$rmsdB") }
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { Log.i("STTBENCH", "[${ms()}ms] onEndOfSpeech") }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

        val t0 = System.currentTimeMillis()
        var mp: MediaPlayer? = null
        main.post {
            rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
            rec.setRecognitionListener(listener)
            rec.startListening(intent)
            ui?.poner(est = "▶ audio sonando — transcribe EN VIVO")
            ui?.arrancarTimer()
            // arrancar el audio apenas el reconocedor quedó escuchando
            mp = reproducir {
                playbackTermino = true
                tPlaybackFin = System.currentTimeMillis()
                Log.i("STTBENCH", "[${ms()}ms] fin de reproducción")
                ui?.poner(est = "⏱ fin del audio — esperando el resultado FINAL…")
                ui?.arrancarTimer()   // el reloj grande pasa a medir la latencia post-audio
                // Si en 4s no llegó el final, forzarlo: stopListening cierra el segmento actual.
                main.postDelayed({ if (fin.count > 0) { Log.i("STTBENCH", "forzando stopListening"); runCatching { rec.stopListening() } } }, 4_000)
                main.postDelayed({ if (fin.count > 0) { Log.i("STTBENCH", "rendido: cierro con lo acumulado"); cerrarSegmento(""); fin.countDown() } }, 15_000)
            }
        }
        val ok = fin.await(120, TimeUnit.SECONDS)
        ui?.let {
            val seg = it.frenarTimer()
            it.poner(est = "✔ FINAL — %.1f s después del fin del audio".format(seg),
                txt = (partes + actual).filter { p -> p.isNotBlank() }.joinToString(" "))
            Thread.sleep(4000)   // que la grabación muestre el resultado congelado
        }
        main.post { runCatching { rec.destroy() }; runCatching { mp?.release() } }
        val texto = partes.joinToString(" ")
        Log.i("STTBENCH", "== FABRICA ($audio, lang=$lang) ==")
        Log.i("STTBENCH", "terminoATiempo=$ok errorFatal=$errorFatal")
        Log.i("STTBENCH", "duracionTotalMs=${tUltimoResultado - t0} " +
            "latenciaTrasFinAudioMs=${if (tPlaybackFin > 0) tUltimoResultado - tPlaybackFin else -1}")
        Log.i("STTBENCH", "texto: $texto")
        check(errorFatal == null) { "SpeechRecognizer error=$errorFatal (12/13 = falta el paquete de idioma offline)" }
    }

    @Test fun appHostGpu() {
        val host = requireNotNull(args.getString("sttHost")) { "falta -e sttHost" }
        val user = requireNotNull(args.getString("sttUser")) { "falta -e sttUser" }
        lanzarUiSiCorresponde("APP → GPU del host")
        val grabador = WavRecorder()
        check(grabador.start()) { "no arrancó el WavRecorder (¿permiso de mic?)" }
        val fin = CountDownLatch(1)
        var tPlaybackFin = 0L
        val main = Handler(Looper.getMainLooper())
        var mp: MediaPlayer? = null
        main.post {
            ui?.poner(est = "▶ audio sonando — el mic graba (WavRecorder, como el botón Dictar)")
            ui?.arrancarTimer()
            mp = reproducir { tPlaybackFin = System.currentTimeMillis(); fin.countDown() }
        }
        check(fin.await(120, TimeUnit.SECONDS)) { "no terminó la reproducción" }
        val wav = requireNotNull(grabador.stop()) { "el grabador no devolvió WAV" }
        main.post { runCatching { mp?.release() } }
        ui?.poner(est = "⏱ soltaste el botón: SSH + subida + GPU + vuelta…", txt = "")
        ui?.arrancarTimer()
        // TOFU del endpoint LAN del experimento: el primer contacto pinea la clave del host
        // (rol que en la app cumple la terminal); transcribe() después sólo compara.
        val pre = com.trilead.ssh2.Connection(host, 22)
        pre.connect(HostKeys.verifier(ctx, host, 22, permitePin = true), 10_000, 10_000)
        pre.close()
        // Desde acá es el camino real del Dictar: soltar el botón → transcribe() → texto.
        val control = RemoteControl(ctx, host, 22, user, KeyStoreSsh.getOrCreateKeyPair())
        val t0 = System.currentTimeMillis()
        val texto = control.transcribe(wav)
        val t1 = System.currentTimeMillis()
        ui?.let {
            val seg = it.frenarTimer()
            it.poner(est = "✔ transcripción lista — %.1f s de ida y vuelta".format(seg), txt = texto)
            Thread.sleep(4000)
        }
        Log.i("STTBENCH", "== APP->HOST GPU ($audio) ==")
        Log.i("STTBENCH", "wavBytes=${wav.size} latenciaRoundTripMs=${t1 - t0} " +
            "(desde fin de audio; incluye SSH+subida+GPU+vuelta)")
        Log.i("STTBENCH", "texto: $texto")
        Log.i("STTBENCH", "nota: tPlaybackFin->stop+transcribe arrancó ${t0 - tPlaybackFin}ms después del fin del audio")
    }
}
