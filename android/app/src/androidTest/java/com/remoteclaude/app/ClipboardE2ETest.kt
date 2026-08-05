package com.remoteclaude.app

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OSC 52: el HOST puede pedir escribir el portapapeles del teléfono. Para una selección
 * de tmux es lo esperado, pero el buffer admite 1 MB, así que arriba de cierto tamaño la
 * app tiene que pedir confirmación en vez de copiar en silencio.
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
            .remove("tabs_e2e-clip").remove("active_e2e-clip").commit()
    }

    @After fun tearDown() { runCatching { fx.killAllTmux() } }

    @Test fun unOsc52Gigante_pideConfirmacion() {
        ActivityScenario.launch<MainActivity>(intent()).use { scenario ->
            await(what = "sesión creada") { fx.tmuxSessions().contains("term 1") }
            // 150 KB decodificados: por encima del umbral de confirmación
            val cmd = "printf '\\033]52;c;%s\\a' \"\$(head -c 150000 /dev/zero | base64 -w0)\"\n"
            scenario.onActivity { a ->
                val b = cmd.toByteArray()
                a.currentSessionForTest()?.write(b, 0, b.size)
            }
            Thread.sleep(6000)
            onView(withText("¿Copiar al portapapeles?"))
                .inRoot(isDialog())
                .check(matches(isDisplayed()))
        }
    }
}
