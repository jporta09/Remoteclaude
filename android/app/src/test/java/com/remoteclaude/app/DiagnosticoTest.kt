package com.remoteclaude.app

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/** El contrato del ring buffer de observabilidad (F8): orden, tope acotado y export legible. */
class DiagnosticoTest {

    @Before fun limpio() = Diagnostico.limpiar()

    @Test fun `registra y devuelve en orden de llegada`() {
        Diagnostico.registrar(Diagnostico.Nivel.INFO, "conexión", "a")
        Diagnostico.registrar(Diagnostico.Nivel.ERROR, "auth", "b")
        val s = Diagnostico.instantanea()
        assertThat(s).hasSize(2)
        assertThat(s.first().detalle).isEqualTo("a")
        assertThat(s.last().nivel).isEqualTo(Diagnostico.Nivel.ERROR)
    }

    @Test fun `descarta los mas viejos pasado el tope de 200`() {
        repeat(250) { Diagnostico.registrar(Diagnostico.Nivel.INFO, "c", "e$it") }
        val s = Diagnostico.instantanea()
        assertThat(s).hasSize(200)
        assertThat(s.first().detalle).isEqualTo("e50")   // e0..e49 se cayeron
        assertThat(s.last().detalle).isEqualTo("e249")
    }

    @Test fun `exporta texto legible con nivel y categoria`() {
        Diagnostico.registrar(Diagnostico.Nivel.AVISO, "conexión", "se cortó")
        val txt = Diagnostico.exportarTexto()
        assertThat(txt).contains("AVISO")
        assertThat(txt).contains("conexión: se cortó")
    }

    @Test fun `sin eventos, el export lo dice en vez de quedar vacio`() {
        assertThat(Diagnostico.exportarTexto()).contains("sin eventos")
    }
}
