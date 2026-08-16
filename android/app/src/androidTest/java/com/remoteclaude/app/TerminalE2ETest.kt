package com.remoteclaude.app

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
 * E2E de la terminal contra el fixture desechable (test/e2e), NUNCA contra el host real:
 * estos tests crean y matan sesiones tmux.
 *
 * El fixture se alcanza en 127.0.0.1:2222 — en el emulador por `10.0.2.2` y en un
 * teléfono por `adb reverse tcp:2222 tcp:2222`, así no queda expuesto a ninguna red.
 */
@RunWith(AndroidJUnit4::class)
class TerminalE2ETest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val fixtureHost get() = args.getString("fixtureHost") ?: "127.0.0.1"
    private val fixturePort get() = (args.getString("fixturePort") ?: "2222").toInt()
    private val rotatedPort get() = (args.getString("fixtureRotatedPort") ?: "2223").toInt()

    private lateinit var fixture: FixtureSsh

    private fun hostId(port: Int) = "e2e-$port"

    private fun intentFor(port: Int) =
        Intent(ctx, MainActivity::class.java).apply {
            putExtra("hostname", fixtureHost)
            putExtra("port", port)
            putExtra("user", FixtureSsh.USER)
            putExtra("hostId", hostId(port))
            putExtra("label", "E2E")
        }

    private fun await(timeoutMs: Long = 30_000, what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(250)
        }
        throw AssertionError("timeout esperando: $what")
    }

    @Before fun setUp() {
        fixture = FixtureSsh(fixtureHost, fixturePort)
        fixture.authorize(KeyStoreSsh.openSshPublicKey("remoteclaude-app"))
        fixture.killAllTmux()
        // el pin del fixture puede quedar de una corrida anterior
        HostKeys.forget(ctx, fixtureHost, fixturePort)
        HostKeys.forget(ctx, fixtureHost, rotatedPort)
        // pestañas persistidas de corridas previas
        ctx.getSharedPreferences("remotemarvin", android.content.Context.MODE_PRIVATE)
            .edit()
            .remove("tabs_${hostId(fixturePort)}").remove("active_${hostId(fixturePort)}")
            .remove("tabs_${hostId(rotatedPort)}").remove("active_${hostId(rotatedPort)}")
            .commit()
    }

    @After fun tearDown() {
        runCatching { fixture.killAllTmux() }
    }

    /** El núcleo del producto: conectar crea (o reengancha) la sesión tmux del host. */
    @Test fun conectar_creaLaSesionTmuxEnElHost() {
        ActivityScenario.launch<MainActivity>(intentFor(fixturePort)).use {
            await(what = "que aparezca 'term 1' en el fixture") {
                fixture.tmuxSessions().contains("term 1")
            }
            // El entorno que la app promete a sus sesiones. No es decorativo: de
            // MARVIN_DISPLAY depende el túnel del display (~/.ssh/config lo usa en un
            // Match exec), y de CLAUDE_CODE_NO_FLICKER que Claude Code arranque en
            // fullscreen — sin él, leer con el dedo en el teléfono te deja mirando el
            // historial congelado de tmux (ver docs/revision-integral.md). Se chequea en
            // el global env de tmux, que es donde lo hereda cada panel.
            val env = fixture.exec("tmux show-environment -g 2>/dev/null")
            for (esperado in listOf("MARVIN_DISPLAY=localhost:6099", "CLAUDE_CODE_NO_FLICKER=1")) {
                if (!env.lineSequence().any { it.trim() == esperado }) {
                    throw AssertionError("falta $esperado en el env global de tmux; hay:\n$env")
                }
            }
        }
    }

    /** Ida y vuelta completa: lo que tipeás llega al host y su salida vuelve a la pantalla. */
    @Test fun loTipeadoLlegaAlHost_yLaSalidaVuelve() {
        ActivityScenario.launch<MainActivity>(intentFor(fixturePort)).use { scenario ->
            await(what = "sesión tmux creada") { fixture.tmuxSessions().contains("term 1") }
            scenario.onActivity { a ->
                val cmd = "echo MARVIN_E2E_OK\n".toByteArray()
                a.screenText()   // fuerza que el emulador ya esté inicializado
                a.currentSessionForTest()?.write(cmd, 0, cmd.size)
            }
            // del lado del host: el comando efectivamente corrió
            await(what = "el host ejecutó el comando") {
                fixture.capturePane("term 1").contains("MARVIN_E2E_OK")
            }
            // del lado de la app: la salida volvió al emulador de terminal
            var visto = ""
            await(what = "la salida llegó a la terminal") {
                scenario.onActivity { a -> visto = a.screenText() }
                visto.contains("MARVIN_E2E_OK")
            }
            assertThat(visto).contains("MARVIN_E2E_OK")
        }
    }

    /**
     * Regresión de S2: si el server presenta una clave distinta a la fijada, la conexión
     * tiene que quedar BLOQUEADA — nada de reconectar en silencio contra un impostor.
     *
     * Se simula corrompiendo el pin guardado (el test corre en el proceso de la app, así
     * que puede tocar las prefs): equivale a que el server haya cambiado de clave.
     */
    @Test fun siCambiaLaClaveDelHost_seBloquea() {
        // 1) primera conexión: fija la clave real del fixture
        ActivityScenario.launch<MainActivity>(intentFor(fixturePort)).use {
            await(what = "primera conexión (fija la clave)") {
                fixture.tmuxSessions().contains("term 1")
            }
        }
        assertThat(HostKeys.hasPinned(ctx, fixtureHost, fixturePort)).isTrue()

        // 2) se falsea el pin: ahora lo guardado NO coincide con lo que presenta el server
        val prefs = ctx.getSharedPreferences("remotemarvin", android.content.Context.MODE_PRIVATE)
        val prefijo = "hostkey_${fixtureHost}_${fixturePort}_"
        val entradas = prefs.all.keys.filter { it.startsWith(prefijo) }
        assertThat(entradas).isNotEmpty()
        prefs.edit().apply { entradas.forEach { putString(it, "Y2xhdmUtZmFsc2E=") } }.commit()
        fixture.killAllTmux()

        // 3) al reconectar tiene que bloquear y avisarlo, sin crear sesión en el host
        ActivityScenario.launch<MainActivity>(intentFor(fixturePort)).use { scenario ->
            var visto = ""
            await(what = "el aviso de clave cambiada en la terminal") {
                scenario.onActivity { a -> visto = a.screenText() }
                visto.contains("LA CLAVE DEL HOST CAMBIÓ")
            }
            assertThat(visto).contains("LA CLAVE DEL HOST CAMBIÓ")
            // y sobre todo: NO se conectó
            assertThat(fixture.tmuxSessions()).doesNotContain("term 1")
        }
    }
}
