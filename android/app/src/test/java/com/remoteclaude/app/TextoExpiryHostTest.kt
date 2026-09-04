package com.remoteclaude.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SRE-5-1 / UX5-6: el aviso del nodo de la PC a partir del filtro canónico, con glosario único. */
class TextoExpiryHostTest {
    @Test fun `norun y negativo avisan que ya vencio o no esta activo`() {
        assertTrue(textoExpiryHost("NORUN", 21)!!.contains("no está activo"))
        assertTrue(textoExpiryHost("-4", 21)!!.contains("YA VENCIÓ hace 4 día"))
    }

    @Test fun `dentro del umbral avisa con los dias, fuera no`() {
        assertTrue(textoExpiryHost("9", 21)!!.contains("vence en 9 día"))
        assertTrue(textoExpiryHost("21", 21)!!.contains("vence en 21 día"))
        assertEquals(null, textoExpiryHost("22", 21))
    }

    @Test fun `tokens no numericos no avisan`() {
        for (t in listOf("TAGGED", "NOEXP", "SINSELF", "", "basura")) assertEquals(t, null, textoExpiryHost(t, 21))
    }

    @Test fun `glosario, siempre el nodo de la PC`() {
        for (t in listOf("NORUN", "-1", "3")) assertTrue(textoExpiryHost(t, 21)!!.contains("nodo Tailscale de la PC"))
        assertNotNull(textoExpiryHost("0", 21))
    }
}
