package com.remoteclaude.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** SRE-5-1 / N4 / SEC5-4 (5ª pasada): el exec remoto tiene tope de tiempo y de bytes. */
class SshExecTest {
    private class Reloj(var t: Long) { fun ahora() = t }

    @Test fun `un comando que termina devuelve todo y completo`() {
        val datos = "CONFIGURADO\n".toByteArray()
        var llamadas = 0
        val (out, completo) = leerAcotado(
            ByteArrayInputStream(datos), 4096, deadlineMs = 10_000,
            esperar = { if (llamadas++ == 0) SshExec.Espera.DATOS else SshExec.Espera.FIN },
            ahora = { 0L },
        )
        assertTrue(completo)
        assertArrayEquals(datos, out)
    }

    @Test fun `un host que nunca cierra stdout corta por deadline`() {
        val reloj = Reloj(0)
        val (out, completo) = leerAcotado(
            ByteArrayInputStream(ByteArray(0)), 4096, deadlineMs = 3_000,
            esperar = { restante -> reloj.t += restante; SshExec.Espera.TIMEOUT },
            ahora = reloj::ahora,
        )
        assertFalse(completo)
        assertTrue(out.isEmpty())
    }

    @Test fun `un host que streamea de mas corta por tope de bytes`() {
        val (out, completo) = leerAcotado(
            ByteArrayInputStream(ByteArray(1_000_000)), maxBytes = 4096, deadlineMs = 10_000,
            esperar = { SshExec.Espera.DATOS }, ahora = { 0L },
        )
        assertFalse(completo)
        assertTrue("leyó ${out.size}", out.size in 4097..(4096 + 8192))
    }

    @Test fun `con el deadline ya vencido no espera ni lee`() {
        var esperas = 0
        val (_, completo) = leerAcotado(
            ByteArrayInputStream("x".toByteArray()), 10, deadlineMs = 5,
            esperar = { esperas++; SshExec.Espera.DATOS }, ahora = { 100L },
        )
        assertFalse(completo)
        assertTrue(esperas == 0)
    }
}
