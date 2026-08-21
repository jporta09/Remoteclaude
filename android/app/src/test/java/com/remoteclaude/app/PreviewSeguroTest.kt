package com.remoteclaude.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** El snippet de preview de OSC 52: una línea, sin control chars, truncado. */
class PreviewSeguroTest {

    @Test fun `colapsa saltos y espacios a un solo espacio`() {
        assertThat(previewSeguro("hola\n\tmundo   fin", 100)).isEqualTo("hola mundo fin")
    }

    @Test fun `trunca con puntos suspensivos pasado el maximo`() {
        val s = previewSeguro("0123456789abcdef", 10)
        assertThat(s).isEqualTo("0123456789…")
    }

    @Test fun `no trunca ni agrega puntos si entra`() {
        assertThat(previewSeguro("corto", 10)).isEqualTo("corto")
    }

    @Test fun `recorta los bordes`() {
        assertThat(previewSeguro("   hola   ", 100)).isEqualTo("hola")
    }
}
