package com.remoteclaude.app

/**
 * Cabecera WAV (RIFF/PCM 16 bit mono) para el audio del dictado.
 *
 * Vive aparte de [WavRecorder] porque es aritmética pura: así se puede testear sin
 * micrófono ni dispositivo, que es donde un error de offset pasa desapercibido y termina
 * en "el host no entiende el audio".
 */
object WavHeader {

    const val SIZE = 44
    const val RATE = 16000
    private const val CHANNELS = 1
    private const val BITS = 16

    private fun le32(v: Int) = byteArrayOf(
        (v and 0xff).toByte(), (v shr 8 and 0xff).toByte(),
        (v shr 16 and 0xff).toByte(), (v shr 24 and 0xff).toByte(),
    )

    private fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), (v shr 8 and 0xff).toByte())

    /** Cabecera de 44 bytes para [dataLen] bytes de PCM. */
    fun of(dataLen: Int): ByteArray {
        val byteRate = RATE * CHANNELS * BITS / 8
        val blockAlign = CHANNELS * BITS / 8
        return "RIFF".toByteArray() + le32(36 + dataLen) + "WAVE".toByteArray() +
            "fmt ".toByteArray() + le32(16) + le16(1) + le16(CHANNELS) +
            le32(RATE) + le32(byteRate) + le16(blockAlign) + le16(BITS) +
            "data".toByteArray() + le32(dataLen)
    }
}
