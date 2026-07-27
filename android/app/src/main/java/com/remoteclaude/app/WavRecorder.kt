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
    private var record: AudioRecord? = null
    private var worker: Thread? = null
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
        worker = thread(name = "wav-rec") {
            val buf = ByteArray(4096)
            while (record === r) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) {
                    synchronized(pcm) { pcm.write(buf, 0, n) }
                    onChunk?.invoke(buf.copyOf(n))   // streaming en vivo (fase 2)
                }
            }
        }
        return true
    }

    /** Para y devuelve el WAV completo (o null si no había grabación / quedó vacía). */
    fun stop(): ByteArray? {
        val r = record ?: return null
        record = null                      // corta el loop del worker
        worker?.join(500)
        try { r.stop() } catch (_: Exception) {}
        r.release()
        val data = synchronized(pcm) { pcm.toByteArray() }
        if (data.size < RATE / 4) return null   // < ~0.12s: ruido de un toque
        return wavHeader(data.size) + data
    }

    fun cancel() {
        val r = record ?: return
        record = null
        worker?.join(500)
        try { r.stop() } catch (_: Exception) {}
        r.release()
    }

    private fun wavHeader(dataLen: Int): ByteArray {
        val byteRate = RATE * 2
        fun le32(v: Int) = byteArrayOf(
            (v and 0xff).toByte(), (v shr 8 and 0xff).toByte(),
            (v shr 16 and 0xff).toByte(), (v shr 24 and 0xff).toByte(),
        )
        fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte())
        return "RIFF".toByteArray() + le32(36 + dataLen) + "WAVE".toByteArray() +
            "fmt ".toByteArray() + le32(16) + le16(1) + le16(1) +
            le32(RATE) + le32(byteRate) + le16(2) + le16(16) +
            "data".toByteArray() + le32(dataLen)
    }

    private companion object {
        const val RATE = 16000
        const val CH = AudioFormat.CHANNEL_IN_MONO
        const val ENC = AudioFormat.ENCODING_PCM_16BIT
    }
}
