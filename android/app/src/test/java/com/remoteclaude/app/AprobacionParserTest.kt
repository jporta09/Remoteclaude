package com.remoteclaude.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * WS-F — detección del prompt de aprobación de Claude Code en el texto del buffer (puro, JVM).
 */
class AprobacionParserTest {

    @Test fun detecta_promptDeProceder_conComandoComoContexto() {
        val screen = """
            ● I'll run the build command.

              cd /project && make build

            Do you want to proceed?
            ❯ 1. Yes
              2. Yes, and don't ask again for make commands in /project
              3. No, and tell Claude what to do differently (esc)
        """.trimIndent()

        val p = AprobacionParser.detectar(screen)
        assertThat(p).isNotNull()
        assertThat(p!!.pregunta).contains("Do you want to proceed?")
        assertThat(p.opciones.map { it.numero }).containsExactly(1, 2, 3).inOrder()
        assertThat(p.opciones[0].etiqueta).isEqualTo("Yes")
        // se limpia el "(esc)" final
        assertThat(p.opciones[2].etiqueta).doesNotContain("(esc)")
        assertThat(p.opciones[2].etiqueta).startsWith("No")
        // el comando queda en el contexto (lo que en la terminal se empujaba fuera)
        assertThat(p.contexto).contains("make build")
    }

    @Test fun detecta_promptDeEdicion_conDiffEnContexto() {
        val screen = """
            ● Update src/main.py

                1  - old line
                1  + new line

            Do you want to make this edit to main.py?
            ❯ 1. Yes
              2. Yes, allow all edits during this session
              3. No, and tell Claude what to do differently (esc)
        """.trimIndent()

        val p = AprobacionParser.detectar(screen)
        assertThat(p).isNotNull()
        assertThat(p!!.pregunta).contains("make this edit")
        assertThat(p.opciones).hasSize(3)
        assertThat(p.contexto).contains("+ new line")
        assertThat(p.contexto).contains("- old line")
    }

    @Test fun une_opcionesQueSeParten_porElAnchoDelCelu() {
        // En un celu las opciones largas se parten en varias líneas del buffer.
        val screen = """
            Do you want to proceed?
            ❯ 1. Yes
              2. Yes, and don't ask again for
                 make commands in /very/long/path
              3. No (esc)
        """.trimIndent()

        val p = AprobacionParser.detectar(screen)
        assertThat(p).isNotNull()
        assertThat(p!!.opciones.map { it.numero }).containsExactly(1, 2, 3).inOrder()
        // la opción 2 se reconstruye con las dos líneas
        assertThat(p.opciones[1].etiqueta).contains("don't ask again")
        assertThat(p.opciones[1].etiqueta).contains("/very/long/path")
    }

    @Test fun salidaNormal_sinPrompt_esNull() {
        val screen = """
            $ ls -la
            total 8
            drwxr-xr-x  2 user user 4096 .
            -rw-r--r--  1 user user  220 archivo.txt
            $
        """.trimIndent()
        assertThat(AprobacionParser.detectar(screen)).isNull()
    }

    @Test fun preguntaSinOpcionesNumeradas_esNull() {
        // "Do you want" suelto (p.ej. en la salida de un comando) no es un select.
        val screen = "Do you want coffee? just prose, no numbered options here"
        assertThat(AprobacionParser.detectar(screen)).isNull()
    }

    @Test fun firma_distingueEntrePrompts() {
        val a = AprobacionParser.detectar("Do you want to proceed?\n 1. Yes\n 2. No")
        val b = AprobacionParser.detectar("Do you want to make this edit?\n 1. Yes\n 2. No")
        assertThat(a).isNotNull()
        assertThat(b).isNotNull()
        assertThat(a!!.firma).isNotEqualTo(b!!.firma)
    }
}
