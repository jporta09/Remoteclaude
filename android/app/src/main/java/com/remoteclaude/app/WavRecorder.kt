package com.remoteclaude.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/**
 * Grabador push-to-talk para el dictado: 16 kHz mono PCM16 -> bytes WAV en memoria.
 * (16 kHz es lo que espera Whisper; ~32 KB/s, un dictado de 15 s son ~480 KB.)
 */
class WavRecorder {
    // @Volatile: el worker lee `record` en su loop y el hilo principal lo pone en null.
    @Volatile private var record: AudioRecord? = null
    @Volatile private var worker: Thread? = null
    private val pcm = ByteArrayOutputStream()

    val isRecording get() = record != null

    @SuppressLint("MissingPermission")  // el caller pide RECORD_AUDIO antes
    fun start(onChunk: ((ByteArray) -> Unit)? = null): Boolean {
        if (record != null) return true
        val minBuf = AudioRecord.getMinBufferSize(RATE, CH, ENC)
        if (minBuf <= 0) return false
        val r = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, CH, ENC, minBuf * 4)
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
        pcm.reset()
        r.startRecording()
        record = r
        // El worker es DUEÑO del AudioRecord: lo libera él al salir. Antes lo liberaba
        // stop() aunque el join se agotara, y el worker seguía leyendo un objeto liberado
        // -> IllegalStateException en un hilo sin catch = app muerta.
        worker = thread(name = "wav-rec") {
            val buf = ByteArray(4096)
            try {
                while (record === r) {
                    val n = r.read(buf, 0, buf.size)
                    if (n > 0) {
                        synchronized(pcm) { pcm.write(buf, 0, n) }
                        onChunk?.invoke(buf.copyOf(n))   // streaming en vivo (fase 2)
                    }
                }
            } catch (_: Throwable) {
                // el micrófono se cortó o el WS se cerró: no arrastramos la app
            } finally {
                try { r.stop() } catch (_: Exception) {}
                r.release()
            }
        }
        return true
    }

    /** Para y devuelve el WAV completo (o null si no había grabación / quedó vacía). */
    fun stop(): ByteArray? {
        record ?: return null
        record = null                      // corta el loop del worker (que libera el mic)
        worker?.join(1000)
        val data = synchronized(pcm) { pcm.toByteArray() }
        if (data.size < RATE / 4) return null   // < ~0.12s: ruido de un toque
        return WavHeader.of(data.size) + data
    }

    fun cancel() {
        record ?: return
        record = null
        worker?.join(1000)
    }

    private companion object {
        const val RATE = 16000
        const val CH = AudioFormat.CHANNEL_IN_MONO
        const val ENC = AudioFormat.ENCODING_PCM_16BIT
    }
}
