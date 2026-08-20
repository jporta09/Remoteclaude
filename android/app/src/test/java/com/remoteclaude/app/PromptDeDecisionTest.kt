package com.remoteclaude.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** El "modo lectura" baja el teclado sólo ante un selector de decisión de Claude. La firma está
 *  anclada en capturas REALES del PTY (selector de permiso de herramienta y de trust): cursor `❯`
 *  sobre opción numerada + footer `to cancel`. No debe dispararse con salida común, listas
 *  numeradas, ni con el spinner ("esc to interrupt"). */
class PromptDeDecisionTest {

    @Test fun `selector de permiso de Bash real (captura) dispara`() {
        val txt = """
            ──────────────────────────────
             Bash command
               echo HOLA_MARVIN
             Do you want to proceed?
            ❯ 1. Yes
              2. No
             Esc to cancel · Tab to amend · ctrl+e to explain
        """.trimIndent()
        assertThat(hayPromptDeDecision(txt)).isTrue()
    }

    @Test fun `selector de trust real (captura) dispara`() {
        val txt = """
            Quick safety check: Is this a project you trust?
            ❯ 1. Yes, I trust this folder
              2. No, exit
            Enter to confirm · Esc to cancel
        """.trimIndent()
        assertThat(hayPromptDeDecision(txt)).isTrue()
    }

    @Test fun `edit con 3 opciones (header distinto) dispara`() {
        val txt = """
            Do you want to make this edit to TerminalClients.kt?
            ❯ 1. Yes
              2. Yes, and don't ask again this session
              3. No, and tell Claude what to do differently
             Esc to cancel · Tab to amend
        """.trimIndent()
        assertThat(hayPromptDeDecision(txt)).isTrue()
    }

    @Test fun `el spinner (esc to interrupt) NO dispara`() {
        // el ❯ del prompt de entrada + "esc to interrupt" no es un selector esperando decisión
        val txt = """
            ❯ Use the Bash tool to run exactly: echo HOLA
              Hatching…
             · esc to interrupt
        """.trimIndent()
        assertThat(hayPromptDeDecision(txt)).isFalse()
    }

    @Test fun `una lista numerada comun (sin cursor ni footer) NO dispara`() {
        val txt = """
            Pasos:
             1. Instalar deps
             2. Compilar
             3. Correr
            jporta@host:~$
        """.trimIndent()
        assertThat(hayPromptDeDecision(txt)).isFalse()
    }

    @Test fun `salida grande sin opciones NO dispara`() {
        val txt = (1..40).joinToString("\n") { "linea $it" }
        assertThat(hayPromptDeDecision(txt)).isFalse()
    }

    @Test fun `un selector sepultado bajo mas salida NO dispara`() {
        val relleno = (1..12).joinToString("\n") { "salida posterior $it" }
        val txt = "❯ 1. Yes\n  2. No\n Esc to cancel\n$relleno"
        assertThat(hayPromptDeDecision(txt)).isFalse()
    }
}
