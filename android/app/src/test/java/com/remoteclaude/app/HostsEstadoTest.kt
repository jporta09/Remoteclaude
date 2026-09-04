package com.remoteclaude.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** UF5-2 (5ª pasada): el vencido tiene que ganarle al verde; el resto del orden se conserva. */
class HostsEstadoTest {
    @Test fun `sin tailscale embebido es directa en muted`() {
        val (t, tono) = lineaTailscale(enabled = false, vencido = true, ready = true, hayError = true)
        assertTrue(t.contains("directa")); assertEquals(TonoLinea.MUTED, tono)
    }

    @Test fun `vencido gana aunque el nodo diga ready`() {
        val (t, tono) = lineaTailscale(enabled = true, vencido = true, ready = true, hayError = false)
        assertTrue(t.contains("enrolamiento vencido")); assertTrue(t.contains("LAN siguen"))
        assertEquals(TonoLinea.AMBER, tono)
    }

    @Test fun `ready sin vencido es verde y muestra la tailnet si se conoce`() {
        val (t, tono) = lineaTailscale(enabled = true, vencido = false, ready = true, hayError = false)
        assertTrue(t.contains("conectada ✓")); assertEquals(TonoLinea.GREEN, tono)
        val (t2, _) = lineaTailscale(enabled = true, vencido = false, ready = true, hayError = false, tailnet = "juan.github")
        assertTrue(t2, t2.endsWith("conectada ✓ · juan.github"))
    }

    @Test fun `error sin ready es ambar y no filtra el error crudo`() {
        val (t, tono) = lineaTailscale(enabled = true, vencido = false, ready = false, hayError = true)
        assertTrue(t.contains("no se pudo conectar")); assertEquals(TonoLinea.AMBER, tono)
    }

    @Test fun `sin nada es conectando`() {
        val (t, _) = lineaTailscale(enabled = true, vencido = false, ready = false, hayError = false)
        assertTrue(t.contains("conectando"))
    }
}
