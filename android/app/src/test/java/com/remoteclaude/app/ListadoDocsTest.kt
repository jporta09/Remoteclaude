package com.remoteclaude.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * El contrato del listado de documentos: los bytes vienen de `find -printf` con campos
 * separados por TAB, y el campo 0 es un NOMBRE DE ARCHIVO que el host no controla —
 * puede traer el mismo TAB que separa los campos.
 *
 * La regresión concreta: un archivo que Claude deja en la raíz llamado
 * `informe.pdf\t9\t9\t9\ts` corría los campos y salía como una fila FANTASMA
 * ("informe.pdf", que no existe: tocarla era un callejón sin salida) etiquetada
 * "subido por vos" — borrando la señal de procedencia que separa lo que compartió
 * Claude de lo que subiste vos.
 */
class ListadoDocsTest {

    /** Un registro como lo emite `find -printf '%f\t%s\t%T@\t%W@\tc\0'`. */
    private fun reg(nombre: String, size: Long = 1, origen: String = "c") =
        "$nombre\t$size\t1700000000.0000000000\t1690000000.0000000000\t$origen\u0000"

    @Test fun `parsea nombre, tamano, fechas y origen`() {
        val docs = parsearListadoDocs(reg("notas.txt", size = 42) + reg("subida.txt", origen = "s"))

        assertThat(docs.map { it.name }).containsExactly("notas.txt", "subida.txt").inOrder()
        assertThat(docs[0].size).isEqualTo(42L)
        assertThat(docs[0].mtime).isEqualTo(1700000000L)
        assertThat(docs[0].btime).isEqualTo(1690000000L)
        assertThat(docs[0].subido).isFalse()
        assertThat(docs[0].soportado).isTrue()
        assertThat(docs[1].subido).isTrue()
    }

    @Test fun `un TAB en el nombre no corre los campos ni fabrica una fila fantasma`() {
        // Archivo COMPARTIDO (raíz, origen "c") cuyo nombre imita los campos del listado.
        val crudo = reg("informe.pdf\t9\t9\t9\ts", size = 12) + reg("notas.txt")
        val docs = parsearListadoDocs(crudo)

        assertThat(docs).hasSize(2)
        // La fila fantasma "informe.pdf" (que no existe en el host) no se crea…
        assertThat(docs.map { it.name }).doesNotContain("informe.pdf")
        // …el nombre llega ENTERO, con sus metadatos reales…
        val hostil = docs.first { it.name.startsWith("informe.pdf") }
        assertThat(hostil.name).isEqualTo("informe.pdf\t9\t9\t9\ts")
        assertThat(hostil.size).isEqualTo(12L)
        // …no spoofea el label de procedencia ("subido por vos")…
        assertThat(hostil.subido).isFalse()
        // …y queda deshabilitada, no como tarjeta tappable que miente.
        assertThat(hostil.soportado).isFalse()
    }

    @Test fun `los nombres que la app no maneja se listan pero no soportados`() {
        val crudos = listOf(
            "raro'nombre.txt",          // comilla: rompería el comillado del shell
            "con\nsalto.txt",           // salto de línea
            "con\ttab.txt",             // TAB: el separador de campos
            "con\u0007bel.txt",        // BEL y ESC: cualquier otro control char
            "con\u001bESC.txt",
            "con\rcr.txt",
        )
        val docs = parsearListadoDocs(crudos.joinToString("") { reg(it) })

        assertThat(docs).hasSize(crudos.size)
        docs.forEach { assertThat(it.soportado).isFalse() }
        // Y el mismo saneo corta esos nombres antes de que toquen el shell.
        crudos.forEach { assertThat(nombreDeDocInseguro(it)).isTrue() }
    }

    @Test fun `espacios, acentos y emoji siguen siendo nombres soportados`() {
        val buenos = listOf("informe final.pdf", "mañana.txt", "foto 🎉.png", "a-b_c.1.tar.gz")
        val docs = parsearListadoDocs(buenos.joinToString("") { reg(it) })

        assertThat(docs.map { it.name }).containsExactlyElementsIn(buenos)
        docs.forEach { assertThat(it.soportado).isTrue() }
        buenos.forEach { assertThat(nombreDeDocInseguro(it)).isFalse() }
    }

    @Test fun `una salida vacia o truncada no inventa documentos`() {
        assertThat(parsearListadoDocs("")).isEmpty()
        assertThat(parsearListadoDocs("\u0000\u0000")).isEmpty()
        assertThat(parsearListadoDocs("a-medias\t12\t170")).isEmpty()   // registro cortado
        assertThat(parsearListadoDocs(reg("", size = 3))).isEmpty()     // nombre vacío
    }
}
