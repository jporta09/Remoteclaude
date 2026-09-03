package com.remoteclaude.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A4-1 (v1.31.1): el diálogo "la clave del host cambió" NO debe presentarse como "esperable" si
 * hubo un re-enrol de Tailscale hace poco, por CUALQUIERA de los tres caminos (QR terminal, QR
 * hosts, key pegada). La ventana la decide esta función pura; el marcador lo pone
 * TailscaleBridge.configure() con key no vacía.
 */
class ReEnrolRecienteTest {

    @Test fun `sin re-enrol nunca es reciente`() {
        assertThat(reEnrolRecienteDesde(0L, 100_000L, VENTANA_RE_ENROL_MS)).isFalse()
    }

    @Test fun `recien re-enrolado es reciente`() {
        assertThat(reEnrolRecienteDesde(100_000L, 100_000L, VENTANA_RE_ENROL_MS)).isTrue()
        assertThat(reEnrolRecienteDesde(100_000L, 100_000L + 59_999L, VENTANA_RE_ENROL_MS)).isTrue()
    }

    @Test fun `pasada la ventana deja de ser reciente`() {
        assertThat(reEnrolRecienteDesde(100_000L, 100_000L + 60_000L, VENTANA_RE_ENROL_MS)).isFalse()
        assertThat(reEnrolRecienteDesde(100_000L, 100_000L + 3_600_000L, VENTANA_RE_ENROL_MS)).isFalse()
    }

    @Test fun `un reloj que retrocede no cuenta como reciente`() {
        assertThat(reEnrolRecienteDesde(100_000L, 90_000L, VENTANA_RE_ENROL_MS)).isFalse()
    }
}
