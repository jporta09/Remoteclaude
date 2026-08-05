package com.remoteclaude.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Protocolo del dictado en vivo, sin túnel ni WebSocket. */
class WlkSnapshotTest {

    @Test fun `solo lines confirmadas`() {
        val p = WlkSnapshot.parse("""{"lines":[{"text":" hola"},{"text":"mundo "}]}""")
        assertThat(p.text).isEqualTo("hola mundo")
        assertThat(p.readyToStop).isFalse()
    }

    @Test fun `lines mas buffer provisional`() {
        val p = WlkSnapshot.parse(
            """{"lines":[{"text":"hola"}],"buffer_transcription":" mundo"}"""
        )
        assertThat(p.text).isEqualTo("hola mundo")
    }

    @Test fun `lines vacio no rompe`() {
        assertThat(WlkSnapshot.parse("""{"lines":[]}""").text).isEmpty()
        assertThat(WlkSnapshot.parse("""{"lines":[{"text":"  "}]}""").text).isEmpty()
    }

    @Test fun `ready_to_stop se reconoce`() {
        val p = WlkSnapshot.parse("""{"type":"ready_to_stop"}""")
        assertThat(p.readyToStop).isTrue()
        assertThat(p.text).isNull()
    }

    @Test fun `un mensaje sin lines no pisa lo que ya habia`() {
        // text=null significa "no actualices"; si devolviera "" borraría la transcripción
        // en curso cada vez que llega un mensaje de estado.
        assertThat(WlkSnapshot.parse("""{"status":"no_audio_detected"}""").text).isNull()
    }

    @Test fun `basura no explota`() {
        for (s in listOf("", "no soy json", "{", "[]", """{"lines":"no-es-array"}""")) {
            val p = WlkSnapshot.parse(s)
            assertThat(p.readyToStop).isFalse()
        }
    }
}
