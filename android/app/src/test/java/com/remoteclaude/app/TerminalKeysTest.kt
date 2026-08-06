package com.remoteclaude.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TerminalKeysTest {

    @Test fun `Ctrl-C es 3`() {
        // La razón de ser del teclado extra: interrumpir lo que corre en el host.
        assertThat(TerminalKeys.ctrlByte('c'.code)).isEqualTo(3)
        assertThat(TerminalKeys.ctrlByte('C'.code)).isEqualTo(3)
    }

    @Test fun `las letras cubren 1 a 26`() {
        assertThat(TerminalKeys.ctrlByte('a'.code)).isEqualTo(1)
        assertThat(TerminalKeys.ctrlByte('z'.code)).isEqualTo(26)
    }

    @Test fun `Ctrl-D y Ctrl-L, que son los otros dos que se usan a diario`() {
        assertThat(TerminalKeys.ctrlByte('d'.code)).isEqualTo(4)
        assertThat(TerminalKeys.ctrlByte('l'.code)).isEqualTo(12)
    }

    @Test fun `los controles fuera del rango de letras`() {
        assertThat(TerminalKeys.ctrlByte(' '.code)).isEqualTo(0)
        assertThat(TerminalKeys.ctrlByte('@'.code)).isEqualTo(0)
        assertThat(TerminalKeys.ctrlByte('['.code)).isEqualTo(27)
        assertThat(TerminalKeys.ctrlByte('\\'.code)).isEqualTo(28)
        assertThat(TerminalKeys.ctrlByte(']'.code)).isEqualTo(29)
    }

    @Test fun `un caracter sin control se reporta como tal`() {
        // Tiene que devolver NINGUNO y no un byte cualquiera: si no, con Ctrl activo cada
        // acento o dígito mandaría basura al host en vez del carácter.
        assertThat(TerminalKeys.ctrlByte('1'.code)).isEqualTo(TerminalKeys.NINGUNO)
        assertThat(TerminalKeys.ctrlByte('ñ'.code)).isEqualTo(TerminalKeys.NINGUNO)
    }

    @Test fun `Shift+Tab es la secuencia CSI Z`() {
        assertThat(TerminalKeys.SHIFT_TAB).isEqualTo(byteArrayOf(0x1b, 0x5b, 0x5a))
    }
}
