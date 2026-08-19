package com.remoteclaude.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2E del visor noVNC — WS-J. Cuando el visor cae al endpoint DIRECTO por una IP cruda
 * (Tailscale apagado + túnel SSH imposible, host viejo), Android bloquea el HTTP en claro y
 * Chrome tira `net::ERR_CLEARTEXT_NOT_PERMITTED`. Antes eso mostraba la página de error cruda
 * de Chrome, incomprensible; ahora se muestra un mensaje traducido que explica qué pasó y cómo
 * verlo (activar Tailscale o actualizar el host para tunelizar por SSH).
 *
 * Se fuerza el camino directo dejando el host SIN clave fijada: los caminos no interactivos
 * (túnel, vncPassword) fallan-cerrado (WS-I) y el visor cae al endpoint directo por IP cruda.
 */
@RunWith(AndroidJUnit4::class)
class DisplayE2ETest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val host get() = args.getString("fixtureHost") ?: "127.0.0.1"
    private val port get() = (args.getString("fixturePort") ?: "2222").toInt()

    @Before fun setUp() {
        HostKeys.forget(ctx, host, port)   // sin pin -> el visor va directo por IP cruda
        ctx.getSharedPreferences("remotemarvin", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("tour_off", true).commit()
    }

    @Test fun visorPorIpCruda_muestraMensajeTraducido_noPaginaCrudaDeChrome() {
        // En el emulador el fixtureHost es 10.0.2.2: una IP cruda, ni loopback ni MagicDNS, así
        // que NO está permitida para cleartext y la carga por HTTP directo se bloquea.
        val intent = Intent(ctx, DisplayActivity::class.java).apply {
            putExtra("hostname", host)
            putExtra("port", port)
            putExtra("user", FixtureSsh.USER)
        }
        ActivityScenario.launch<DisplayActivity>(intent).use { esc ->
            esperar("el mensaje de error traducido del visor") {
                var visible = false
                esc.onActivity { a -> visible = a.errorVisibleParaTest() }
                visible
            }
            var texto = ""
            esc.onActivity { a -> texto = a.errorTextoParaTest() }
            // Mensaje propio, no la página cruda de Chrome ni un ERR_ suelto.
            assertThat(texto).contains("tráfico en claro")
        }
    }

    private fun esperar(que: String, ms: Long = 45_000, cond: () -> Boolean) {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) {
            if (cond()) return
            Thread.sleep(300)
        }
        throw AssertionError("timeout esperando: $que")
    }
}
