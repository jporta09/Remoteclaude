package com.remoteclaude.app

/**
 * Las decisiones sobre pestañas que no necesitan ni Android ni red.
 *
 * Estaban embebidas en MainActivity, mezcladas con diálogos y llamadas SSH, así que sólo se
 * podían ejercitar con un emulador y un host vivo. Son reglas con casos de borde reales
 * (cerrar una pestaña anterior a la activa corre los índices; un nombre repetido en el host
 * resucitaría una sesión ajena), y acá se prueban en milisegundos.
 */
object TabPlan {

    /**
     * El "term N" libre más bajo. `usados` tiene que incluir las pestañas abiertas Y las
     * sesiones tmux del host: si sólo mirara las abiertas, al reconectar `tmux new -A`
     * engancharía una sesión de trabajo ajena creyendo que abre una nueva.
     */
    fun nextFreeName(usados: Set<String>): String {
        var k = 1
        while ("term $k" in usados) k++
        return "term $k"
    }

    /**
     * Qué pestaña queda activa después de cerrar la de índice [cerrada].
     *
     * La intención es "quedate donde estabas": si cerraste una ANTERIOR a la activa, todos
     * los índices posteriores bajaron uno y hay que compensar, o saltarías de pestaña sin
     * pedirlo. Si cerraste la última de la lista, se cae a la nueva última.
     */
    fun activeAfterClose(cerrada: Int, activa: Int, quedan: Int): Int {
        if (quedan <= 0) return 0
        return if (cerrada < activa) (activa - 1).coerceAtLeast(0) else activa.coerceAtMost(quedan - 1)
    }

    /** Nombres persistidos en prefs -> lista. Tolera basura: líneas vacías o prefs corruptas. */
    fun parseSaved(raw: String?): List<String> =
        raw.orEmpty().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
}
