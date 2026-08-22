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
 * E2E del canal de avisos "Claude te espera" ([NotificacionesRemotas]) contra el fixture.
 *
 * Verifica el fix headline de la Fase 1 (cursor por `ts` en vez de `tail -n0`):
 *  - una línea escrita en notify.jsonl MIENTRAS el canal está caído se ENTREGA al reconectar
 *    (antes se perdía: `tail -n0` reabría en el EOF nuevo y se la salteaba);
 *  - al reconectar y re-leer las 200 últimas, una línea YA vista NO se re-emite (dedup por cursor).
 *
 * Lanza la MainActivity (que corre el canal best-effort con la inicialización de red real) y observa
 * por [Diagnostico] (categoría "avisos"): `procesar` registra "aviso recibido" por cada línea nueva
 * ANTES del gate de primer plano, así que cuenta sin depender de permisos de notificación. El corte se
 * provoca con `e2e-drop-ssh` del fixture (mata el sshd del usuario → cae el canal SSH).
 */
@RunWith(AndroidJUnit4::class)
class AvisosE2ETest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val host get() = args.getString("fixtureHost") ?: "127.0.0.1"
    private val port get() = (args.getString("fixturePort") ?: "2222").toInt()

    private lateinit var fixture: FixtureSsh
    private val hostId = "e2e-avisos"

    private fun intent() = Intent(ctx, MainActivity::class.java).apply {
        putExtra("hostname", host); putExtra("port", port)
        putExtra("user", FixtureSsh.USER); putExtra("hostId", hostId); putExtra("label", "E2E")
    }

    @Before fun setUp() {
        fixture = FixtureSsh(host, port)
        fixture.authorize(KeyStoreSsh.openSshPublicKey("remoteclaude-app"))
        fixture.killAllTmux()
        HostKeys.forget(ctx, host, port)
        // Estado limpio: archivo de avisos vacío y cursor reseteado (para que siembre en 0).
        fixture.exec("mkdir -p ~/.config/marvin && : > ~/.config/marvin/notify.jsonl")
        ctx.getSharedPreferences("avisos", Context.MODE_PRIVATE).edit()
            .remove("cursor_ts_$host").commit()
        ctx.getSharedPreferences("remotemarvin", Context.MODE_PRIVATE).edit()
            .remove("tabs_$hostId").remove("active_$hostId").putBoolean("tour_off", true).commit()
        Diagnostico.limpiar()
    }

    @After fun tearDown() {
        runCatching { fixture.exec(": > ~/.config/marvin/notify.jsonl") }
        runCatching { fixture.killAllTmux() }
    }

    @Test fun lineaEscritaDuranteElCorte_seEntregaAlReconectar() {
        ActivityScenario.launch<MainActivity>(intent()).use {
            esperar("el canal conecta") { conectados() >= 1 }
            assertThat(avisosRecibidos()).isEqualTo(0)   // archivo vacío: nada aún

            // Cortar el canal y, con él caído, dejar un aviso en el archivo (el "hueco").
            fixture.exec("e2e-drop-ssh")
            Thread.sleep(1_500)
            appendNotify("hueco-${System.currentTimeMillis()}", ahora())

            // Al reconectar re-lee las 200 y el cursor deja pasar la línea nueva → se entrega.
            esperar("el aviso del hueco se entrega al reconectar", 60_000) { avisosRecibidos() >= 1 }
        }
    }

    @Test fun alReconectar_noReEmiteLoYaVisto() {
        ActivityScenario.launch<MainActivity>(intent()).use {
            esperar("el canal conecta") { conectados() >= 1 }

            appendNotify("uno-${System.currentTimeMillis()}", ahora())
            esperar("se procesa el primer aviso") { avisosRecibidos() >= 1 }
            val tras1 = avisosRecibidos()

            // Forzar una reconexión: al reconectar re-lee las 200 (incluida la de recién). El cursor
            // debe saltearla (ts <= cursor): la cuenta NO tiene que subir por la re-lectura.
            fixture.exec("e2e-drop-ssh")
            esperar("reconecta", 60_000) { conectados() >= 2 }
            Thread.sleep(2_000)
            assertThat(avisosRecibidos()).isEqualTo(tras1)
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun ahora() = System.currentTimeMillis() / 1000

    private fun appendNotify(msg: String, ts: Long) {
        val json = """{"type":"permission_prompt","message":"$msg","ts":$ts}"""
        esperar("escribir el aviso en el fixture") {
            fixture.exec(
                "mkdir -p ~/.config/marvin && printf '%s\\n' ${ShellQuote.sq(json)} " +
                    ">> ~/.config/marvin/notify.jsonl && echo ok",
            ).contains("ok")
        }
    }

    private fun avisosRecibidos(): Int =
        Diagnostico.instantanea().count { it.categoria == "avisos" && it.detalle.startsWith("aviso recibido") }

    private fun conectados(): Int =
        Diagnostico.instantanea().count { it.categoria == "avisos" && it.detalle.startsWith("canal conectado") }

    private fun esperar(que: String, ms: Long = 30_000, cond: () -> Boolean) {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) {
            if (runCatching { cond() }.getOrDefault(false)) return
            Thread.sleep(500)
        }
        throw AssertionError("timeout esperando: $que")
    }
}
