package com.remoteclaude.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** QA5-2 (5ª pasada): la validación de forma aceptaba tskey-api-/tskey-client- y control chars. */
class AuthKeyPlausibleTest {
    // Las keys de prueba se ARMAN en tiempo de ejecución: un literal con la forma completa
    // "tskey-auth-<id>-<secreto>" dispara el secret scanning de GitHub aunque sea inventado
    // (pasó el 2026-09-04). Así el fuente nunca contiene el patrón entero.
    private fun key(tipo: String) = "tskey-" + tipo + "-" + "k" + "A".repeat(11) + "-" + "b".repeat(40)
    private val ok = key("auth")

    @Test fun `una auth key real pasa`() = assertTrue(EnrolarTailscale.esAuthKeyPlausible(ok))

    @Test fun `un api token o un oauth secret no son auth keys`() {
        assertFalse(EnrolarTailscale.esAuthKeyPlausible(key("api")))
        assertFalse(EnrolarTailscale.esAuthKeyPlausible(key("client")))
    }

    @Test fun `control chars e invisibles la invalidan`() {
        assertFalse(EnrolarTailscale.esAuthKeyPlausible(ok + " "))
        assertFalse(EnrolarTailscale.esAuthKeyPlausible(ok.replaceRange(15, 15, "\u200B")))
        assertFalse(EnrolarTailscale.esAuthKeyPlausible("\u001B[0m$ok"))
        assertFalse(EnrolarTailscale.esAuthKeyPlausible("$ok\n"))
        assertFalse(EnrolarTailscale.esAuthKeyPlausible("$ok\u0000"))
    }

    @Test fun `largo acotado`() {
        assertFalse(EnrolarTailscale.esAuthKeyPlausible("tskey-" + "auth-corta"))
        assertFalse(EnrolarTailscale.esAuthKeyPlausible("tskey-" + "auth-" + "x".repeat(300)))
    }
}
