package com.remoteclaude.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OSC 52 — política A + handshake (v1.20.0 → v1.22.0). El HOST puede pedir escribir el portapapeles del
 * teléfono por OSC 52, pero un proceso no confiable (un Claude inyectado, la salida de un archivo) podría
 * secuestrarlo. Política A: **sólo se copia si la copia la iniciaste VOS**.
 *
 * "Vos" ya NO es "el modo Sel está prendido" (ese toggle persistente era el bypass que reportó seguridad):
 * es una **ventana de un-solo-uso** que se abre al SOLTAR un arrastre en Sel (cuando tmux está por emitir
 * su OSC 52) y se consume al primer OSC 52. Estos tests fijan ese contrato:
 *  - host OSC 52 sin handshake → BLOQUEADO (no toca el portapapeles);
 *  - Sel prendido SIN arrastre → sigue BLOQUEADO (bypass cerrado);
 *  - tras soltar el arrastre → el OSC 52 de tmux SÍ copia.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardE2ETest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val host get() = args.getString("fixtureHost") ?: "127.0.0.1"
    private val port get() = (args.getString("fixturePort") ?: "2222").toInt()
    private lateinit var fx: FixtureSsh

    private fun intent() = Intent(ctx, MainActivity::class.java).apply {
        putExtra("hostname", host); putExtra("port", port)
        putExtra("user", FixtureSsh.USER); putExtra("hostId", "e2e-clip"); putExtra("label", "E2E")
    }

    private fun await(ms: Long = 30_000, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(250) }
        throw AssertionError("timeout esperando: $what")
    }

    @Before fun setUp() {
        fx = FixtureSsh(host, port)
        fx.authorize(KeyStoreSsh.openSshPublicKey("remoteclaude-app"))
        fx.killAllTmux()
        HostKeys.forget(ctx, host, port)
        ctx.getSharedPreferences("remotemarvin", Context.MODE_PRIVATE).edit()
            .remove("tabs_e2e-clip").remove("active_e2e-clip")
            .putBoolean("tour_off", true).commit()
    }

    @After fun tearDown() { runCatching { fx.killAllTmux() } }

    /** Envía un OSC 52 del host que intenta copiar `payload`, seguido de un marcador; espera a que el
     *  marcador aparezca en el panel (así el emulador ya procesó el OSC 52 que iba antes en el stream). */
    private fun enviarOsc52DelHost(scenario: ActivityScenario<MainActivity>, payload: String, marcador: String) {
        val cmd = "printf '\\033]52;c;%s\\a' \"\$(printf %s '$payload' | base64 -w0)\"; echo $marcador\n"
        scenario.onActivity { a ->
            val b = cmd.toByteArray(); a.currentSessionForTest()?.write(b, 0, b.size)
        }
        await(what = "el marcador $marcador llega al panel (OSC 52 ya procesado)") {
            fx.capturePane("term 1").contains(marcador)
        }
    }

    private fun portapapeles(scenario: ActivityScenario<MainActivity>): String {
        var got = ""
        scenario.onActivity { a ->
            got = a.getSystemService(ClipboardManager::class.java)
                ?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        }
        return got
    }

    private fun fijarSentinela(scenario: ActivityScenario<MainActivity>, s: String) {
        scenario.onActivity { a ->
            a.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("x", s))
        }
    }

    @Test fun hostOsc52SinHandshake_seBloquea() {
        ActivityScenario.launch<MainActivity>(intent()).use { scenario ->
            await(what = "sesión creada") { fx.tmuxSessions().contains("term 1") }
            fijarSentinela(scenario, "SENTINELA")
            val n = System.nanoTime()
            enviarOsc52DelHost(scenario, "SECUESTRO_$n", "FIN_$n")
            // Sin que vos iniciaras la copia, el OSC 52 del host NO toca el portapapeles.
            assertThat(portapapeles(scenario)).isEqualTo("SENTINELA")
        }
    }

    @Test fun selPrendidoSinArrastre_tampocoCopia() {
        // El bypass viejo: con Sel ON, cualquier OSC 52 del host pasaba. Ahora el toggle NO alcanza:
        // hace falta el evento de SOLTAR un arrastre. Prendemos Sel y el host sigue bloqueado.
        ActivityScenario.launch<MainActivity>(intent()).use { scenario ->
            await(what = "sesión creada") { fx.tmuxSessions().contains("term 1") }
            scenario.onActivity { it.activarSelForTest() }
            fijarSentinela(scenario, "SENTINELA")
            val n = System.nanoTime()
            enviarOsc52DelHost(scenario, "SECUESTRO_$n", "FIN_$n")
            assertThat(portapapeles(scenario)).isEqualTo("SENTINELA")
        }
    }

    @Test fun trasSoltarElArrastre_elOsc52DeTmuxCopia() {
        ActivityScenario.launch<MainActivity>(intent()).use { scenario ->
            await(what = "sesión creada") { fx.tmuxSessions().contains("term 1") }
            fijarSentinela(scenario, "SENTINELA")
            val n = System.nanoTime()
            val marca = "MARVIN_OK_$n"
            // Abrir la ventana del handshake ANTES de mandar el OSC 52 (como pasa de verdad: soltás el
            // arrastre y RECIÉN AHÍ tmux emite el OSC 52). Si no, un round-trip rápido puede llegar antes
            // de abrirla y el único OSC 52 se bloquea.
            scenario.onActivity { it.marcarSelDragSoltadoForTest() }
            val cmd = "printf '\\033]52;c;%s\\a' \"\$(printf %s '$marca' | base64 -w0)\"\n"
            scenario.onActivity { a ->
                val b = cmd.toByteArray(); a.currentSessionForTest()?.write(b, 0, b.size)
            }
            // Y mantenerla abierta mientras el OSC 52 hace el round-trip por tmux. El primer OSC 52
            // aceptado copia.
            await(what = "la copia iniciada por vos llega al portapapeles") {
                scenario.onActivity { it.marcarSelDragSoltadoForTest() }
                portapapeles(scenario) == marca
            }
        }
    }
}
