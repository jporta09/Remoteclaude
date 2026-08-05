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

/** Pestañas: nombres, reenganche y — sobre todo — que un nombre hostil no ejecute nada. */
@RunWith(AndroidJUnit4::class)
class TabsE2ETest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val host get() = args.getString("fixtureHost") ?: "127.0.0.1"
    private val port get() = (args.getString("fixturePort") ?: "2222").toInt()
    private lateinit var fx: FixtureSsh

    private fun intent() = Intent(ctx, MainActivity::class.java).apply {
        putExtra("hostname", host); putExtra("port", port)
        putExtra("user", FixtureSsh.USER); putExtra("hostId", "e2e-tabs"); putExtra("label", "E2E")
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
            .remove("tabs_e2e-tabs").remove("active_e2e-tabs").commit()
    }

    @After fun tearDown() { runCatching { fx.killAllTmux() } }

    /**
     * `tmux new -A` debe REENGANCHARSE a la sesión que ya existe, no crear otra.
     *
     * Se verifica con una marca escrita DESPUÉS de conectar: si la app estuviera en una
     * sesión distinta, nunca la vería. (No se asserta el scrollback previo: al adjuntarse
     * con un tamaño de pane distinto, tmux redibuja y el contenido anterior puede
     * reflowear — eso es de tmux, no del producto.)
     */
    @Test fun reengancha_laMismaSesion_sinDuplicarla() {
        fx.exec("tmux new -d -s 'term 1'")
        await(what = "el pane del fixture está listo") {
            fx.exec("tmux send-keys -t 'term 1' 'true' Enter; sleep 1; tmux ls").contains("term 1")
        }
        // La pestaña tiene que estar en la lista persistida: si no, la app considera
        // 'term 1' ocupada y abre la siguiente libre ('term 2'), que es lo correcto para
        // una pestaña NUEVA. El reenganche es el caso "vuelvo a abrir la app".
        ctx.getSharedPreferences("remotemarvin", Context.MODE_PRIVATE).edit()
            .putString("tabs_e2e-tabs", "term 1").putInt("active_e2e-tabs", 0).commit()
        ActivityScenario.launch<MainActivity>(intent()).use { scenario ->
            await(what = "la app quedó conectada") {
                fx.exec("tmux list-clients -t 'term 1' 2>/dev/null").isNotBlank()
            }
            // no se creó una segunda sesión
            assertThat(fx.tmuxSessions()).containsExactly("term 1")
            // y lo que el host escribe en ESA sesión aparece en la pantalla de la app
            fx.exec("tmux send-keys -t 'term 1' 'echo MARCA_VIVA' Enter")
            var visto = ""
            await(what = "la marca escrita desde el host") {
                scenario.onActivity { a -> visto = a.screenText() }
                visto.contains("MARCA_VIVA")
            }
            assertThat(visto).contains("MARCA_VIVA")
        }
    }

    /**
     * Regresión de S6: un nombre de sesión con comilla NO debe ejecutar nada en el host.
     * Antes los comandos se armaban interpolando el nombre entre comillas simples, así que
     * `x'; touch /tmp/pwned; '` cerraba el literal y corría lo que siguiera.
     */
    @Test fun unNombreHostilNoEjecutaNada() {
        val hostil = "x'; touch /tmp/pwned; '"
        fx.exec("rm -f /tmp/pwned")
        // el nombre entra por la persistencia, que es una de las fuentes no confiables
        ctx.getSharedPreferences("remotemarvin", Context.MODE_PRIVATE).edit()
            .putString("tabs_e2e-tabs", hostil).putInt("active_e2e-tabs", 0).commit()

        ActivityScenario.launch<MainActivity>(intent()).use {
            // se le da tiempo a conectar (con el nombre saneado) y a que el host reaccione
            Thread.sleep(8000)
        }
        assertThat(fx.exec("test -e /tmp/pwned && echo PWNED || echo LIMPIO")).contains("LIMPIO")
    }
}
