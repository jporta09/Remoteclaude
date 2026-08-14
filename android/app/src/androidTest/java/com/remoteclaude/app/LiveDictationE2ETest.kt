package com.remoteclaude.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E del dictado EN VIVO contra el stub de WhisperLiveKit del fixture.
 *
 * Es el camino con más partes móviles de la app y hasta ahora no tenía ninguna prueba: túnel
 * SSH local → WebSocket → chunks de audio en orden → snapshots parciales → cierre limpio.
 * `WlkSnapshot` ya está cubierto por tests JVM; lo que se prueba acá es todo lo que lo rodea.
 */
@RunWith(AndroidJUnit4::class)
class LiveDictationE2ETest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val host get() = args.getString("fixtureHost") ?: "127.0.0.1"
    private val port get() = (args.getString("fixturePort") ?: "2222").toInt()

    private lateinit var fixture: FixtureSsh
    private val parciales = CopyOnWriteArrayList<String>()
    private var live: LiveDictation? = null

    private fun nuevo() = LiveDictation(
        ctx, host, port, FixtureSsh.USER, KeyStoreSsh.getOrCreateKeyPair(),
    ) { txt -> parciales.add(txt) }.also { live = it }

    /** Un chunk de PCM cualquiera: al stub le alcanza con que no esté vacío. */
    private fun chunk(bytes: Int = 640) = ByteArray(bytes)

    @Before fun setUp() {
        fixture = FixtureSsh(host, port)
        fixture.authorize(KeyStoreSsh.openSshPublicKey("remoteclaude-app"))
        HostKeys.forget(ctx, host, port)
        parciales.clear()
    }

    @After fun tearDown() {
        runCatching { live?.cancel() }
        live = null
    }

    @Test fun seConectaAlServerEnVivoDelHost() {
        val l = nuevo()
        assertThat(l.start()).isTrue()
        assertThat(l.available).isTrue()
    }

    @Test fun elAudioProduceParcialesMientrasHablas() {
        // Lo que hace útil el modo en vivo: ver el texto ANTES de soltar el micrófono.
        val l = nuevo()
        assertThat(l.start()).isTrue()
        repeat(3) { l.feed(chunk()) }
        esperar("que lleguen parciales") { parciales.isNotEmpty() }
        assertThat(parciales.last()).contains("hola")
    }

    @Test fun alSoltarDevuelveElTextoFinal() {
        val l = nuevo()
        assertThat(l.start()).isTrue()
        l.feed(chunk())
        assertThat(l.stop()).isEqualTo("hola marvin")
    }

    @Test fun losChunksDeAntesDeAbrirNoSePierden() {
        // feed() puede llegar antes de que el WebSocket abra: la app los encola y los drena
        // al conectar. Si se perdieran, faltaría el arranque de cada dictado — justo la
        // parte donde uno dice la primera palabra.
        val l = nuevo()
        l.feed(chunk())          // ANTES de start()
        l.feed(chunk())
        assertThat(l.start()).isTrue()
        esperar("que lleguen parciales de los chunks encolados") { parciales.isNotEmpty() }
        assertThat(l.stop()).isEqualTo("hola marvin")
    }

    @Test fun siElServerEnVivoNoEsta_seAvisaEnVezDeColgarse() {
        // El contrato del fallback: start() devuelve false y el dictado por lote se hace
        // cargo. Si en vez de eso quedara colgado, el usuario suelta el micrófono y no pasa
        // nada.
        //
        // Se simula con una bandera que el stub mira al aceptar, NO matándolo: matarlo y
        // reponerlo es una carrera —el que arranca mientras el viejo agoniza no puede
        // bindear— y dejaba sin server a los tests siguientes, que fallaban por culpa de
        // éste con el motivo lejos de la causa.
        fixture.exec("touch /tmp/wlk-stub-apagado")
        try {
            val l = nuevo()
            assertThat(l.start()).isFalse()
            assertThat(l.available).isFalse()
        } finally {
            fixture.exec("rm -f /tmp/wlk-stub-apagado")
        }
    }

    @Test fun cancelarNoDejaNadaAbierto() {
        // cancel() puede correr mientras el WebSocket todavía está abriendo (dictado corto):
        // antes eso dejaba colgados una conexión SSH, un ServerSocket y un WebSocket por vez.
        val l = nuevo()
        l.start()
        l.cancel()
        assertThat(l.available).isFalse()
        // y el server sigue aceptando a alguien nuevo: si hubiéramos dejado basura, esto falla
        val otro = nuevo()
        assertThat(otro.start()).isTrue()
    }

    private fun esperar(que: String, ms: Long = 10_000, cond: () -> Boolean) {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) {
            if (cond()) return
            Thread.sleep(200)
        }
        throw AssertionError("timeout esperando: $que")
    }
}
