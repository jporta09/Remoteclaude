package com.remoteclaude.app

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * M1: sesión de ECO LOCAL (sin red). Valida que el motor de Termux renderiza e
 * interpreta input real. Lo que el usuario tipea se "devuelve" al emulador.
 * En M2 esta subclase se reemplaza por una que habla SSH.
 */
class EchoTerminalSession(client: TerminalSessionClient) : TerminalSession(2000, client) {

    override fun onEmulatorInitialized(
        columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int
    ) {
        val banner = "Remoteclaude — M1 (motor Termux, eco local)\r\n" +
            "Escribí: se renderiza con el emulador real.\r\n$ "
        val b = banner.toByteArray(Charsets.UTF_8)
        onTransportInput(b, 0, b.size)
    }

    override fun writeToTransport(data: ByteArray, offset: Int, count: Int) {
        // Eco: devolver lo tipeado, traduciendo CR (Enter) a CR+LF para bajar de línea.
        val out = ArrayList<Byte>(count + 4)
        for (i in offset until offset + count) {
            val byte = data[i]
            out.add(byte)
            if (byte.toInt() == 13) out.add(10) // \r -> \r\n
        }
        val arr = out.toByteArray()
        onTransportInput(arr, 0, arr.size)
    }

    override fun closeTransport() {
        // nada que cerrar en el eco
    }
}
