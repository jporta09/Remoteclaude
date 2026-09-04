package com.remoteclaude.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * La decisión del pinning. Es la parte que importa para la seguridad: cuándo se acepta
 * una clave nueva y cuándo hay que cortar.
 */
class HostKeysTest {

    private val A = "AAAAC3NzaC1lZDI1NTE5AAAAIExampleKeyAAAA"
    private val B = "AAAAC3NzaC1lZDI1NTE5AAAAIOtherKeyBBBBBB"

    @Test fun `la primera vez se fija (TOFU)`() {
        assertThat(HostKeys.decide(null, A)).isEqualTo(HostKeys.Decision.PIN)
    }

    @Test fun `la misma clave se acepta`() {
        assertThat(HostKeys.decide(A, A)).isEqualTo(HostKeys.Decision.MATCH)
    }

    @Test fun `una clave distinta es mismatch (se corta la conexión)`() {
        assertThat(HostKeys.decide(A, B)).isEqualTo(HostKeys.Decision.MISMATCH)
    }

    @Test fun `no alcanza con parecerse`() {
        assertThat(HostKeys.decide(A, A.dropLast(1))).isEqualTo(HostKeys.Decision.MISMATCH)
        assertThat(HostKeys.decide(A, A + "=")).isEqualTo(HostKeys.Decision.MISMATCH)
        assertThat(HostKeys.decide(A, A.uppercase())).isEqualTo(HostKeys.Decision.MISMATCH)
    }

    @Test fun `una clave vacia guardada no se confunde con "sin fijar"`() {
        // "" es un valor guardado real, no la ausencia de pin: si el server manda vacío
        // (imposible en la práctica) debe seguir siendo MATCH, y cualquier otra, MISMATCH.
        assertThat(HostKeys.decide("", "")).isEqualTo(HostKeys.Decision.MATCH)
        assertThat(HostKeys.decide("", A)).isEqualTo(HostKeys.Decision.MISMATCH)
    }

    // --- A5-2 (5ª pasada): la variante dura la decide la RED, no un timer ------------------

    @Test fun `mismatch con la tailnet cambiada es red distinta`() {
        assertThat(HostKeys.redDistinta("juan.github", "atacante.github")).isTrue()
    }

    @Test fun `misma tailnet o sin identidad conocida no afirma nada`() {
        assertThat(HostKeys.redDistinta("juan.github", "juan.github")).isFalse()
        assertThat(HostKeys.redDistinta(null, "juan.github")).isFalse()
        assertThat(HostKeys.redDistinta("", "juan.github")).isFalse()
        assertThat(HostKeys.redDistinta("juan.github", "")).isFalse()   // modo directo / sin nodo aún
    }
}
