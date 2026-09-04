package com.remoteclaude.app

import com.trilead.ssh2.ChannelCondition
import com.trilead.ssh2.Connection
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Exec remoto ACOTADO en tiempo y en bytes (5ª pasada: SRE-5-1 / DEV-N4 / SEC5-4).
 *
 * Antes los 7 exec de la app hacían `String(s.stdout.readBytes())` sin ningún tope: un host
 * (ya autenticado) que no cerraba stdout colgaba el hilo para siempre —en el ssh-loop, ANTES de
 * abrir tmux, así que la terminal nunca conectaba— y uno que streameara GB tiraba la app por
 * OOM. Acá se espera con `waitForCondition` y se lee sólo lo disponible, hasta el deadline o
 * el tope de bytes.
 */
object SshExec {
    /** `completo` = el comando cerró su stdout dentro del tiempo y del tope. */
    class Resultado(val salida: String, val rc: Int?, val completo: Boolean)

    enum class Espera { DATOS, FIN, TIMEOUT }

    fun leer(c: Connection, comando: String, timeoutMs: Long, maxBytes: Int, stdin: ByteArray? = null): Resultado {
        val s = c.openSession()
        try {
            s.execCommand(comando)
            // Cerrar stdin = EOF para el comando (los que leen entrada empiezan; los otros no notan nada).
            try { s.stdin.use { if (stdin != null) it.write(stdin) } } catch (_: Exception) {}
            val deadline = System.currentTimeMillis() + timeoutMs
            val (bytes, completo) = leerAcotado(s.stdout, maxBytes, deadline, { restante ->
                val cond = s.waitForCondition(
                    ChannelCondition.STDOUT_DATA or ChannelCondition.EOF or ChannelCondition.CLOSED, restante,
                )
                when {
                    cond and ChannelCondition.TIMEOUT != 0 -> Espera.TIMEOUT
                    cond and (ChannelCondition.EOF or ChannelCondition.CLOSED) != 0 -> Espera.FIN
                    else -> Espera.DATOS
                }
            })
            if (completo) {
                // El EOF puede llegar antes que el exit-status; darle un momento acotado.
                s.waitForCondition(ChannelCondition.EXIT_STATUS, (deadline - System.currentTimeMillis()).coerceIn(0L, 3000L))
            }
            return Resultado(String(bytes, Charsets.UTF_8), s.exitStatus, completo)
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }
}

/**
 * Núcleo puro y testeable de [SshExec.leer]: lee de `entrada` sólo lo que está disponible tras
 * cada `esperar(restanteMs)`, hasta FIN (completo=true), o hasta el deadline / el tope de bytes
 * (completo=false, con lo leído hasta ahí).
 */
internal fun leerAcotado(
    entrada: InputStream,
    maxBytes: Int,
    deadlineMs: Long,
    esperar: (Long) -> SshExec.Espera,
    ahora: () -> Long = System::currentTimeMillis,
): Pair<ByteArray, Boolean> {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        val restante = deadlineMs - ahora()
        if (restante <= 0) return out.toByteArray() to false
        val cond = esperar(restante)
        if (cond == SshExec.Espera.TIMEOUT) return out.toByteArray() to false
        var disponible = entrada.available()
        while (disponible > 0) {
            val n = entrada.read(buf, 0, minOf(buf.size, disponible))
            if (n < 0) return out.toByteArray() to true
            out.write(buf, 0, n)
            if (out.size() > maxBytes) return out.toByteArray() to false
            disponible = entrada.available()
        }
        if (cond == SshExec.Espera.FIN) return out.toByteArray() to true
    }
}
