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
 * Las dos caras del bug de `3795a8d`: al rotar y al volver a abrir, la app tiene que seguir en
 * la pestaña donde estabas.
 *
 * Es un bug barato de reintroducir, porque el orden importa: cada `abrir()` activa la pestaña
 * recién creada y persiste ESE índice, así que si el índice guardado se lee después de abrir
 * las pestañas, siempre se restaura la última. Se arregló leyéndolo antes; nada impedía que
 * volviera.
 */
@RunWith(AndroidJUnit4::class)
class RotacionE2ETest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val host get() = args.getString("fixtureHost") ?: "127.0.0.1"
    private val port get() = (args.getString("fixturePort") ?: "2222").toInt()

    private lateinit var fixture: FixtureSsh
    private val hostId = "e2e-rotacion"
    private val prefs get() = ctx.getSharedPreferences("remotemarvin", Context.MODE_PRIVATE)

    private fun intent() = Intent(ctx, MainActivity::class.java).apply {
        putExtra("hostname", host); putExtra("port", port)
        putExtra("user", FixtureSsh.USER); putExtra("hostId", hostId); putExtra("label", "E2E")
    }

    /** Deja el estado que la app persiste: qué pestañas hay y cuál estaba activa. */
    private fun sembrar(pestanas: List<String>, activa: Int) {
        prefs.edit()
            .putString("tabs_$hostId", pestanas.joinToString("\n"))
            .putInt("active_$hostId", activa)
            .commit()
    }

    @Before fun setUp() {
        fixture = FixtureSsh(host, port)
        fixture.authorize(KeyStoreSsh.openSshPublicKey("remoteclaude-app"))
        fixture.killAllTmux()
        HostKeys.forget(ctx, host, port)
        prefs.edit().remove("tabs_$hostId").remove("active_$hostId")
            .putBoolean("tour_off", true).commit()
    }

    @After fun tearDown() {
        runCatching { fixture.killAllTmux() }
        prefs.edit().remove("tabs_$hostId").remove("active_$hostId").commit()
    }

    /**
     * OJO con lo que se puede afirmar acá: las sesiones se conectan de forma PEREZOSA, al
     * engancharse la vista de terminal. Restaurar tres pestañas crea UNA sola sesión en el
     * host —la activa—; las otras dos recién existen cuando las tocás. Por eso los tests
     * miran cuál quedó ENGANCHADA y no cuántas hay: es lo observable de verdad, y además
     * detecta mejor el bug original (restaurar la última en vez de la guardada).
     */
    private fun sesionEnganchada(): String? =
        listOf("term 1", "term 2", "term 3").firstOrNull { n ->
            fixture.exec("tmux list-clients -t '$n' 2>/dev/null").isNotBlank()
        }

    @Test fun elArranqueEnFrioRestauraLaPestanaActiva_noLaUltima() {
        // La activa NO puede ser ni la primera ni la última: con dos pestañas, "restaurar la
        // última" pasaría de casualidad.
        sembrar(listOf("term 1", "term 2", "term 3"), activa = 1)

        ActivityScenario.launch<MainActivity>(intent()).use { esc ->
            esperar("que la app enganche la pestaña guardada") { sesionEnganchada() == "term 2" }
            var actual: String? = null
            esc.onActivity { a -> actual = a.currentSessionForTest()?.tmuxSession }
            assertThat(actual).isEqualTo("term 2")
        }
    }

    @Test fun rotarNoTeMueveDePestana() {
        sembrar(listOf("term 1", "term 2", "term 3"), activa = 2)

        ActivityScenario.launch<MainActivity>(intent()).use { esc ->
            esperar("que enganche term 3") { sesionEnganchada() == "term 3" }

            // recreate() es lo que hace Android al rotar: destruye y recrea la activity.
            esc.recreate()

            esperar("que siga en term 3 después de rotar") {
                var actual: String? = null
                esc.onActivity { a -> actual = a.currentSessionForTest()?.tmuxSession }
                actual == "term 3"
            }
            assertThat(sesionEnganchada()).isEqualTo("term 3")
        }
    }

    @Test fun rotarNoDuplicaLaSesionDelHost() {
        // Al recrearse, la app vuelve a abrir su pestaña: si no reenganchara con `tmux -A`,
        // cada rotación dejaría una sesión nueva tirada en el host.
        sembrar(listOf("term 1", "term 2"), activa = 1)

        ActivityScenario.launch<MainActivity>(intent()).use { esc ->
            esperar("que enganche term 2") { sesionEnganchada() == "term 2" }
            esc.recreate()
            esperar("que vuelva a enganchar term 2") { sesionEnganchada() == "term 2" }
            assertThat(fixture.tmuxSessions()).containsExactly("term 2")
        }
    }

    private fun esperar(que: String, ms: Long = 45_000, cond: () -> Boolean) {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) {
            if (runCatching { cond() }.getOrDefault(false)) return
            Thread.sleep(500)
        }
        throw AssertionError("timeout esperando: $que")
    }
}
