package com.remoteclaude.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E del dictado por lote contra el fixture, que trae un stub de `marvin-stt`: valida que
 * le llegue un WAV de verdad y responde un texto fijo, sin modelo ni GPU.
 *
 * Lo que se protege es el contrato del camino: el audio viaja por SSH y lo que vuelve se
 * **tipea en la terminal**. Si un mensaje de error volviera como si fuera la transcripción,
 * la app escribiría "curl: (28) Operation timed out" en la sesión del usuario.
 */
@RunWith(AndroidJUnit4::class)
class DictationE2ETest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val host get() = args.getString("fixtureHost") ?: "127.0.0.1"
    private val port get() = (args.getString("fixturePort") ?: "2222").toInt()

    private lateinit var fixture: FixtureSsh
    private lateinit var control: RemoteControl

    /** Un WAV mínimo pero válido, armado con el mismo header que usa la app al grabar. */
    private fun wavDePrueba(muestras: Int = 1600): ByteArray {
        val pcm = ByteArray(muestras * 2)                 // silencio, 16 bits
        return WavHeader.of(pcm.size) + pcm
    }

    @Before fun setUp() {
        fixture = FixtureSsh(host, port)
        fixture.authorize(KeyStoreSsh.openSshPublicKey("remoteclaude-app"))
        HostKeys.forget(ctx, host, port)
        control = RemoteControl(ctx, host, port, FixtureSsh.USER, KeyStoreSsh.getOrCreateKeyPair())
        // WS-I: los caminos no interactivos ya no fijan la primera clave; la terminal lo haría
        // antes en el uso real. Se simula ese paso para que el dictado conecte.
        control.confiarEnClaveDelHost()
    }

    @After fun tearDown() {
        // Devolver el stub si algún test lo escondió.
        runCatching {
            fixture.exec("[ -f ~/.local/bin/marvin-stt.bak ] && mv ~/.local/bin/marvin-stt.bak ~/.local/bin/marvin-stt; true")
        }
    }

    @Test fun elAudioViajaYVuelveElTexto() {
        assertThat(control.transcribe(wavDePrueba())).isEqualTo("hola marvin")
    }

    @Test fun elHostRecibeUnWavDeVerdad() {
        // El stub exige la marca RIFF: si el header que arma la app se rompiera, esto falla
        // acá y no en el teléfono con un dictado que "no entiende nada".
        val e = runCatching { control.transcribe("esto no es audio".toByteArray()) }.exceptionOrNull()
        assertThat(e).isNotNull()
    }

    @Test fun unErrorDelHostNoSeTipeaComoSiFueraLaTranscripcion() {
        // La regresión concreta: el cliente falla, imprime algo por stderr, y ese texto
        // terminaba escribiéndose en la terminal del usuario como si lo hubiera dictado.
        val e = runCatching { control.transcribe("no soy un wav".toByteArray()) }.exceptionOrNull()
        assertThat(e).isNotNull()
        assertThat(e!!.message).contains("no es un WAV")
    }

    @Test fun siFaltaElClienteEnElHost_seAvisaEnVezDeQuedarEnSilencio() {
        fixture.exec("mv ~/.local/bin/marvin-stt ~/.local/bin/marvin-stt.bak")
        val e = runCatching { control.transcribe(wavDePrueba()) }.exceptionOrNull()
        assertThat(e).isNotNull()
        assertThat(e!!.message).isNotEmpty()
    }

    @Test fun elModoDeEnergiaSeConsultaEnElHost() {
        // Lo que lee el diálogo de edición del host para mostrar el switch de "siempre
        // encendido". Con el stub responde cualquier cosa: lo que importa es que la llamada
        // llegue y vuelva algo, no que quede colgada.
        fixture.exec("printf '#!/bin/bash\\necho \"modo: ondemand | daemon apagado\"\\n' > ~/.local/bin/marvin-stt.bak2; " +
            "cp ~/.local/bin/marvin-stt ~/.local/bin/marvin-stt.bak; " +
            "cp ~/.local/bin/marvin-stt.bak2 ~/.local/bin/marvin-stt; chmod +x ~/.local/bin/marvin-stt")
        assertThat(control.sttMode("status")).contains("modo")
    }
}
