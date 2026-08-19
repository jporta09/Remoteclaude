package com.remoteclaude.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * El contrato de sanitizarDictado(): el texto que devuelve el STT y que va a insertarse en el
 * prompt NUNCA puede llevar un carácter de control. La regresión concreta es el `\n`: sería un
 * Enter IMPLÍCITO que manda el comando sin que el usuario lo revise en el preview.
 */
class SanitizarDictadoTest {

    @Test fun `un salto de linea se vuelve espacio, no Enter`() {
        val limpio = sanitizarDictado("borra todo\nrm -rf")
        assertThat(limpio).doesNotContain("\n")
        assertThat(limpio).isEqualTo("borra todo rm -rf")
    }

    @Test fun `CR, TAB y BEL tambien se limpian`() {
        val bel = '\u0007'
        val limpio = sanitizarDictado("uno\r\ndos\ttres${bel}fin")
        assertThat(limpio).isEqualTo("uno dos tres fin")
    }

    @Test fun `colapsa espacios y recorta los bordes`() {
        assertThat(sanitizarDictado("  hola   marvin  ")).isEqualTo("hola marvin")
    }

    @Test fun `null y vacio dan cadena vacia`() {
        assertThat(sanitizarDictado(null)).isEmpty()
        assertThat(sanitizarDictado("")).isEmpty()
        assertThat(sanitizarDictado("   \n\t ")).isEmpty()
    }

    @Test fun `texto normal con acentos y emoji sobrevive`() {
        assertThat(sanitizarDictado("añadí una función 🎤")).isEqualTo("añadí una función 🎤")
    }
}
