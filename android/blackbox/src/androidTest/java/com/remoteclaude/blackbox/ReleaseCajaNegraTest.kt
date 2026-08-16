package com.remoteclaude.blackbox

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Valida el APK de release **tal como se publica**, manejándolo desde afuera.
 *
 * Qué agrega sobre `make e2e-release`: esa suite corre DENTRO del proceso de la app, así
 * que R8 tiene que dejarle costuras (`proguard-rules-e2e.pro`) que el APK publicado no
 * lleva. Acá no se referencia una sola clase de la app: se la maneja por la interfaz, con
 * el host como oráculo. El APK bajo prueba tiene el DEX byte a byte idéntico al publicado
 * —lo comprueba `scripts/e2e-blackbox.sh` comparando sha256 contra un build limpio—, así
 * que lo que pase acá le pasa al usuario.
 *
 * Lo que se puede afirmar y lo que no: se prueba que la app arranca, que habla SSH de
 * verdad (trilead, que R8 podría renombrar) y que el binding JNI de gomobile sigue en pie.
 * No se prueba lo que la terminal DIBUJA: sin acceso al proceso no hay `screenText()`, y
 * el texto del canvas tampoco lo expone accesibilidad todavía. El oráculo es el host.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseCajaNegraTest {

    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val host get() = args.getString("fixtureHost") ?: "10.0.2.2"
    private val port get() = (args.getString("fixturePort") ?: "2222").toInt()

    private lateinit var device: UiDevice
    private lateinit var fixture: Fixture

    private val APP = "com.remoteclaude.app"

    @Before fun setUp() {
        device = UiDevice.getInstance(inst)
        fixture = Fixture(host, port)
        fixture.matarTmux()
        // Que la app NO esté autorizada es parte del guion: así aparece el diálogo que
        // muestra la clave pública, que es la única vía por la que un test de caja negra
        // puede enterarse de cuál es.
        fixture.desautorizar()
        // NO se limpia el estado de la app: ni `pm clear` ni desinstalar. Las dos cosas
        // borran la clave del Keystore, que es exactamente lo que no debe perderse (y en
        // el teléfono del usuario significaría tener que reautorizar el dispositivo). Los
        // tests se escriben para tolerar estado previo: por eso `darDeAltaElHost` es
        // idempotente y el diálogo de autorización se fuerza desautorizando del lado del
        // fixture, no reseteando la app.
    }

    @After fun tearDown() {
        runCatching { fixture.matarTmux() }
        shell("am force-stop $APP")
    }

    private fun shell(cmd: String): String =
        inst.uiAutomation.executeShellCommand(cmd).use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).use { String(it.readBytes()) }
        }

    private fun abrirApp() {
        val ctx = inst.context
        val intent = ctx.packageManager.getLaunchIntentForPackage(APP)
            ?: throw AssertionError(
                "com.remoteclaude.app no está instalada (o el manifiesto del test no la declara en <queries>)"
            )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ctx.startActivity(intent)
        assertThat(device.wait(Until.hasObject(By.pkg(APP).depth(0)), 30_000)).isTrue()
    }

    /**
     * Qué hay en pantalla ahora mismo. Va en el mensaje de cada timeout: "no apareció X" no
     * dice si la app está en otra pantalla, si quedó en el splash o si ni siquiera arrancó,
     * y sin eso una corrida intermitente no se puede diagnosticar después.
     */
    private fun pantalla(): String {
        val textos = device.findObjects(By.clazz("android.widget.TextView"))
            .mapNotNull { runCatching { it.text }.getOrNull() }
            .filter { it.isNotBlank() }
            .take(12)
        val foco = runCatching { device.currentPackageName }.getOrNull()
        return "paquete=$foco textos=${textos.joinToString(" | ") { it.take(40) }}"
    }

    private fun esperar(texto: String, ms: Long = 30_000): UiObject2 =
        device.wait(Until.findObject(By.textContains(texto)), ms)
            ?: throw AssertionError("no apareció en pantalla: \"$texto\" · ${pantalla()}")

    /**
     * Busca y toca, reintentando. UI Automator entrega una referencia a un nodo de
     * accesibilidad que se invalida si la pantalla cambia entremedio: buscar y tocar en dos
     * pasos tira `StaleObjectException` de forma intermitente, que es la peor manera de
     * fallar — parece un bug de la app y es del test.
     */
    private fun tocar(texto: String, ms: Long = 15_000) {
        val fin = System.currentTimeMillis() + ms
        var ultimo: String? = null
        while (System.currentTimeMillis() < fin) {
            val obj = device.wait(Until.findObject(By.textContains(texto)), 2_000)
            if (obj != null) {
                try { obj.click(); return } catch (e: StaleObjectException) { ultimo = e.toString() }
            }
            device.waitForIdle(1_000)
        }
        throw AssertionError(
            "no pude tocar \"$texto\"" + (ultimo?.let { " (último intento: $it)" } ?: "") +
                " · " + pantalla()
        )
    }

    /**
     * Carga el host del fixture por la interfaz, como lo haría una persona. Idempotente
     * porque los tests NO limpian el estado de la app (ver setUp): si la tarjeta ya está,
     * volver a crearla dejaría dos hosts iguales y el `tocar("CajaNegra")` sería ambiguo.
     */
    private fun darDeAltaElHost() {
        if (device.hasObject(By.textContains("CajaNegra"))) return
        tocar("Agregar host")
        esperar("Nuevo host")
        val campos = device.findObjects(By.clazz("android.widget.EditText"))
        check(campos.size >= 4) { "esperaba 4 campos en el diálogo de host, hay ${campos.size}" }
        campos[0].text = "CajaNegra"
        campos[1].text = host
        campos[2].text = port.toString()
        campos[3].text = Fixture.USER
        tocar("Guardar")
    }

    /**
     * Lee de la pantalla la clave pública que la app propone autorizar.
     *
     * El formato NO es `ssh-...`: el Keystore de Android genera una clave EC, así que la
     * línea empieza con `ecdsa-sha2-nistp256`. Suponerlo al revés hizo fallar los dos
     * tests con "el diálogo no mostró ninguna clave", que apuntaba al lugar equivocado.
     * Por eso el reconocimiento va por la forma de una línea de `authorized_keys` y no por
     * un prefijo concreto.
     */
    private fun claveDelDialogo(): String {
        esperar("Falta autorizar este dispositivo", 45_000)
        val formato = Regex("""(?:ssh|ecdsa|sk)-\S+\s+[A-Za-z0-9+/=]{40,}(?:\s+\S+)?""")
        val texto = device.findObjects(By.clazz("android.widget.TextView"))
            .mapNotNull { it.text }
            .firstNotNullOfOrNull { formato.find(it)?.value }
            ?: throw AssertionError("el diálogo no mostró ninguna clave pública reconocible")
        return texto.trim()
    }

    @Test fun elReleasePublicado_conectaPorSshYCreaLaSesionEnElHost() {
        abrirApp()
        darDeAltaElHost()
        tocar("CajaNegra")

        // La app rechaza: su clave no está en authorized_keys. El diálogo trae la clave, y
        // leerla de la pantalla es la única forma que tiene un test de afuera de saberla.
        val clave = claveDelDialogo()
        assertThat(clave).contains("nistp256")

        fixture.autorizar(clave)
        tocar("Reintentar")

        // El oráculo es el host: si la sesión existe, el APK publicado abrió un SSH real y
        // corrió tmux del otro lado. Eso ejercita trilead entero bajo R8.
        esperarQue("la sesión tmux aparezca en el host", 60_000) {
            fixture.sesionesTmux().contains("term 1")
        }
    }

    @Test fun elReleasePublicado_escribeEnLaSesionYElHostLoRecibe() {
        abrirApp()
        darDeAltaElHost()
        tocar("CajaNegra")
        fixture.autorizar(claveDelDialogo())
        tocar("Reintentar")
        esperarQue("la sesión tmux", 60_000) { fixture.sesionesTmux().contains("term 1") }

        // Tipear "de verdad": `input text` va a la vista con foco, que es la terminal. Es
        // lo más parecido a un dedo que se puede hacer sin tocar el proceso de la app.
        val marca = "cajanegra-${System.currentTimeMillis()}"
        // `%s` y no un espacio: executeShellCommand NO pasa por un shell (parte el string
        // por espacios y ejecuta), así que comillas y barras invertidas no significan nada
        // y el comando llegaba partido. `input text` interpreta %s como espacio.
        shell("input text echo%s$marca")
        shell("input keyevent 66")   // ENTER

        esperarQue("que el comando llegue al host", 30_000) {
            fixture.panel("term 1").contains(marca)
        }
    }

    @Test fun elBindingJniDeGomobileSigueEnPie() {
        // El canario de R8 más barato que existe para este proyecto: `libgojni.so` busca
        // `marvints.*` POR NOMBRE, así que si una regla keep se cae, la app compila igual y
        // explota recién en runtime. Con una auth key falsa el puente igual se invoca —
        // TailscaleBridge.init() sólo se saltea si NO hay clave— y la app reporta el error
        // en pantalla.
        //
        // La aserción es sobre la CLASE de error: uno de Tailscale significa que el binding
        // está sano (se llegó a ejecutar código Go); uno de resolución de clases significa
        // que R8 se llevó puesto el binding.
        abrirApp()
        tocar("VPN: directa")
        val campo = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000)
            ?: throw AssertionError("no apareció el campo de la auth key")
        campo.text = "tskey-auth-invalida-a-proposito"
        tocar("Guardar")

        // Hay que esperar a que el estado SE RESUELVA: "conectando…" es el intermedio y
        // afirmar sobre él no probaría nada (no contiene ningún error, ni el bueno ni el
        // malo). Se espera al desenlace.
        var estado = ""
        esperarQue("que el estado de Tailscale se resuelva", 90_000) {
            estado = device.findObjects(By.textContains("Tailscale:"))
                .mapNotNull { it.text }.firstOrNull().orEmpty()
            estado.contains("error") || estado.contains("conectada")
        }

        // Un binding roto se ve como un fallo de resolución de clases o de enlace nativo;
        // cualquier otro error (auth key inválida, sin red) significa que se llegó a
        // ejecutar código Go, que es lo que se quería comprobar.
        for (sintoma in listOf("NoClassDefFoundError", "ClassNotFoundException",
                               "UnsatisfiedLinkError", "NoSuchMethodError")) {
            assertThat(estado).doesNotContain(sintoma)
        }
        // Y que la app siga viva: un binding roto la tira abajo en el hilo "tailscale-up".
        assertThat(device.hasObject(By.pkg(APP).depth(0))).isTrue()

        // Dejar la clave vacía: si quedara puesta, cada arranque siguiente gastaría un
        // intento de conexión a Tailscale. No afecta a los otros tests (sin nodo levantado
        // `endpoint()` cae a la conexión directa), pero los hace más lentos.
        runCatching {
            tocar("Tailscale:")
            device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000)?.text = ""
            tocar("Guardar")
        }
    }

    private fun esperarQue(que: String, ms: Long, cond: () -> Boolean) {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) {
            if (runCatching { cond() }.getOrDefault(false)) return
            Thread.sleep(500)
        }
        throw AssertionError("timeout esperando: $que")
    }
}
