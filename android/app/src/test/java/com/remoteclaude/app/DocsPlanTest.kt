package com.remoteclaude.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DocsPlanTest {

    private fun doc(name: String, size: Long = 0, mtime: Long = 0, btime: Long = 0, subido: Boolean = false) =
        RemoteControl.Doc(name, size, mtime, btime, subido)

    // --- ordenar -----------------------------------------------------------------------

    @Test fun `por nombre, sin distinguir mayusculas`() {
        val docs = listOf(doc("b.txt"), doc("A.txt"), doc("c.txt"))
        assertThat(DocsPlan.ordenar(docs, DocsPlan.Criterio.NOMBRE, asc = true).map { it.name })
            .containsExactly("A.txt", "b.txt", "c.txt").inOrder()
    }

    @Test fun `por tamano descendente`() {
        val docs = listOf(doc("a", size = 5), doc("b", size = 50), doc("c", size = 1))
        assertThat(DocsPlan.ordenar(docs, DocsPlan.Criterio.TAMANO, asc = false).map { it.name })
            .containsExactly("b", "a", "c").inOrder()
    }

    @Test fun `por tipo agrupa imagen-pdf-texto-otro y desempata por nombre`() {
        val docs = listOf(doc("z.txt"), doc("b.pdf"), doc("a.png"), doc("x.bin"), doc("a.txt"))
        assertThat(DocsPlan.ordenar(docs, DocsPlan.Criterio.TIPO, asc = true).map { it.name })
            .containsExactly("a.png", "b.pdf", "a.txt", "z.txt", "x.bin").inOrder()
    }

    @Test fun `por modificacion`() {
        val docs = listOf(doc("viejo", mtime = 100), doc("nuevo", mtime = 300), doc("medio", mtime = 200))
        assertThat(DocsPlan.ordenar(docs, DocsPlan.Criterio.MODIFICACION, asc = false).map { it.name })
            .containsExactly("nuevo", "medio", "viejo").inOrder()
    }

    @Test fun `por creacion, los docs sin btime van al final en ambas direcciones`() {
        // El filesystem puede no registrar creación (btime=0): esos no compiten, cierran
        // la lista siempre — si no, en descendente aparecerían como "los más viejos" y en
        // ascendente como "los más nuevos", mintiendo en una de las dos.
        val docs = listOf(doc("sin", btime = 0), doc("a", btime = 100), doc("b", btime = 200))
        assertThat(DocsPlan.ordenar(docs, DocsPlan.Criterio.CREACION, asc = true).map { it.name })
            .containsExactly("a", "b", "sin").inOrder()
        assertThat(DocsPlan.ordenar(docs, DocsPlan.Criterio.CREACION, asc = false).map { it.name })
            .containsExactly("b", "a", "sin").inOrder()
    }

    @Test fun `hayBtime detecta si el host registra creacion`() {
        assertThat(DocsPlan.hayBtime(listOf(doc("a", btime = 0), doc("b", btime = 5)))).isTrue()
        assertThat(DocsPlan.hayBtime(listOf(doc("a", btime = 0), doc("b", btime = 0)))).isFalse()
        assertThat(DocsPlan.hayBtime(emptyList())).isFalse()
    }

    // --- normalizarNombre --------------------------------------------------------------

    @Test fun `espacios a guion bajo y filtra caracteres raros`() {
        assertThat(DocsPlan.normalizarNombre("foto de ayer (1).jpg")).isEqualTo("foto_de_ayer_1.jpg")
    }

    @Test fun `sin acentos ni comillas, la regla de marvin-show`() {
        assertThat(DocsPlan.normalizarNombre("informe'; rm -rf ~;'.pdf")).isEqualTo("informe_rm_-rf_.pdf")
    }

    @Test fun `toma solo el nombre base si viene con ruta`() {
        assertThat(DocsPlan.normalizarNombre("/sdcard/DCIM/img.png")).isEqualTo("img.png")
    }

    @Test fun `nombre irrecuperable devuelve null`() {
        assertThat(DocsPlan.normalizarNombre("'''")).isNull()
        assertThat(DocsPlan.normalizarNombre("")).isNull()
        assertThat(DocsPlan.normalizarNombre(null)).isNull()
        assertThat(DocsPlan.normalizarNombre("...")).isNull()
    }

    @Test fun `recorta a 120 y no deja punto inicial`() {
        val largo = "a".repeat(300) + ".txt"
        assertThat(DocsPlan.normalizarNombre(largo)!!.length).isAtMost(120)
        assertThat(DocsPlan.normalizarNombre(".oculto")).isEqualTo("oculto")
    }
}
