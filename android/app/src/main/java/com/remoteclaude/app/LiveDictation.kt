package com.remoteclaude.app

import com.trilead.ssh2.Connection
import com.trilead.ssh2.LocalPortForwarder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.ServerSocket
import java.security.KeyPair
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Sesión de dictado EN VIVO (fase 2): streamea el PCM 16k del micrófono por un túnel
 * SSH (LocalPortForwarder) al WhisperLiveKit del host (127.0.0.1:6092) y va recibiendo
 * el texto parcial. Best-effort: si CUALQUIER paso falla, el caller sigue con el
 * flujo batch de fase 1 (nunca se pierde un dictado).
 *
 * Protocolo WLK (relevado): el server manda snapshots JSON
 *   {"status":"active_transcription","lines":[{"text":…}],"buffer_transcription":…}
 * (lines = confirmado, buffer = provisional). Fin de audio = frame binario VACÍO;
 * el server responde {"type":"ready_to_stop"} tras el flush final.
 */
class LiveDictation(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val key: KeyPair,
    private val onPartial: (String) -> Unit,
) {
    @Volatile var available = false
        private set

    private var conn: Connection? = null
    private var fwd: LocalPortForwarder? = null
    private var ws: WebSocket? = null
    private val pending = ArrayList<ByteArray>()   // chunks previos a que el WS abra
    private val stopped = CountDownLatch(1)
    @Volatile private var committed = ""
    @Volatile private var buffer = ""

    /** Abre túnel + WebSocket. BLOQUEA (~1-2s); llamar en un thread. */
    fun start(): Boolean {
        try {
            val (h, p) = TailscaleBridge.endpoint(host, port)
            val c = Connection(h, p)
            c.connect({ _, _, _, _ -> true }, 8000, 8000)
            if (!c.authenticateWithPublicKey(user, key)) { c.close(); return false }
            conn = c
            val local = ServerSocket(0).use { it.localPort }   // puerto libre (carrera improbable)
            fwd = c.createLocalPortForwarder(local, "127.0.0.1", 6092)
            val client = OkHttpClient.Builder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)   // stream sin límite
                .build()
            val opened = CountDownLatch(1)
            var ok = false
            val sock = client.newWebSocket(
                Request.Builder().url("ws://127.0.0.1:$local/asr").build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        ok = true; opened.countDown()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        handleMessage(text)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        available = false
                        opened.countDown()
                        stopped.countDown()
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        stopped.countDown()
                    }
                },
            )
            opened.await(5, TimeUnit.SECONDS)
            if (!ok) { closeQuiet(); return false }
            ws = sock
            synchronized(pending) {
                pending.forEach { sock.send(it.toByteString()) }
                pending.clear()
            }
            available = true
            return true
        } catch (_: Exception) {
            closeQuiet()
            return false
        }
    }

    private fun handleMessage(text: String) {
        try {
            val j = JSONObject(text)
            if (j.optString("type") == "ready_to_stop") { stopped.countDown(); return }
            val lines = j.optJSONArray("lines") ?: return
            val sb = StringBuilder()
            for (i in 0 until lines.length()) {
                val t = lines.getJSONObject(i).optString("text").trim()
                if (t.isNotEmpty()) { if (sb.isNotEmpty()) sb.append(' '); sb.append(t) }
            }
            committed = sb.toString()
            buffer = j.optString("buffer_transcription").trim()
            val shown = (committed + if (buffer.isNotEmpty()) " $buffer" else "").trim()
            if (shown.isNotEmpty()) onPartial(shown)
        } catch (_: Exception) {
            // mensaje no-JSON o schema inesperado: se ignora (best-effort)
        }
    }

    /** Chunk de PCM del micrófono. Si el WS aún no abrió, se encola (no se pierde audio). */
    fun feed(chunk: ByteArray) {
        val w = ws
        if (w != null) {
            w.send(chunk.toByteString())
        } else {
            synchronized(pending) { if (pending.size < 400) pending.add(chunk) }  // tope ~2MB
        }
    }

    /**
     * Fin del dictado: manda el frame vacío, espera el flush final (~4s máx) y devuelve
     * el texto completo, o null si no hubo nada utilizable. BLOQUEA; llamar en thread.
     */
    fun stop(): String? {
        val w = ws ?: return null
        try { w.send(ByteString.EMPTY) } catch (_: Exception) {}
        stopped.await(4, TimeUnit.SECONDS)
        closeQuiet()
        val text = (committed + if (buffer.isNotEmpty()) " $buffer" else "").trim()
        return text.ifBlank { null }
    }

    /** Aborta sin esperar nada (cancelación). */
    fun cancel() = closeQuiet()

    private fun closeQuiet() {
        available = false
        try { ws?.close(1000, null) } catch (_: Exception) {}
        try { fwd?.close() } catch (_: Exception) {}
        try { conn?.close() } catch (_: Exception) {}
        ws = null; fwd = null; conn = null
    }
}
