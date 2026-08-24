package com.remoteclaude.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** El invariante del botón de dictado: habilitado ⇔ motor con el modelo CARGADO.
 *  `motorDeStt` mapea la respuesta de `despertarStt()` del host; `puedeGrabar` es el gate. */
class MotorSttTest {

    @Test fun `vivo y batch habilitan`() {
        assertThat(motorDeStt("vivo")).isEqualTo(MotorStt.VIVO)
        assertThat(motorDeStt("batch")).isEqualTo(MotorStt.BATCH)
        assertThat(puedeGrabar(MotorStt.VIVO)).isTrue()
        assertThat(puedeGrabar(MotorStt.BATCH)).isTrue()
    }

    @Test fun `sin-stt deshabilita`() {
        assertThat(motorDeStt("sin-stt")).isEqualTo(MotorStt.SIN_STT)
        assertThat(puedeGrabar(MotorStt.SIN_STT)).isFalse()
    }

    @Test fun `timeout y error no son veredicto - quedan PREPARANDO (reintenta la proxima conexion)`() {
        assertThat(motorDeStt("timeout")).isEqualTo(MotorStt.PREPARANDO)
        assertThat(motorDeStt("")).isEqualTo(MotorStt.PREPARANDO)
        assertThat(motorDeStt("basura inesperada")).isEqualTo(MotorStt.PREPARANDO)
        assertThat(puedeGrabar(MotorStt.PREPARANDO)).isFalse()
    }

    @Test fun `la respuesta se toleran con espacios`() {
        assertThat(motorDeStt(" vivo \n")).isEqualTo(MotorStt.VIVO)
    }
}
