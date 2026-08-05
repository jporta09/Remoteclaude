package com.remoteclaude.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TmuxNameTest {

    /**
     * INVARIANTE CRÍTICA: si esto falla, al actualizar la app el usuario deja de
     * reengancharse a sus sesiones vivas y abre pestañas nuevas vacías.
     */
    @Test fun `los nombres que ya genera la app son punto fijo`() {
        for (n in 1..50) {
            val name = "term $n"
            assertThat(TmuxName.sanitize(name)).isEqualTo(name)
        }
    }

    @Test fun `sanitizar es idempotente`() {
        for (raw in listOf("term 1", "a.b", "x:y", "  hola   mundo  ", "\nlinea\n", "")) {
            val once = TmuxName.sanitize(raw)
            assertThat(TmuxName.sanitize(once)).isEqualTo(once)
        }
    }

    @Test fun `los separadores de target de tmux se reemplazan`() {
        assertThat(TmuxName.sanitize("a.b")).isEqualTo("a-b")
        assertThat(TmuxName.sanitize("a:b")).isEqualTo("a-b")
    }

    @Test fun `los controles no sobreviven`() {
        val out = TmuxName.sanitize("a\nb\tc\rd")
        assertThat(out).isEqualTo("a b c d")
        assertThat(out.none { it.isISOControl() }).isTrue()
    }

    @Test fun `espacios colapsados y recortados`() {
        assertThat(TmuxName.sanitize("   hola    mundo   ")).isEqualTo("hola mundo")
    }

    @Test fun `vacio o solo controles cae al fallback`() {
        assertThat(TmuxName.sanitize("")).isEqualTo("term")
        assertThat(TmuxName.sanitize("   ")).isEqualTo("term")
        assertThat(TmuxName.sanitize("\n\t")).isEqualTo("term")
    }

    @Test fun `se acota el largo y no queda espacio final`() {
        val out = TmuxName.sanitize("x".repeat(100))
        assertThat(out).hasLength(TmuxName.MAX_LEN)
        val cortado = TmuxName.sanitize("a".repeat(TmuxName.MAX_LEN - 1) + "   cola")
        assertThat(cortado.last()).isNotEqualTo(' ')
    }

    @Test fun `la salida siempre es segura para el shell`() {
        for (raw in listOf("x'y", "a\nb", "a.b", "'; touch /tmp/pwned; '")) {
            val name = TmuxName.sanitize(raw)
            // sq() no debe lanzar y el nombre no debe traer controles
            ShellQuote.sq(name)
            assertThat(name.none { it.isISOControl() }).isTrue()
        }
    }
}
