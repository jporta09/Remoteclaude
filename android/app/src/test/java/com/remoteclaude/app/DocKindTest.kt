package com.remoteclaude.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DocKindTest {
    @Test fun `imagenes pdf y texto`() {
        assertThat(DocKind.of("grafico.PNG")).isEqualTo(DocKind.IMAGE)
        assertThat(DocKind.of("informe.pdf")).isEqualTo(DocKind.PDF)
        assertThat(DocKind.of("notas.md")).isEqualTo(DocKind.TEXT)
    }

    @Test fun `sin extension se trata como texto`() {
        assertThat(DocKind.of("LICENSE")).isEqualTo(DocKind.TEXT)
    }

    @Test fun `solo cuenta la ultima extension`() {
        assertThat(DocKind.of("backup.tar.gz")).isEqualTo(DocKind.OTHER)
        assertThat(DocKind.of("captura.tar.png")).isEqualTo(DocKind.IMAGE)
    }

    @Test fun `desconocidas caen en OTHER`() {
        assertThat(DocKind.of("app.apk")).isEqualTo(DocKind.OTHER)
        assertThat(DocKind.of("video.mp4")).isEqualTo(DocKind.OTHER)
    }
}
