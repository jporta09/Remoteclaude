package com.remoteclaude.app

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * La demo de primer uso, sobre la pantalla de hosts (no necesita fixture SSH): con prefs
 * vírgenes aparece, tocar avanza, "Omitir demo" cierra y persiste, y no vuelve a salir.
 */
@RunWith(AndroidJUnit4::class)
class TourE2ETest {

    // Los helpers van por onActivity, que BLOQUEA si el main thread se cuelga (pasó: un
    // layout-loop del overlay dejó la suite entera colgada 1h40 sin fallar). Este timeout
    // corre el test en otro hilo y lo mata: mejor un test rojo que un gate eterno.
    @get:Rule val timeout: Timeout = Timeout(120, TimeUnit.SECONDS)

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = ctx.getSharedPreferences("remotemarvin", Context.MODE_PRIVATE)

    @Before fun setUp() {
        prefs.edit().remove("tour_hosts2").remove("tour_off").commit()
    }

    @After fun tearDown() {
        // Que el resto de la suite no herede una demo pendiente.
        prefs.edit().putBoolean("tour_off", true).commit()
    }

    @Test fun apareceAvanzaYSaltarLaPersisteComoVista() {
        ActivityScenario.launch(HostsActivity::class.java).use { esc ->
            esperar("que la demo aparezca") { overlay(esc) != null }

            var antes = ""
            esc.onActivity { a -> antes = contador(overlay(a)!!) }
            esc.onActivity { a -> overlay(a)!!.performClick() }
            esperar("que tocar avance de paso") {
                var ahora = ""
                esc.onActivity { a -> ahora = overlay(a)?.let { contador(it) } ?: "" }
                ahora.isNotEmpty() && ahora != antes
            }

            esc.onActivity { a -> textoExacto(overlay(a)!!, "Omitir demo")!!.performClick() }
            esperar("que Omitir cierre la demo") { overlay(esc) == null }
            assertThat(prefs.getBoolean("tour_hosts2", false)).isTrue()
        }

        // Vista una vez, no vuelve a aparecer.
        ActivityScenario.launch(HostsActivity::class.java).use { esc ->
            Thread.sleep(800)
            assertThat(overlay(esc)).isNull()
        }
    }

    @Test fun conElKillSwitchNoAparece() {
        prefs.edit().putBoolean("tour_off", true).commit()
        ActivityScenario.launch(HostsActivity::class.java).use { esc ->
            Thread.sleep(800)
            assertThat(overlay(esc)).isNull()
        }
    }

    // --- helpers -----------------------------------------------------------------------

    private fun overlay(esc: ActivityScenario<HostsActivity>): TourOverlay? {
        var v: TourOverlay? = null
        esc.onActivity { a -> v = overlay(a) }
        return v
    }

    private fun overlay(a: HostsActivity): TourOverlay? =
        buscar(a.window.decorView) { it is TourOverlay } as TourOverlay?

    private fun contador(o: TourOverlay): String =
        (buscar(o) { it is TextView && it.text.toString().contains("toque para continuar") } as TextView)
            .text.toString()

    private fun textoExacto(o: TourOverlay, texto: String): View? =
        buscar(o) { it is TextView && it.text.toString() == texto }

    private fun buscar(v: View, cond: (View) -> Boolean): View? {
        if (cond(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) {
            buscar(v.getChildAt(i), cond)?.let { return it }
        }
        return null
    }

    private fun esperar(que: String, ms: Long = 10_000, cond: () -> Boolean) {
        val fin = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < fin) {
            if (cond()) return
            Thread.sleep(150)
        }
        throw AssertionError("timeout esperando: $que")
    }
}
