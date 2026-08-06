package com.remoteclaude.app

/**
 * Traducción de teclas a los bytes que espera la terminal.
 *
 * Estaba adentro del TerminalViewClient anónimo de MainActivity, o sea que verificar que
 * Ctrl-C manda 0x03 requería un emulador con una sesión SSH viva.
 */
object TerminalKeys {

    /** No hay byte de control para ese carácter. */
    const val NINGUNO = -1

    /**
     * Byte que corresponde a Ctrl + [codePoint], o [NINGUNO].
     *
     * Las letras van a 1..26 (Ctrl-A = 0x01), y de ahí sale Ctrl-C = 3, que es la razón de
     * ser de todo esto. El resto son los controles que el rango de letras no cubre.
     */
    fun ctrlByte(codePoint: Int): Int = when (codePoint) {
        in 'a'.code..'z'.code -> codePoint - 'a'.code + 1
        in 'A'.code..'Z'.code -> codePoint - 'A'.code + 1
        ' '.code, '@'.code -> 0
        '['.code -> 27
        '\\'.code -> 28
        ']'.code -> 29
        else -> NINGUNO
    }

    /** Shift+Tab = back-tab (CSI Z): la secuencia que Claude lee para cambiar de modo. */
    val SHIFT_TAB = byteArrayOf(0x1b, '['.code.toByte(), 'Z'.code.toByte())

    /** ESC, el prefijo con el que se manda Alt+tecla. */
    val ESC = byteArrayOf(27)
}
