package com.remoteclaude.app

import android.content.Intent
import android.view.KeyEvent
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
            .remove("tabs_e2e-reconx").remove("active_e2e-reconx")
            .putBoolean("tour_off", true)
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
     * WS-F (SPIKE): ¿`screenText()` (emulator.screen.transcriptText) captura el prompt de
     * aprobación de Claude Code cuando corre en PANTALLA ALTERNADA (lo que hace
     * CLAUDE_CODE_NO_FLICKER=1)? El riesgo era que transcriptText leyera sólo el buffer
     * principal. Se simula: entrar a alt-screen (ESC[?1049h), pintar un prompt y, mientras
     * sigue ahí (sleep), leer el buffer. Si aparece, la hoja estructurada de WS-F es viable.
     */
    @Test fun spikeWSF_transcriptTextCapturaElPromptEnPantallaAlternada() {
        ActivityScenario.launch<MainActivity>(intentFor(fixturePort)).use { scenario ->
            await(what = "sesión tmux creada") { fixture.tmuxSessions().contains("term 1") }
            scenario.onActivity { a ->
                a.screenText()   // fuerza init del emulador
                // ESC[?1049h = pantalla alternada; ESC[2J limpia; ESC[H cursor arriba; luego el
                // prompt. El sleep mantiene el alt-screen activo mientras leemos.
                val cmd = ("printf '\\033[?1049h\\033[2J\\033[HDo you want to proceed?" +
                    "\\r\\n 1. Yes\\r\\n 2. No\\r\\n'; sleep 8\n").toByteArray()
                a.currentSessionForTest()?.write(cmd, 0, cmd.size)
            }
            var visto = ""
            await(what = "el prompt del alt-screen aparece en screenText()") {
                scenario.onActivity { a -> visto = a.screenText() }
                visto.contains("Do you want to proceed?")
            }
            assertThat(visto).contains("1. Yes")
        }
    }

    /**
     * Los modificadores pegajosos del keypad se propagan a las teclas ESPECIALES: con Ctrl
     * activo, tocar End manda al host la secuencia modificada `ESC[1;5F`, no un End pelado.
     * Antes `enviarTecla` hardcodeaba keyMod=0 y el host nunca veía el Ctrl (por eso Ctrl+End
     * "no hacía nada" en Claude). El host lee 6 bytes crudos y los compara en hex.
     */
    @Test fun ctrlMasTeclaEspecial_llegaAlHostConElModificador() {
        fixture.exec("rm -f /tmp/marvin_seq")
        ActivityScenario.launch<MainActivity>(intentFor(fixturePort)).use { scenario ->
            await(what = "sesión tmux creada") { fixture.tmuxSessions().contains("term 1") }
            scenario.onActivity { a ->
                a.screenText()
                val cmd = ("IFS= read -rsn6 s; printf %s \"\$s\" | od -An -tx1 | tr -d ' \\n' " +
                    "> /tmp/marvin_seq\n").toByteArray()
                a.currentSessionForTest()?.write(cmd, 0, cmd.size)
            }
            Thread.sleep(2000)   // que el `read` del host ya esté esperando la tecla
            scenario.onActivity { a ->
                a.activarCtrlKeypadForTest()
                a.enviarTeclaEspecialForTest(KeyEvent.KEYCODE_MOVE_END)   // Ctrl+End
            }
            // ESC [ 1 ; 5 F  = 1b 5b 31 3b 35 46
            await(what = "el host recibió Ctrl+End como ESC[1;5F") {
                fixture.exec("cat /tmp/marvin_seq 2>/dev/null").trim() == "1b5b313b3546"
            }
        }
    }

    /**
     * F5: "[reconectando…]" se anuncia UNA sola vez por episodio, no en cada intento del backoff
     * (con la PC apagada llenaba la terminal). Se apunta a un puerto MUERTO para que la conexión
     * falle y reintente varias veces, y se cuenta cuántas veces aparece el aviso.
     */
    @Test fun reconectando_seAnunciaUnaSolaVez_noEnCadaIntento() {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            putExtra("hostname", fixtureHost)
            putExtra("port", 1)                 // puerto muerto: connect falla y reintenta
            putExtra("user", FixtureSsh.USER)
            putExtra("hostId", "e2e-reconx")
            putExtra("label", "E2E")
        }
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            await(what = "aparece [reconectando…]") {
                var t = ""; scenario.onActivity { a -> t = a.screenText() }
                t.contains("reconectando")
            }
            // dar tiempo a varios intentos más (backoff 1s,2s,3s…): sin el fix se repetiría.
            Thread.sleep(8000)
            var visto = ""
            scenario.onActivity { a -> visto = a.screenText() }
            val veces = visto.split("reconectando").size - 1
            assertThat(veces).isEqualTo(1)
        }
    }

    /**
     * WS-F: la hoja de aprobación aparece al detectar el prompt de Claude en el buffer, y el
     * botón inyecta la elección al host. Se simula un prompt en pantalla alternada que lee UNA
     * tecla y la guarda; se toca la opción 2 y se verifica que el host recibió el "2".
     */
    @Test fun laHojaDeAprobacion_apareceEInyectaLaEleccionAlHost() {
        fixture.exec("rm -f /tmp/marvin_pick")
        ActivityScenario.launch<MainActivity>(intentFor(fixturePort)).use { scenario ->
            await(what = "sesión tmux creada") { fixture.tmuxSessions().contains("term 1") }
            scenario.onActivity { a ->
                a.screenText()
                // prompt tipo Claude en alt-screen; read -rsn1 toma una tecla y la guarda.
                val cmd = ("printf '\\033[?1049h\\033[2J\\033[HDo you want to proceed?" +
                    "\\r\\n 1. Yes\\r\\n 2. No\\r\\n'; read -rsn1 k; printf '\\033[?1049l'; " +
                    "echo \"\$k\" > /tmp/marvin_pick\n").toByteArray()
                a.currentSessionForTest()?.write(cmd, 0, cmd.size)
            }
            // diagnóstico: si la hoja no aparece, mostrar qué había en el buffer
            val fin = System.currentTimeMillis() + 30_000
            var aparecio = false
            var ultimo = ""
            while (System.currentTimeMillis() < fin && !aparecio) {
                scenario.onActivity { a ->
                    aparecio = a.aprobacionVisibleForTest()
                    ultimo = a.screenText()
                }
                if (!aparecio) Thread.sleep(250)
            }
            if (!aparecio) throw AssertionError("la hoja no apareció. screenText=<<<$ultimo>>>")
            scenario.onActivity { a -> a.aprobacionElegirForTest(2) }   // tocar "2. No"
            await(what = "el host recibió la elección '2'") {
                fixture.exec("cat /tmp/marvin_pick 2>/dev/null").trim() == "2"
            }
        }
    }

    /**
     * F7: si Claude queda esperando aprobación con la app en SEGUNDO PLANO, en vez de la hoja
     * (que no se puede mostrar con la ventana parada) se postea una notificación. Al volver a
     * primer plano el aviso se baja y la hoja aparece. Así no se aprueba tarde ni se lo pierde.
     */
    @Test fun promptEnSegundoPlano_avisaPorNotificacion_yLaHojaApareceAlVolver() {
        // POST_NOTIFICATIONS (Android 13+) ya viene concedido por MarvinTestRunner, así que el
        // aviso puede postearse (y el diálogo de permiso no pausa la activity).
        ActivityScenario.launch<MainActivity>(intentFor(fixturePort)).use { scenario ->
            await(what = "sesión tmux creada") { fixture.tmuxSessions().contains("term 1") }
            // La app se va a segundo plano ANTES de que aparezca el prompt.
            scenario.onActivity { a -> a.simularSegundoPlanoParaTest(true) }
            scenario.onActivity { a ->
                val cmd = ("printf '\\033[?1049h\\033[2J\\033[HDo you want to proceed?" +
                    "\\r\\n 1. Yes\\r\\n 2. No\\r\\n'; read -rsn1 k; printf '\\033[?1049l'; " +
                    "echo \"\$k\" > /tmp/marvin_pick_bg\n").toByteArray()
                a.currentSessionForTest()?.write(cmd, 0, cmd.size)
            }
            // Atrás: se avisa por notificación y la hoja NO se abre.
            await(what = "aviso de aprobación posteado") {
                var v = false; scenario.onActivity { a -> v = a.avisoAprobacionActivoForTest() }; v
            }
            scenario.onActivity { a -> assertThat(a.aprobacionVisibleForTest()).isFalse() }
            // Al volver a primer plano: el aviso se baja y la hoja aparece.
            scenario.onActivity { a -> a.simularSegundoPlanoParaTest(false) }
            await(what = "la hoja aparece al volver") {
                var v = false; scenario.onActivity { a -> v = a.aprobacionVisibleForTest() }; v
            }
            await(what = "el aviso se canceló al volver") {
                var v = true; scenario.onActivity { a -> v = a.avisoAprobacionActivoForTest() }; !v
            }
            scenario.onActivity { a -> a.aprobacionElegirForTest(2) }   // responder para desbloquear el read
        }
    }

    /**
     * WS-A: la barra dice "conectado" (verde, sin sufijo) SOLO cuando la conexión SSH está
     * realmente establecida — no pintada del extra del Intent. Con la clave autorizada, la
     * sesión llega a CONECTADO y la barra no muestra "sin conexión".
     */
    @Test fun laBarraReflejaLaConexionReal_cuandoConecta() {
        ActivityScenario.launch<MainActivity>(intentFor(fixturePort)).use { scenario ->
            await(what = "la sesión activa llega a CONECTADO") {
                var estado: SshTerminalSession.Estado? = null
                scenario.onActivity { a -> estado = a.currentSessionForTest()?.estado }
                estado == SshTerminalSession.Estado.CONECTADO
            }
            var barra = ""
            scenario.onActivity { a -> barra = a.barLabelForTest() }
            assertThat(barra).doesNotContain("sin conexión")
            assertThat(barra).doesNotContain("reconectando")
        }
    }

    /**
     * WS-B: si el server tmux muere (reboot/OOM del host), al reconectar `tmux new -A` crea
     * una sesión NUEVA vacía — antes eso pasaba mudo y parecía que no se perdió nada. Ahora
     * la app avisa. Se simula matando el server tmux con la sesión ya conectada.
     */
    @Test fun avisaCuandoSePierdeLaSesion_alReiniciarseElHost() {
        ActivityScenario.launch<MainActivity>(intentFor(fixturePort)).use { scenario ->
            await(what = "sesión tmux creada") { fixture.tmuxSessions().contains("term 1") }
            await(what = "la sesión activa esté CONECTADA antes de matar el server") {
                var e: SshTerminalSession.Estado? = null
                scenario.onActivity { a -> e = a.currentSessionForTest()?.estado }
                e == SshTerminalSession.Estado.CONECTADO
            }
            fixture.killAllTmux()   // el server muere -> el cliente attached sale -> reconecta
            // Se verifica el CALLBACK, no el texto en la terminal: tmux, al recrear la
            // sesión, limpia la transcripción y borra el aviso del stream (por eso además
            // hay un Toast). El callback es la señal confiable.
            await(what = "el aviso de sesión perdida se disparó", timeoutMs = 60_000) {
                var n = 0
                scenario.onActivity { a -> n = a.sesionPerdidaCountForTest() }
                n > 0
            }
        }
    }

    /**
     * WS-A (el sev-4 transversal): con la clave NO autorizada, la barra NO debe decir
     * "conectado". Antes se pintaba verde "conectado" del extra del Intent aunque la auth
     * fallara. Ahora la sesión llega a CAIDO y la barra dice "sin conexión".
     */
    @Test fun laBarraNoMiente_conAuthRechazada() {
        fixture.exec("rm -f ~/.ssh/authorized_keys")   // desautorizar la clave de la app
        ActivityScenario.launch<MainActivity>(intentFor(fixturePort)).use { scenario ->
            await(what = "la sesión activa llega a CAIDO") {
                var estado: SshTerminalSession.Estado? = null
                scenario.onActivity { a -> estado = a.currentSessionForTest()?.estado }
                estado == SshTerminalSession.Estado.CAIDO
            }
            var barra = ""
            scenario.onActivity { a -> barra = a.barLabelForTest() }
            assertThat(barra).contains("sin conexión")
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
