package com.remoteclaude.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** DG-4 (5ª pasada): una sola paleta. Paleta.kt (código) tiene que coincidir con colors.xml (recursos). */
class PaletaTest {
    private fun colorXml(nombre: String): String {
        val f = generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, "src/main/res/values/colors.xml") }.first { it.exists() }
        val m = Regex("""<color name="$nombre">(#[0-9A-Fa-f]{6})</color>""").find(f.readText())
        return requireNotNull(m) { "no está $nombre en colors.xml" }.groupValues[1].uppercase()
    }

    @Test fun `los hex de Paleta son los de colors xml`() {
        assertEquals(colorXml("marvin_green"), Paleta.ACCENT_HEX)
        assertEquals(colorXml("marvin_fg"), Paleta.KEY_FG_HEX)
        assertEquals(colorXml("marvin_petrol"), Paleta.PETROL_HEX)
        assertEquals(colorXml("marvin_muted"), Paleta.MUTED_HEX)
        assertEquals(colorXml("marvin_amber"), Paleta.AMBER_HEX)
        assertEquals(colorXml("marvin_red"), Paleta.RED_HEX)
        assertEquals(colorXml("marvin_surface"), Paleta.SURFACE_HEX)
    }
}
