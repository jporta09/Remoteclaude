package com.remoteclaude.app

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** El contrato del ring buffer de observabilidad (F8): orden, tope acotado y export legible. */
class DiagnosticoTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before fun limpio() {
        Diagnostico.limpiar()
        Diagnostico.archivoParaTest(null)   // que un test no arrastre persistencia a otro
    }

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

    @Test fun `persiste avisos-errores y los recupera al reabrir, el INFO no se persiste`() {
        val f = File(tmp.root, "eventos-conexion.log")
        Diagnostico.archivoParaTest(f)
        Diagnostico.registrar(Diagnostico.Nivel.ERROR, "auth", "clave no autorizada")   // persiste
        Diagnostico.registrar(Diagnostico.Nivel.INFO, "conexión", "conectado")          // NO persiste
        assertThat(f.exists()).isTrue()

        // simular reinicio del proceso: memoria vacía + persistencia apagada, y recargar del archivo
        Diagnostico.limpiar()
        Diagnostico.archivoParaTest(null)
        Diagnostico.cargarPersistidosDe(f)

        val s = Diagnostico.instantanea()
        assertThat(s).hasSize(1)
        assertThat(s.first().categoria).contains("previo")
        assertThat(s.first().detalle).contains("clave no autorizada")
        assertThat(s.first().detalle).doesNotContain("conectado")   // el INFO no viajó
        assertThat(f.exists()).isFalse()                            // se borra tras cargar
    }

    @Test fun `cargarPersistidos sin archivo es noop`() {
        Diagnostico.cargarPersistidosDe(File(tmp.root, "no-existe.log"))
        assertThat(Diagnostico.instantanea()).isEmpty()
    }

    @Test fun `al pasar el tope rota conservando la ultima mitad, no borra todo`() {
        val f = File(tmp.root, "eventos-conexion.log")
        Diagnostico.archivoParaTest(f)
        val relleno = "z".repeat(200)
        // Suficientes ERROR (cada uno persiste) para cruzar el tope de 64 KB varias veces.
        repeat(900) { Diagnostico.registrar(Diagnostico.Nivel.ERROR, "c", "e$it-$relleno") }

        val texto = f.readText()
        assertThat(texto).contains("e899")              // lo más nuevo sobrevive
        assertThat(texto).doesNotContain("e0-")         // lo viejo se rotó
        // Lo clave del fix: NO quedó borrado a casi nada (antes `writeText("")` dejaba ~1 línea).
        assertThat(f.readLines().size).isGreaterThan(50)
        assertThat(f.length()).isAtMost(80 * 1024L)     // acotado
    }

    @Test fun `persistir concurrente no corrompe ni colapsa el archivo`() {
        val f = File(tmp.root, "eventos-conexion.log")
        Diagnostico.archivoParaTest(f)
        val relleno = "z".repeat(200)
        val hilos = (0 until 8).map { h ->
            Thread { repeat(200) { Diagnostico.registrar(Diagnostico.Nivel.ERROR, "h$h", "e$it-$relleno") } }
        }
        hilos.forEach { it.start() }
        hilos.forEach { it.join() }

        // Con el lock, el read-modify-write de la rotación no se pisa con los appends: el archivo queda
        // coherente y con histórico (antes la carrera lo dejaba en ~1 línea).
        val lineas = f.readLines()
        assertThat(lineas.size).isGreaterThan(50)
        // Cada evento persistido son 2 líneas ("=== ts ===" + "[cat] detalle"): vienen en pares (±1 por
        // el borde de la rotación, que puede cortar el evento más viejo). Sin el lock, appends pisados
        // dejaban líneas partidas y el conteo se descuajeringaba.
        val cabeceras = lineas.count { it.startsWith("=== ") }
        val cuerpos = lineas.count { it.startsWith("[h") }
        assertThat(Math.abs(cabeceras - cuerpos)).isAtMost(1)
    }
}
