package com.remoteclaude.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** La detección del modo lectura: ¿hay un prompt de DECISIÓN con opciones al pie? Sólo ahí baja
 *  el teclado. No debe confundir una lista numerada en medio de la salida ni una sola opción. */
class PromptDeOpcionesTest {

    @Test fun `formato real de Claude Code (cursor + reglas, sin bordes) dispara`() {
        val txt = "──────────\nDo you want to make this edit?\n\n ❯ 1. Yes\n   2. Yes, and don't ask again\n   3. No"
        assertThat(hayPromptDeOpciones(txt)).isTrue()
    }

    @Test fun `opciones con parentesis disparan (menu generico, no-Claude)`() {
        assertThat(hayPromptDeOpciones("Elegí una opción:\n 1) Yes\n 2) No")).isTrue()
    }

    @Test fun `una sola opcion no alcanza`() {
        assertThat(hayPromptDeOpciones("continuar?\n 1) ok")).isFalse()
    }

    @Test fun `sin opciones, no dispara`() {
        assertThat(hayPromptDeOpciones("compilando...\nlisto\njporta@host:~$ ")).isFalse()
    }

    @Test fun `una lista numerada SEPULTADA bajo mas salida no dispara`() {
        // las 2 opciones quedan fuera de las últimas 8 líneas no-blancas
        val relleno = (1..10).joinToString("\n") { "linea de salida $it" }
        val txt = "1) uno\n2) dos\n$relleno"
        assertThat(hayPromptDeOpciones(txt)).isFalse()
    }

    @Test fun `tolera lineas en blanco entre las opciones y el borde`() {
        assertThat(hayPromptDeOpciones("Seguir?\n\n 1. Sí\n 2. No\n\n")).isTrue()
    }
}
