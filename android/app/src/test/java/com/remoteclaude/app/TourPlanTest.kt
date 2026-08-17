package com.remoteclaude.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** La parte pura de la demo de primer uso: elegir paso y ubicar la burbuja. */
class TourPlanTest {

    @Test fun `salta los pasos sin blanco disponible`() {
        // El caso real: la fila Shift está vacía porque el teclado del sistema subió.
        assertThat(Tour.proximoPaso(listOf(true, false, false, true), 1)).isEqualTo(3)
    }

    @Test fun `sin pasos mostrables devuelve -1 y la demo cierra`() {
        assertThat(Tour.proximoPaso(listOf(true, false), 1)).isEqualTo(-1)
        assertThat(Tour.proximoPaso(emptyList(), 0)).isEqualTo(-1)
    }

    @Test fun `no repite pasos anteriores`() {
        assertThat(Tour.proximoPaso(listOf(true, true, true), 2)).isEqualTo(2)
    }

    @Test fun `la burbuja va debajo del blanco si entra`() {
        // Blanco arriba (una barra): sobra lugar abajo.
        assertThat(Tour.posicionBurbuja(100, 160, 2000, 300, 12)).isEqualTo(172)
    }

    @Test fun `si no entra abajo va arriba`() {
        // Blanco pegado al piso (el keypad): la burbuja tiene que subir.
        assertThat(Tour.posicionBurbuja(1700, 1950, 2000, 300, 12)).isEqualTo(1700 - 12 - 300)
    }

    @Test fun `blanco gigante deja la burbuja centrada adentro de la pantalla`() {
        // La terminal ocupa casi todo: ni arriba ni abajo hay lugar — centrada y visible.
        val y = Tour.posicionBurbuja(50, 1990, 2000, 300, 12)
        assertThat(y).isAtLeast(0)
        assertThat(y + 300).isAtMost(2000)
    }
}
