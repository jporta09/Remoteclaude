package com.remoteclaude.app

/**
 * Comillado de argumentos para el shell remoto.
 *
 * Los comandos que la app manda por SSH se arman como strings y los ejecuta el shell de
 * login del host: interpolar un nombre a mano (`'$name'`) alcanza para el caso feliz pero
 * NO para uno que traiga una comilla — ahí se cierra el literal y el resto se ejecuta.
 * Los nombres no son de confianza: vienen de prefs restauradas, de la salida de `tmux ls`
 * y de listados de archivos del host.
 */
object ShellQuote {

    /**
     * Devuelve [s] listo para pasar como UN argumento a un shell POSIX.
     *
     * Envuelve en comillas simples (que no interpretan nada) y escapa las comillas simples
     * internas con el idioma clásico `'\''` = cerrar, comilla literal, reabrir. Los saltos
     * de línea quedan comillados correctamente (son legales dentro de comillas simples);
     * si un caso de uso no los tolera, es su validador el que debe rechazarlos.
     *
     * NUL no se puede representar en un argumento de proceso, así que se rechaza.
     */
    fun sq(s: String): String {
        require(s.none { it.code == 0 }) { "un argumento del shell no puede contener NUL" }
        return "'" + s.replace("'", "'\\''") + "'"
    }
}
