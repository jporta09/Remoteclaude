package com.remoteclaude.app

import org.json.JSONObject

/**
 * Parseo de los mensajes de WhisperLiveKit (dictado en vivo).
 *
 * Formato relevado del server: `{"status":"active_transcription",
 * "lines":[{"text":…}], "buffer_transcription":"…"}` — `lines` es lo confirmado y
 * `buffer_transcription` lo provisional. El fin del audio se avisa con
 * `{"type":"ready_to_stop"}`.
 *
 * Separado del transporte para poder testear el protocolo sin túnel ni WebSocket.
 */
object WlkSnapshot {

    /** Resultado del parseo: [text] es el texto completo, o null si el mensaje no lo trae. */
    data class Parsed(val text: String?, val readyToStop: Boolean)

    fun parse(json: String): Parsed = try {
        val j = JSONObject(json)
        if (j.optString("type") == "ready_to_stop") {
            Parsed(null, true)
        } else {
            val lines = j.optJSONArray("lines")
            if (lines == null) {
                Parsed(null, false)
            } else {
                val sb = StringBuilder()
                for (i in 0 until lines.length()) {
                    val t = lines.optJSONObject(i)?.optString("text")?.trim().orEmpty()
                    if (t.isNotEmpty()) { if (sb.isNotEmpty()) sb.append(' '); sb.append(t) }
                }
                val buffer = j.optString("buffer_transcription").trim()
                val full = (sb.toString() + if (buffer.isNotEmpty()) " $buffer" else "").trim()
                Parsed(full, false)
            }
        }
    } catch (_: Exception) {
        Parsed(null, false)   // mensaje no-JSON o schema inesperado: se ignora
    }
}
