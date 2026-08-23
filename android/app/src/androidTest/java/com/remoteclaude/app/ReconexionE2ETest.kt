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
 * E2E de la reconexión: es la promesa central del producto — bloqueás el celular, se corta el
 * wifi, volvés y seguís donde estabas.
 *
 * El corte se provoca con `e2e-drop-ssh` del fixture, que mata el sshd del usuario: la sesión
 * de la app muere de verdad, no se simula. `tmux` vive del lado del host, así que lo que se
 * verifica es que la sesión sobreviva al corte y que la app la reenganche con su contenido.
 */
@RunWith(AndroidJUnit4::class)
class ReconexionE2ETest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val host get() = args.getString("fixtureHost") ?: "127.0.0.1"
    private val port get() = (args.getString("fixturePort") ?: "2222").toInt()

    private lateinit var fixture: FixtureSsh
    private val hostId = "e2e-reconexion"

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
        runCatching { fixture.killAllTmux() }
    }

    @Test fun laSesionTmuxSobreviveAlCorteYLaAppLaReengancha() {
        ActivityScenario.launch<MainActivity>(intent()).use { esc ->
            esperar("la sesión creada") { fixture.tmuxSessions().contains("term 1") }

            // Dejar una marca en el panel: es lo que tiene que seguir ahí después del corte.
            val marca = "marca-${System.currentTimeMillis()}"
            esc.onActivity { a ->
                val cmd = "echo $marca\n".toByteArray()
                a.currentSessionForTest()?.write(cmd, 0, cmd.size)
            }
            esperar("que el comando llegue al host") { fixture.capturePane("term 1").contains(marca) }

            // Cortar de verdad: mata el sshd del usuario (el master sigue, así que se puede
            // volver a conectar).
            fixture.exec("e2e-drop-ssh")
            Thread.sleep(3_000)

            // tmux vive en el host: la sesión NO se va con la conexión.
            esperar("que la sesión siga viva tras el corte") {
                fixture.tmuxSessions().contains("term 1")
            }
            assertThat(fixture.capturePane("term 1")).contains(marca)
        }
    }

    @Test fun trasElCorte_laAppVuelveAEscribirEnLaMismaSesion() {
        // Lo que importa de reenganchar: que lo que tipeás DESPUÉS siga yendo a la sesión de
        // antes, y no a una nueva vacía (que es lo que haría `tmux new` sin `-A`).
        ActivityScenario.launch<MainActivity>(intent()).use { esc ->
            esperar("la sesión creada") { fixture.tmuxSessions().contains("term 1") }
            fixture.exec("e2e-drop-ssh")
            Thread.sleep(3_000)

            val marca = "post-corte-${System.currentTimeMillis()}"
            esperar("que la app reconecte y el comando llegue", 60_000) {
                esc.onActivity { a ->
                    val cmd = "echo $marca\n".toByteArray()
                    a.currentSessionForTest()?.write(cmd, 0, cmd.size)
                }
                fixture.capturePane("term 1").contains(marca)
            }
            // Y sin duplicar: reenganchó la que ya estaba.
            assertThat(fixture.tmuxSessions().count { it == "term 1" }).isEqualTo(1)
        }
    }

    @Test fun elCorteNoCreaSesionesDeMas() {
        ActivityScenario.launch<MainActivity>(intent()).use {
            esperar("la sesión creada") { fixture.tmuxSessions().contains("term 1") }
            fixture.exec("e2e-drop-ssh")
            Thread.sleep(5_000)
            esperar("que reconecte", 60_000) { fixture.tmuxSessions().isNotEmpty() }
            // Si el reintento creara una sesión nueva en vez de reenganchar, acá habría
            // "term 2" y el usuario perdería de vista su trabajo anterior.
            assertThat(fixture.tmuxSessions()).containsExactly("term 1")
        }
    }

    @Test fun unHalfOpenEnPrimerPlano_seDetectaYReconecta() {
        // Half-open (path-death SIN RST) estando EN PRIMER PLANO (F-SRE-1). Antes quedaba mudo (el
        // keepalive sólo escribía; forzarReconexion sólo dispara al volver de background). Ahora el
        // heurístico "mandé input y no volvió el eco" + un probe FUERA DE BANDA lo detectan y reconectan
        // sin ir a background. `e2e-blackhole-ssh` hace un iptables DROP self-restoring (~30s).
        ActivityScenario.launch<MainActivity>(intent()).use { esc ->
            esperar("la sesión creada") { fixture.tmuxSessions().contains("term 1") }
            val m0 = "pre-${System.currentTimeMillis()}"
            esc.onActivity { a -> val c = "echo $m0\n".toByteArray(); a.currentSessionForTest()?.write(c, 0, c.size) }
            esperar("que la sesión funciona antes del blackhole") { fixture.capturePane("term 1").contains(m0) }

            fixture.exec("e2e-blackhole-ssh")     // path-death por ~30s, self-restoring
            Thread.sleep(2_000)

            // UN solo input para gatillar el heurístico (mandaste algo, no vuelve el eco). NO seguir
            // escribiendo: si tipearas sin parar, el timer de "sin eco" nunca acumularía y no dispararía.
            esc.onActivity { a -> val c = "x".toByteArray(); a.currentSessionForTest()?.write(c, 0, c.size) }

            // Esperar a que detecte (heurístico ~10-17s) + reconecte cuando el blackhole se auto-restaura
            // (~30s). Durante ese rato NO escribimos (para no resetear el timer). La Activity está RESUMED
            // todo el tiempo: no se va a background, que es lo que prueba el fix.
            Thread.sleep(38_000)

            // Ya restaurado y (si el fix anda) reconectado: lo que tipees ahora tiene que llegar.
            val marca = "post-halfopen-${System.currentTimeMillis()}"
            esperar("que tras el half-open la app reconecte y el comando llegue", 45_000) {
                esc.onActivity { a -> val c = "echo $marca\n".toByteArray(); a.currentSessionForTest()?.write(c, 0, c.size) }
                fixture.capturePane("term 1").contains(marca)
            }
            assertThat(fixture.tmuxSessions().count { it == "term 1" }).isEqualTo(1)
        }
    }

    private fun esperar(que: String, ms: Long = 30_000, cond: () -> Boolean) {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) {
            if (runCatching { cond() }.getOrDefault(false)) return
            Thread.sleep(500)
        }
        throw AssertionError("timeout esperando: $que")
    }
}
