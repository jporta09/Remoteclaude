package com.remoteclaude.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SRE-5-3 (5ª pasada): reintentos con jitter y tope creciente; esperas puras y acotadas. */
class BackoffTest {
    @Test fun `la espera de la terminal es lineal con tope 8 s y jitter entre 50 y 100 por ciento`() {
        assertEquals(500L, esperaRetry(1, 0.0))
        assertTrue(esperaRetry(1, 0.999999) in 999L..1000L)
        assertEquals(4000L, esperaRetry(8, 0.0))     // tope 8 s * 50 %
        assertTrue(esperaRetry(100, 0.5) in 6000L..8000L)
        assertTrue(esperaRetry(0, 0.5) >= 500L)      // attempt 0 se trata como 1
    }

    @Test fun `el backoff de avisos dobla desde 2 s con tope 30 s`() {
        assertTrue(proximoBackoff(2_000L, 1, 0.999999) in 3_999L..4_000L)
        assertEquals(15_000L, proximoBackoff(30_000L, 3, 0.0))   // tope 30 s * 50 %
        assertTrue(proximoBackoff(30_000L, 3, 0.999999) <= 30_000L)
    }

    @Test fun `tras 10 fallos seguidos el tope sube a 5 min`() {
        assertTrue(proximoBackoff(30_000L, 10, 0.999999) in 59_999L..60_000L)
        assertTrue(proximoBackoff(300_000L, 20, 0.999999) <= 300_000L)
        assertTrue(proximoBackoff(300_000L, 20, 0.0) >= 150_000L)
    }

    @Test fun `el jitter nunca baja de la mitad ni pasa la base`() {
        for (r in listOf(0.0, 0.25, 0.5, 0.75, 0.999)) {
            val v = conJitter(10_000L, r)
            assertTrue("rnd=$r dio $v", v in 5_000L..10_000L)
        }
        assertTrue(conJitter(1L, 0.0) >= 1L)
    }
}
