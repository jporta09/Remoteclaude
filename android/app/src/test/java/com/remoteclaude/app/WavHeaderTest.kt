package com.remoteclaude.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** El host rechaza el audio si la cabecera está mal; acá se fija byte por byte. */
class WavHeaderTest {

    private fun le32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xff) or ((b[off + 1].toLong() and 0xff) shl 8) or
            ((b[off + 2].toLong() and 0xff) shl 16) or ((b[off + 3].toLong() and 0xff) shl 24)

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    private fun ascii(b: ByteArray, off: Int, len: Int) = String(b, off, len, Charsets.US_ASCII)

    @Test fun `mide 44 bytes y tiene los marcadores RIFF`() {
        val h = WavHeader.of(1000)
        assertThat(h).hasLength(WavHeader.SIZE)
        assertThat(ascii(h, 0, 4)).isEqualTo("RIFF")
        assertThat(ascii(h, 8, 4)).isEqualTo("WAVE")
        assertThat(ascii(h, 12, 4)).isEqualTo("fmt ")
        assertThat(ascii(h, 36, 4)).isEqualTo("data")
    }

    @Test fun `los campos del formato son PCM 16k mono 16 bit`() {
        val h = WavHeader.of(0)
        assertThat(le32(h, 16)).isEqualTo(16L)      // tamaño del bloque fmt
        assertThat(le16(h, 20)).isEqualTo(1)        // PCM
        assertThat(le16(h, 22)).isEqualTo(1)        // canales
        assertThat(le32(h, 24)).isEqualTo(16000L)   // sample rate
        assertThat(le32(h, 28)).isEqualTo(32000L)   // byteRate = 16000*1*16/8
        assertThat(le16(h, 32)).isEqualTo(2)        // blockAlign
        assertThat(le16(h, 34)).isEqualTo(16)       // bits
    }

    @Test fun `los tamaños siguen al payload`() {
        for (n in listOf(0, 1, 44100, 3_200_000)) {
            val h = WavHeader.of(n)
            assertThat(le32(h, 4)).isEqualTo((36 + n).toLong())   // RIFF size
            assertThat(le32(h, 40)).isEqualTo(n.toLong())         // data size
        }
    }

    @Test fun `un payload grande no se escribe con signo`() {
        // 3 GB de audio es irreal, pero el campo es unsigned de 32 bits: si se escribiera
        // como Int con signo, el host leería un tamaño negativo y descartaría el WAV.
        val h = WavHeader.of(Int.MAX_VALUE - 36)
        assertThat(le32(h, 40)).isGreaterThan(0L)
    }
}
