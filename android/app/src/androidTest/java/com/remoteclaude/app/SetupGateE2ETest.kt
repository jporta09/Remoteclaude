package com.remoteclaude.app

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
 * E2E del contrato "host configurado" (v1.29.0):
 *  - un host SIN setup-host no abre sesión (bloqueo con mensaje, no features fallando de a una);
 *  - en un host configurado, el mic de dictado arranca DESHABILITADO ("Preparando…") y se
 *    habilita recién cuando el motor del host respondió (invariante: habilitado ⇔ modelo cargado).
 */
@RunWith(AndroidJUnit4::class)
class SetupGateE2ETest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val host get() = args.getString("fixtureHost") ?: "127.0.0.1"
    private val port get() = (args.getString("fixturePort") ?: "2222").toInt()

    private lateinit var fixture: FixtureSsh
    private val hostId = "e2e-setupgate"

    private fun intent() = Intent(ctx, MainActivity::class.java).apply {
        putExtra("hostname", host); putExtra("port", port)
        putExtra("user", FixtureSsh.USER); putExtra("hostId", hostId); putExtra("label", "E2E")
    }

    @Before fun setUp() {
        fixture = FixtureSsh(host, port)
        fixture.authorize(KeyStoreSsh.openSshPublicKey("remoteclaude-app"))
        fixture.killAllTmux()
        HostKeys.forget(ctx, host, port)
        ctx.getSharedPreferences("remotemarvin", Context.MODE_PRIVATE).edit()
            .remove("tabs_$hostId").remove("active_$hostId")
            .putBoolean("tour_off", true).commit()
    }

    @After fun tearDown() {
        // Devolver el fixture a "configurado" pase lo que pase.
        runCatching {
            fixture.exec(
                "[ -f ~/.config/marvin/setup-ok.bak ] && mv ~/.config/marvin/setup-ok.bak ~/.config/marvin/setup-ok; " +
                    "[ -f ~/.local/bin/marvin-stt.bak ] && mv ~/.local/bin/marvin-stt.bak ~/.local/bin/marvin-stt; true",
            )
        }
        runCatching { fixture.killAllTmux() }
    }

    private fun esperar(que: String, ms: Long = 45_000, cond: () -> Boolean) {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) {
            if (runCatching(cond).getOrDefault(false)) return
            Thread.sleep(500)
        }
        throw AssertionError("esperando $que: no pasó en ${ms}ms")
    }

    @Test fun unHostSinConfigurar_noAbreSesion() {
        // Sin marker Y sin marvin-stt (el fallback para setups viejos): host pelado.
        fixture.exec(
            "mv ~/.config/marvin/setup-ok ~/.config/marvin/setup-ok.bak && " +
                "mv ~/.local/bin/marvin-stt ~/.local/bin/marvin-stt.bak",
        )
        ActivityScenario.launch<MainActivity>(intent()).use { esc ->
            // El bloqueo es determinista: la sesión pasa a CAIDO y NO se crea tmux.
            esperar("el estado CAIDO del gate") {
                var caido = false
                esc.onActivity { a -> caido = a.currentSessionForTest()?.estado == SshTerminalSession.Estado.CAIDO }
                caido
            }
            assertThat(fixture.tmuxSessions()).doesNotContain("term 1")
        }
    }

    @Test fun elMicArrancaDeshabilitadoYSeHabilitaConElMotorListo() {
        ActivityScenario.launch<MainActivity>(intent()).use { esc ->
            esperar("la sesión creada") { fixture.tmuxSessions().contains("term 1") }
            // El fixture no tiene systemctl → despertarStt cae al prewarm del stub → "batch".
            esperar("el mic habilitado tras despertar el motor", ms = 30_000) {
                var habilitado = false
                esc.onActivity { a -> habilitado = a.keypadForTest().vistaMic()?.isEnabled == true }
                habilitado
            }
        }
    }
}
