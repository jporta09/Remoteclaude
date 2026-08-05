package com.remoteclaude.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Test
import java.io.File

/**
 * Utilidades del harness E2E — no son asserts de producto, son los ganchos que necesita
 * `scripts/e2e.sh` para preparar el emulador.
 *
 * Corren DENTRO del proceso de la app, así que pueden usar el Keystore y las prefs
 * directamente: por eso no hace falta ni un flavor de debug ni leer nada por la UI.
 */
class HarnessSetupTest {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Deja la clave pública SSH de la app en un archivo que el runner pueda `adb pull`,
     * para autorizarla en el host de pruebas.
     */
    @Test
    fun dumpPublicKey() {
        val pub = KeyStoreSsh.openSshPublicKey("remoteclaude-e2e")
        val out = File(ctx.getExternalFilesDir(null), "pubkey.txt")
        out.writeText(pub)
        println("MARVIN_PUBKEY=$pub")
    }

    /**
     * Siembra un host en las prefs (lo que haría el usuario por la UI de alta) para que
     * los tests arranquen ya conectables. Se parametriza por argumentos del runner:
     *   -Pandroid.testInstrumentationRunnerArguments.fixtureHost=10.0.2.2
     */
    @Test
    fun seedHost() {
        val args = InstrumentationRegistry.getArguments()
        val host = Host(
            id = "e2e",
            label = args.getString("fixtureLabel") ?: "E2E",
            hostname = args.getString("fixtureHost") ?: "10.0.2.2",
            port = (args.getString("fixturePort") ?: "22").toInt(),
            user = args.getString("fixtureUser") ?: "tester",
        )
        val arr = JSONArray().put(host.toJson())
        ctx.getSharedPreferences("remotemarvin", Context.MODE_PRIVATE)
            .edit().putString("hosts", arr.toString()).commit()
        println("MARVIN_SEEDED=${host.hostname}:${host.port} ${host.user}")
    }
}
