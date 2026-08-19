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
        ctx.getSharedPreferences("remotemarvin", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("tour_off", true).commit()
        control = RemoteControl(ctx, host, port, FixtureSsh.USER, KeyStoreSsh.getOrCreateKeyPair())
        // Los caminos no interactivos ya NO fijan la primera clave (WS-I). En el uso real, la
        // terminal la fija antes; acá simulamos ese paso para que los tests de docs conecten.
        control.confiarEnClaveDelHost()
    }

    @After fun tearDown() {
        runCatching { fixture.exec("rm -rf /tmp/canario ~/RemoteMarvinDocs/hostil* ~/RemoteMarvinDocs/con* ~/RemoteMarvinDocs/mini.pdf ~/RemoteMarvinDocs/borrame-raiz.txt ~/RemoteMarvinDocs/subidos 2>/dev/null; true") }
    }

    @Test fun listaLosDocumentosQueElHostComparte() {
        val nombres = control.listDocs().map { it.name }
        assertThat(nombres).containsAtLeast("notas.txt", "datos.csv")
    }

    @Test fun elTamanioReportadoEsElReal() {
        // La lista muestra el tamaño; si se leyera mal el `find -printf`, mostraría 0 y
        // nadie lo notaría hasta ver un archivo "vacío" que no lo está.
        val notas = control.listDocs().first { it.name == "notas.txt" }
        assertThat(notas.size).isEqualTo(fixture.exec("stat -c %s ~/RemoteMarvinDocs/notas.txt").trim().toLong())
    }

    @Test fun listDocs_traeSubidosYCompartidosConSuEtiqueta() {
        fixture.exec("mkdir -p ~/RemoteMarvinDocs/subidos && echo hola > ~/RemoteMarvinDocs/subidos/desde-celu.txt")

        val docs = control.listDocs()
        val subido = docs.first { it.name == "desde-celu.txt" }
        val compartido = docs.first { it.name == "notas.txt" }

        assertThat(subido.subido).isTrue()
        assertThat(compartido.subido).isFalse()
        // btime: >0 si el FS lo registra, 0 si no — nunca negativo ni basura.
        assertThat(subido.btime).isAtLeast(0L)
        assertThat(subido.mtime).isGreaterThan(0L)
    }

    @Test fun subirUnArchivo_llegaIntactoASubidosDelHost() {
        // Texto y binario (bytes 0..255): el canal es el stdin de un `cat` remoto y
        // cualquier mediación de shell/charset corrompería el binario.
        val texto = "subida de prueba\n".toByteArray()
        assertThat(control.uploadDoc("subida.txt", texto).ok).isTrue()
        assertThat(fixture.exec("cat ~/RemoteMarvinDocs/subidos/subida.txt"))
            .isEqualTo("subida de prueba\n")

        val binario = ByteArray(256) { it.toByte() }
        assertThat(control.uploadDoc("subida.bin", binario).ok).isTrue()
        val shaHost = fixture.exec("sha256sum ~/RemoteMarvinDocs/subidos/subida.bin").trim().split(" ")[0]
        val shaLocal = java.security.MessageDigest.getInstance("SHA-256")
            .digest(binario).joinToString("") { "%02x".format(it) }
        assertThat(shaHost).isEqualTo(shaLocal)
    }

    @Test fun borrarUnDocumento_desapareceDelHost() {
        fixture.exec("mkdir -p ~/RemoteMarvinDocs/subidos && echo x > ~/RemoteMarvinDocs/subidos/borrame.txt && echo y > ~/RemoteMarvinDocs/borrame-raiz.txt")

        assertThat(control.deleteDoc("borrame.txt", subido = true).ok).isTrue()
        assertThat(control.deleteDoc("borrame-raiz.txt", subido = false).ok).isTrue()

        assertThat(fixture.exec("ls ~/RemoteMarvinDocs/subidos/borrame.txt 2>/dev/null; ls ~/RemoteMarvinDocs/borrame-raiz.txt 2>/dev/null").trim())
            .isEmpty()
        // Borrar algo que no existe es un error visible, no un silencio.
        assertThat(control.deleteDoc("no-existe.txt", subido = false).failed).isTrue()
    }

    @Test fun unNombreHostilSeRechazaAlSubirYAlBorrar_sinTocarElShell() {
        val hostil = "hostil'; touch /tmp/canario; echo '.txt"

        assertThat(control.uploadDoc(hostil, "x".toByteArray()).failed).isTrue()
        assertThat(control.deleteDoc(hostil, subido = false).failed).isTrue()
        assertThat(control.deleteDoc("../notas.txt", subido = true).failed).isTrue()

        val canario = fixture.exec("test -e /tmp/canario && echo EJECUTADO || echo limpio").trim()
        assertThat(canario).isEqualTo("limpio")
        // Y el intento de traversal no borró el archivo real de la raíz.
        assertThat(fixture.exec("cat ~/RemoteMarvinDocs/notas.txt").trim()).isEqualTo("contenido de prueba")
    }

    /**
     * Fase 1: un nombre no soportado (comilla) se LISTA pero marcado `soportado=false` (no como
     * callejón sin salida), y un nombre con salto de línea NO genera filas fantasma (el listado es
     * NUL-delimited, no newline-delimited).
     */
    @Test fun listDocs_marcaNoSoportadoYNoParteConSaltosDeLinea() {
        // el fixture ya siembra "raro'nombre.txt" (comilla)
        // creamos uno con salto de línea real en el nombre (comillas preservan el \n)
        fixture.exec("touch \"\$HOME/RemoteMarvinDocs/\$(printf 'con\\nsalto.txt')\"")

        val docs = control.listDocs()

        // la comilla se lista, pero marcada no-soportada
        val comilla = docs.firstOrNull { it.name.contains("raro") && it.name.contains("nombre") }
        assertThat(comilla).isNotNull()
        assertThat(comilla!!.soportado).isFalse()

        // el salto de línea NO produce una fila fantasma "salto.txt"
        assertThat(docs.map { it.name }).doesNotContain("salto.txt")
        // el archivo con salto se lista entero (una sola fila) y marcado no-soportado
        val conSalto = docs.firstOrNull { it.name.contains("con") && it.name.contains("salto") }
        assertThat(conSalto).isNotNull()
        assertThat(conSalto!!.soportado).isFalse()
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

    /**
     * WS-C: el reenganche debe distinguir "no me pude conectar" de "no hay sesiones".
     * Antes `sessionsWithLastLine` usaba `exec()` que se tragaba el error → ambos casos
     * daban lista vacía y el menú mostraba "No hay sesiones detacheadas" en los dos.
     */
    @Test fun sessionsWithLastLine_distingueNoConectarDeSinSesiones() {
        // Sin server tmux: lista vacía, NO lanza (es "no hay nada", legítimo).
        fixture.exec("tmux kill-server 2>/dev/null; true")
        assertThat(control.sessionsWithLastLine()).isEmpty()
        // Host inalcanzable (puerto muerto): LANZA con motivo (distinto de "no hay nada").
        val muerto = RemoteControl(ctx, host, 1, FixtureSsh.USER, KeyStoreSsh.getOrCreateKeyPair())
        val e = runCatching { muerto.sessionsWithLastLine() }.exceptionOrNull()
        assertThat(e).isNotNull()
        assertThat(e!!.message).isNotEmpty()
    }

    /**
     * WS-I (H-1): un camino NO interactivo (Documentos) NO puede FIJAR la clave del host en el
     * primer contacto. Si lo hiciera en silencio, un MITM en el primer contacto por IP directa
     * quedaría pinneado sin que el usuario viera nunca la huella. La confianza inicial la
     * establece la terminal (que muestra la huella); acá, sin pin previo, debe fallar-cerrado y
     * NO dejar ninguna clave fijada.
     */
    @Test fun documentosNoFijaLaClaveEnSilencio_enPrimerContacto() {
        HostKeys.forget(ctx, host, port)   // primer contacto: sin clave fijada
        assertThat(HostKeys.hasPinned(ctx, host, port)).isFalse()

        val e = runCatching { control.listDocs() }.exceptionOrNull()
        assertThat(e).isNotNull()                                   // falla-cerrado
        assertThat(HostKeys.hasPinned(ctx, host, port)).isFalse()  // y NO fijó nada
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
