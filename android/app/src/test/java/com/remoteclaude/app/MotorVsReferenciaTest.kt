package com.remoteclaude.app

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Le da al motor de terminal vendorizado un flujo REAL de Claude Code y vuelca la pantalla
 * resultante, para poder compararla contra un emulador de referencia independiente.
 *
 * Existe por un reporte del usuario que ninguna otra prueba explicaba: la pantalla queda
 * mezclada —una respuesta escrita encima de otra— y **no se arregla apretando teclas**. Eso
 * descarta un cuadro a medio dibujar, porque ése se completaría en el repintado siguiente;
 * lo que queda mal es el búfer. Y si el flujo trae los borrados correctos (los trae: 54
 * `ESC[K` y 57 `ESC[2K`), entonces la sospecha se corre a cómo los aplica el motor.
 *
 * El flujo se graba aparte (no se versiona: son ~90 KB de una sesión concreta). Sin el
 * archivo, el test se saltea en vez de fallar, porque no aporta nada en un checkout limpio.
 */
class MotorVsReferenciaTest {

    private val flujo = File(System.getProperty("marvin.flujo") ?: "/tmp/dialogo.bin")

    @Test fun volcarPantallaDelMotor() {
        assumeTrue("sin flujo grabado en ${flujo.path}: se saltea", flujo.isFile)

        val cols = System.getProperty("marvin.cols")?.toInt() ?: 38
        val filas = System.getProperty("marvin.filas")?.toInt() ?: 26
        val salida = object : TerminalOutput() {
            override fun write(data: ByteArray?, offset: Int, count: Int) {}
            override fun titleChanged(oldTitle: String?, newTitle: String?) {}
            override fun onCopyTextToClipboard(text: String?) {}
            override fun onPasteTextFromClipboard() {}
            override fun onBell() {}
            override fun onColorsChanged() {}
        }
        val motor = TerminalEmulator(salida, cols, filas, 0, 0, filas, null)

        // De a pedazos, como llega por la red: si el motor tuviera estado mal manejado entre
        // escrituras, acá se vería.
        val bytes = flujo.readBytes()
        var i = 0
        while (i < bytes.size) {
            val n = minOf(1024, bytes.size - i)
            motor.append(bytes.copyOfRange(i, i + n), n)
            i += n
        }

        val pantalla = (0 until filas).joinToString("\n") { y ->
            motor.screen.getSelectedText(0, y, cols - 1, y).trimEnd()
        }
        File(flujo.parentFile, "pantalla-motor.txt").writeText(pantalla)
        println("=== pantalla según el motor de RemoteMarvin (${cols}x$filas) ===")
        pantalla.lines().forEachIndexed { n, l -> if (l.isNotBlank()) println("%2d|%s".format(n, l)) }
    }
}
