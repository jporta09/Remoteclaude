package com.remoteclaude.app

/**
 * Saneo de nombres de sesión tmux.
 *
 * Restricciones reales de tmux: el punto y los dos puntos son separadores de
 * target (`sesión:ventana.panel`), así que un nombre que los contenga es ambiguo.
 * El resto lo imponemos nosotros para que el nombre sobreviva al ida y vuelta por
 * `tmux ls` (que devuelve una línea por sesión) y por los parseos tabulados.
 *
 * INVARIANTE CRÍTICA: los nombres que la app ya genera (`term 1`, `term 2`, …) tienen
 * que ser PUNTO FIJO. Si `sanitize` los cambiara, al actualizar la app el usuario
 * dejaría de reengancharse a sus sesiones vivas y abriría pestañas nuevas vacías.
 */
object TmuxName {

    const val MAX_LEN = 40
    private const val FALLBACK = "term"

    /**
     * Devuelve un nombre de sesión seguro derivado de [raw]:
     * saca controles (incluido salto de línea y tabulador), reemplaza `.` y `:`,
     * colapsa espacios, recorta y acota el largo. Si no queda nada, [FALLBACK].
     */
    fun sanitize(raw: String): String {
        val cleaned = buildString(raw.length) {
            for (ch in raw) {
                when {
                    ch.isISOControl() -> append(' ')      // \n \r \t y demás -> espacio
                    ch == '.' || ch == ':' -> append('-')  // separadores de target de tmux
                    else -> append(ch)
                }
            }
        }
        val collapsed = cleaned.replace(SPACES, " ").trim()
        if (collapsed.isEmpty()) return FALLBACK
        return collapsed.take(MAX_LEN).trim()
    }

    private val SPACES = Regex("\\s+")
}
