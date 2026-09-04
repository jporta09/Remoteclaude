package com.remoteclaude.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** QA5-2 (5ª pasada): la validación de forma aceptaba tskey-api-/tskey-client- y control chars. */
class AuthKeyPlausibleTest {
    private val ok = "tskey-auth-kAbCdEf1CNTRL-abcdefghijklmnopqrstuvwxyz0123456789"

    @Test fun `una auth key real pasa`() = assertTrue(EnrolarTailscale.esAuthKeyPlausible(ok))

    @Test fun `un api token o un oauth secret no son auth keys`() {
        assertFalse(EnrolarTailscale.esAuthKeyPlausible("tskey-api-kAbCdEf1CNTRL-abcdefghijklmnopqrstuvwxyz"))
        assertFalse(EnrolarTailscale.esAuthKeyPlausible("tskey-client-kAbCdEf1CNTRL-abcdefghijklmnopqrstuvwxyz"))
    }

    @Test fun `control chars e invisibles la invalidan`() {
        assertFalse(EnrolarTailscale.esAuthKeyPlausible(ok + " "))
        assertFalse(EnrolarTailscale.esAuthKeyPlausible(ok.replaceRange(15, 15, "\u200B")))
        assertFalse(EnrolarTailscale.esAuthKeyPlausible("\u001B[0m$ok"))
        assertFalse(EnrolarTailscale.esAuthKeyPlausible("$ok\n"))
        assertFalse(EnrolarTailscale.esAuthKeyPlausible("$ok\u0000"))
    }

    @Test fun `largo acotado`() {
        assertFalse(EnrolarTailscale.esAuthKeyPlausible("tskey-auth-corta"))
        assertFalse(EnrolarTailscale.esAuthKeyPlausible("tskey-auth-" + "x".repeat(300)))
    }
}
