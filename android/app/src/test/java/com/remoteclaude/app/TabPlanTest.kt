package com.remoteclaude.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TabPlanTest {

    @Test fun `sin nada usado arranca en term 1`() {
        assertThat(TabPlan.nextFreeName(emptySet())).isEqualTo("term 1")
    }

    @Test fun `toma el hueco mas bajo, no el siguiente al ultimo`() {
        // Si devolviera "term 4", abrir y cerrar pestañas iría dejando nombres cada vez más
        // altos aunque el usuario tenga una sola abierta.
        assertThat(TabPlan.nextFreeName(setOf("term 1", "term 3"))).isEqualTo("term 2")
    }

    @Test fun `cuenta tambien las sesiones detacheadas del host`() {
        // El caso que importa: "term 1" no está abierta acá, pero existe en el host. Elegirla
        // haría que `tmux new -A` enganchara una sesión de trabajo ajena creyendo que crea una.
        assertThat(TabPlan.nextFreeName(setOf("term 1", "term 2"))).isEqualTo("term 3")
    }

    @Test fun `los nombres propios no interfieren con la numeracion`() {
        assertThat(TabPlan.nextFreeName(setOf("deploy", "logs"))).isEqualTo("term 1")
    }

    @Test fun `el nombre generado sobrevive al saneado`() {
        // Punto fijo: si TmuxName tocara "term N", al reconectar la app buscaría un nombre
        // distinto del que creó y el usuario perdería sus pestañas.
        val n = TabPlan.nextFreeName(emptySet())
        assertThat(TmuxName.sanitize(n)).isEqualTo(n)
    }

    @Test fun `cerrar una anterior a la activa mantiene la misma pestaña`() {
        // Estabas en la 2 (índice 2) y cerrás la 0: tu pestaña ahora es la 1.
        assertThat(TabPlan.activeAfterClose(cerrada = 0, activa = 2, quedan = 3)).isEqualTo(1)
    }

    @Test fun `cerrar una posterior no mueve la activa`() {
        assertThat(TabPlan.activeAfterClose(cerrada = 2, activa = 1, quedan = 3)).isEqualTo(1)
    }

    @Test fun `cerrar la ultima cae a la nueva ultima`() {
        assertThat(TabPlan.activeAfterClose(cerrada = 2, activa = 2, quedan = 2)).isEqualTo(1)
    }

    @Test fun `cerrar la unica no devuelve un indice invalido`() {
        assertThat(TabPlan.activeAfterClose(cerrada = 0, activa = 0, quedan = 0)).isEqualTo(0)
    }

    @Test fun `las prefs vacias o corruptas no generan pestañas fantasma`() {
        assertThat(TabPlan.parseSaved(null)).isEmpty()
        assertThat(TabPlan.parseSaved("")).isEmpty()
        assertThat(TabPlan.parseSaved("\n\n  \n")).isEmpty()
    }

    @Test fun `respeta el orden guardado`() {
        assertThat(TabPlan.parseSaved("term 2\nlogs\nterm 1"))
            .containsExactly("term 2", "logs", "term 1").inOrder()
    }
}
