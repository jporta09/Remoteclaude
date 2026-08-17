package com.remoteclaude.app

import android.content.Intent
import android.graphics.pdf.PdfDocument
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * E2E del visor de documentos contra el fixture desechable, que ya viene con
 * `~/RemoteMarvinDocs` sembrado — incluido un archivo con comilla en el nombre.
 *
 * Lo que se protege acá es la cadena entera: la app arma un comando de shell con el nombre
 * del archivo y lo corre por SSH en el host. Un nombre hostil no puede terminar ejecutando
 * nada, y un host caído no puede parecerse a "no hay documentos".
 */
@RunWith(AndroidJUnit4::class)
class DocsE2ETest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val host get() = args.getString("fixtureHost") ?: "127.0.0.1"
    private val port get() = (args.getString("fixturePort") ?: "2222").toInt()

    private lateinit var fixture: FixtureSsh
    private lateinit var control: RemoteControl

    @Before fun setUp() {
        fixture = FixtureSsh(host, port)
        fixture.authorize(KeyStoreSsh.openSshPublicKey("remoteclaude-app"))
        HostKeys.forget(ctx, host, port)
        control = RemoteControl(ctx, host, port, FixtureSsh.USER, KeyStoreSsh.getOrCreateKeyPair())
    }

    @After fun tearDown() {
        runCatching { fixture.exec("rm -f /tmp/canario ~/RemoteMarvinDocs/hostil* ~/RemoteMarvinDocs/mini.pdf 2>/dev/null; true") }
    }

    @Test fun listaLosDocumentosQueElHostComparte() {
        val nombres = control.listDocs().map { it.first }
        assertThat(nombres).containsAtLeast("notas.txt", "datos.csv")
    }

    @Test fun elTamanioReportadoEsElReal() {
        // La lista muestra el tamaño; si se leyera mal el `find -printf`, mostraría 0 y
        // nadie lo notaría hasta ver un archivo "vacío" que no lo está.
        val notas = control.listDocs().first { it.first == "notas.txt" }
        assertThat(notas.second).isEqualTo(fixture.exec("stat -c %s ~/RemoteMarvinDocs/notas.txt").trim().toLong())
    }

    @Test fun leeElContenidoDeUnDocumento() {
        val b64 = control.readDocBase64("notas.txt")
        val texto = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
        assertThat(texto.trim()).isEqualTo("contenido de prueba")
    }

    @Test fun unNombreConComillaNoEjecutaNada() {
        // El fixture siembra "raro'nombre.txt". Si el nombre se interpolara sin comillar, el
        // shell del host lo partiría y ejecutaría lo que venga después.
        val hostil = "hostil'; touch /tmp/canario; echo '.txt"
        fixture.exec("touch \"\$HOME/RemoteMarvinDocs/${hostil.replace("\"", "\\\"")}\" 2>/dev/null; true")

        control.listDocs()                       // listarlo no puede ejecutar nada
        control.readDocBase64(hostil)            // leerlo tampoco

        val canario = fixture.exec("test -e /tmp/canario && echo EJECUTADO || echo limpio").trim()
        assertThat(canario).isEqualTo("limpio")
    }

    @Test fun unNombreInseguroSeRechazaEnVezDeIntentarLeerlo() {
        // readDocBase64 corta de entrada los nombres con comilla, salto de línea o barra:
        // es la defensa que no depende de acordarse de comillar más abajo.
        assertThat(control.readDocBase64("raro'nombre.txt")).isEmpty()
        assertThat(control.readDocBase64("../../etc/passwd")).isEmpty()
        assertThat(control.readDocBase64("con\nsalto.txt")).isEmpty()
    }

    @Test fun elVisorRenderizaUnPdf_noSoloLoBaja() {
        // Regresión del "Unsupported pixel format": el transporte (readDocBase64) andaba
        // perfecto y estos tests pasaban, pero PdfRenderer.render() solo acepta bitmaps
        // ARGB_8888 y el visor le pasaba RGB_565 — TODO pdf moría con un mensaje de error.
        // Este test atraviesa la pantalla de verdad: exige ver una página renderizada.
        val pdf = PdfDocument().let { d ->
            val pag = d.startPage(PdfDocument.PageInfo.Builder(300, 400, 0).create())
            pag.canvas.drawColor(android.graphics.Color.WHITE)
            d.finishPage(pag)
            val out = ByteArrayOutputStream()
            d.writeTo(out)
            d.close()
            out.toByteArray()
        }
        val b64 = android.util.Base64.encodeToString(pdf, android.util.Base64.NO_WRAP)
        fixture.exec("printf %s '$b64' | base64 -d > ~/RemoteMarvinDocs/mini.pdf")

        val intent = Intent(ctx, DocViewerActivity::class.java).apply {
            putExtra("hostname", host)
            putExtra("port", port)
            putExtra("user", FixtureSsh.USER)
            putExtra("name", "mini.pdf")
            putExtra("size", pdf.size.toLong())
        }
        ActivityScenario.launch<DocViewerActivity>(intent).use { esc ->
            esperar("una página de PDF renderizada en pantalla") {
                var hay = false
                esc.onActivity { a -> hay = contarPaginas(a.window.decorView) > 0 }
                hay
            }
        }
    }

    private fun contarPaginas(v: View): Int = when (v) {
        is ImageView -> if (v.drawable != null) 1 else 0
        is ViewGroup -> (0 until v.childCount).sumOf { contarPaginas(v.getChildAt(it)) }
        else -> 0
    }

    private fun esperar(que: String, ms: Long = 45_000, cond: () -> Boolean) {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) {
            if (cond()) return
            Thread.sleep(300)
        }
        throw AssertionError("timeout esperando: $que")
    }

    @Test fun siElHostNoResponde_seDistingueDeNoTenerDocumentos() {
        // El hallazgo B2: con exec() tragándose el error, un host caído devolvía lista vacía
        // y la pantalla decía "Sin documentos en ~/RemoteMarvinDocs del host" — o sea que un
        // problema de conexión se presentaba como un hecho sobre tus archivos.
        val muerto = RemoteControl(ctx, host, 1, FixtureSsh.USER, KeyStoreSsh.getOrCreateKeyPair())
        val e = runCatching { muerto.listDocs() }.exceptionOrNull()
        assertThat(e).isNotNull()
        assertThat(e!!.message).isNotEmpty()
    }
}
