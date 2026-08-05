package com.remoteclaude.app

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * El contrato de sq(): lo que devuelve, pasado a `sh -c "echo <eso>"`, tiene que
 * imprimir EXACTAMENTE la entrada. Acá se simula esa semántica desarmando el
 * comillado, que es lo mismo que hace el shell.
 */
class ShellQuoteTest {

    /** Interpreta un literal comillado POSIX como lo haría el shell. */
    private fun shellUnquote(quoted: String): String {
        val sb = StringBuilder()
        var i = 0
        var inSingle = false
        while (i < quoted.length) {
            val c = quoted[i]
            when {
                c == '\'' && !inSingle -> { inSingle = true; i++ }
                c == '\'' && inSingle -> { inSingle = false; i++ }
                c == '\\' && !inSingle -> { sb.append(quoted[i + 1]); i += 2 }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    private fun roundTrip(s: String) {
        assertThat(shellUnquote(ShellQuote.sq(s))).isEqualTo(s)
    }

    @Test fun `texto simple sobrevive el round trip`() = roundTrip("term 1")

    @Test fun `la comilla simple no rompe el literal`() {
        roundTrip("x'y")
        // y explícitamente: no debe quedar una comilla suelta que cierre el comando
        assertThat(ShellQuote.sq("x'y")).isEqualTo("'x'\\''y'")
    }

    @Test fun `intento de inyeccion queda inerte`() {
        val malicioso = "x'; touch /tmp/pwned; '"
        roundTrip(malicioso)
        // el resultado es UN literal: no hay ';' fuera de comillas
        val q = ShellQuote.sq(malicioso)
        assertThat(shellUnquote(q)).doesNotContain("pwned; ;")
        assertThat(q.startsWith("'")).isTrue()
        assertThat(q.endsWith("'")).isTrue()
    }

    @Test fun `metacaracteres del shell quedan literales`() {
        for (s in listOf("a\$HOME", "a`id`", "a;b", "a&&b", "a|b", "a>b", "a*b", "a\\b")) {
            roundTrip(s)
        }
    }

    @Test fun `saltos de linea y tabuladores se comillan bien`() {
        roundTrip("a\nb")
        roundTrip("a\r\nb")
        roundTrip("a\tb")
    }

    @Test fun `string vacio y unicode`() {
        roundTrip("")
        roundTrip("acentos áéíóú ñ y emoji 🎤")
    }

    @Test fun `NUL se rechaza`() {
        val conNul = "a" + 0.toChar() + "b"
        assertThrows(IllegalArgumentException::class.java) { ShellQuote.sq(conNul) }
    }
}
