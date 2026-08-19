package com.remoteclaude.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * El parseo del estado del nodo Tailscale (fila 550): distinguir "el acceso venció, reescaneá el
 * QR" de una caída de red pasajera. Sólo NeedsLogin o expired=1 cuentan como vencido; nada más
 * (ni "Running", ni "Directo", ni un string roto) debe gritar "reescaneá".
 */
class AccesoVencidoTest {

    @Test fun `NeedsLogin es acceso vencido`() {
        assertThat(accesoVencidoDeEstado("NeedsLogin;0;0")).isTrue()
    }

    @Test fun `expired=1 es acceso vencido aunque el backend diga otra cosa`() {
        assertThat(accesoVencidoDeEstado("Running;1;1699999999")).isTrue()
    }

    @Test fun `un nodo Running y sano no esta vencido`() {
        assertThat(accesoVencidoDeEstado("Running;0;0")).isFalse()
    }

    @Test fun `modo directo (sin nodo embebido) no esta vencido`() {
        assertThat(accesoVencidoDeEstado("Directo;0;0")).isFalse()
    }

    @Test fun `estados transitorios no gritan reescanea`() {
        for (s in listOf("Starting;0;0", "Stopped;0;0", "Desconocido;0;0", "Detenido;0;0")) {
            assertThat(accesoVencidoDeEstado(s)).isFalse()
        }
    }

    @Test fun `un string roto no dispara falso positivo`() {
        for (s in listOf("", "basura", ";;", "NeedsLoginXtra;0;0")) {
            assertThat(accesoVencidoDeEstado(s)).isFalse()
        }
    }
}
