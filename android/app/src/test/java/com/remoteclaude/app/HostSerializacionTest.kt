package com.remoteclaude.app

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

/**
 * Serialización de [Host]. Importa que `avisosBg` (el toggle del foreground service de avisos)
 * sobreviva el ida y vuelta a JSON y que los hosts viejos —guardados antes de que existiera el
 * campo— se lean como `false` en vez de romper.
 */
class HostSerializacionTest {

    private val base = Host(
        id = "h1", label = "Mi PC", hostname = "10.0.0.5", port = 2222, user = "juan",
    )

    @Test fun `avisosBg sobrevive el round-trip`() {
        val conAvisos = base.copy(avisosBg = true)
        assertThat(Host.fromJson(conAvisos.toJson())).isEqualTo(conAvisos)
        assertThat(Host.fromJson(base.toJson())).isEqualTo(base)   // default false
    }

    @Test fun `un host viejo sin el campo se lee como false`() {
        // JSON tal como lo guardaba una versión previa: sin avisosBg.
        val viejo = JSONObject()
            .put("id", "h1").put("label", "Mi PC")
            .put("hostname", "10.0.0.5").put("port", 2222).put("user", "juan")
        assertThat(Host.fromJson(viejo).avisosBg).isFalse()
    }

    @Test fun `el default de un host nuevo es false`() {
        assertThat(base.avisosBg).isFalse()
    }
}
