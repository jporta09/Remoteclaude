package com.remoteclaude.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * El pellizco tiene que ser SIMÉTRICO. El bug que motivó estos tests hacía que la letra se
 * achicara con cualquier roce y después no hubiera forma práctica de agrandarla: el tamaño
 * se guardaba en Int y `toInt()` trunca hacia abajo, así que los incrementos chicos que
 * entrega el detector de gestos se perdían al agrandar y se aplicaban enteros al achicar.
 */
class ZoomTest {

    private val min = 8f
    private val max = 28f

    /** Un pellizco real: muchos eventos chicos, no uno grande. */
    private fun gesto(desde: Float, paso: Float, veces: Int): Float {
        var f = desde
        repeat(veces) { f = Zoom.escalar(f, paso, min, max) }
        return f
    }

    @Test fun muchosPasosChicosAgrandanDeVerdad() {
        // Ésta es la regresión: con Int, 20 pasos de +2% no movían la aguja ni un píxel.
        val inicial = 15f
        val final = gesto(inicial, 1.02f, 20)
        assertThat(Zoom.aPixeles(final)).isGreaterThan(Zoom.aPixeles(inicial))
    }

    @Test fun agrandarYAchicarCuestanLoMismo() {
        // 20 pasos de +2% y después 20 de -2% tienen que volver al punto de partida. Con
        // truncado, la vuelta quedaba varios píxeles por debajo: cada gesto perdía terreno.
        val inicial = 15f
        val ida = gesto(inicial, 1.02f, 20)
        val vuelta = gesto(ida, 1f / 1.02f, 20)
        assertThat(Zoom.aPixeles(vuelta)).isEqualTo(Zoom.aPixeles(inicial))
    }

    @Test fun desdeElMinimoSePuedeSalir() {
        // El caso que reportó el usuario: quedó en la fuente más chica y no podía volver.
        val final = gesto(min, 1.02f, 15)
        assertThat(Zoom.aPixeles(final)).isGreaterThan(Zoom.aPixeles(min))
    }

    @Test fun respetaLosTopes() {
        assertThat(gesto(20f, 1.5f, 30)).isEqualTo(max)
        assertThat(gesto(20f, 0.5f, 30)).isEqualTo(min)
    }

    @Test fun redondeaEnVezDeTruncar() {
        assertThat(Zoom.aPixeles(15.6f)).isEqualTo(16)
        assertThat(Zoom.aPixeles(15.4f)).isEqualTo(15)
    }
}
