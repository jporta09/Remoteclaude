package com.remoteclaude.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UX5-4 / SEC5-5: "Confiar en la nueva" exige tipear el final de la huella nueva. */
class ConfirmaHuellaTest {
    private val huella = "SHA256:r8sxVabcdefghijklmnopqrstuvwxyz0123456789ABCD"

    @Test fun `los ultimos 4 (o mas) caracteres confirman`() {
        assertTrue(confirmaHuella("ABCD", huella))
        assertTrue(confirmaHuella(" 89ABCD ", huella))
        assertTrue(confirmaHuella(huella, huella))
    }

    @Test fun `menos de 4, otro tramo o vacio no confirman`() {
        assertFalse(confirmaHuella("BCD", huella))
        assertFalse(confirmaHuella("", huella))
        assertFalse(confirmaHuella("r8sx", huella))
        assertFalse(confirmaHuella("abcd", huella))   // distingue mayúsculas: es base64
    }
}
