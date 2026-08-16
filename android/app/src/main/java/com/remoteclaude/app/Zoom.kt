package com.remoteclaude.app

/**
 * Tamaño de fuente de la terminal ante un pellizco.
 *
 * Vive aparte y en float por un bug concreto: antes esto era
 * `(fuentePx * scale).toInt()`, y `toInt()` trunca SIEMPRE hacia abajo. Como el detector
 * de gestos entrega muchos eventos chiquitos y el acumulador se reinicia en cada uno, el
 * resultado era asimétrico: achicar funcionaba con cualquier roce (15 × 0,97 = 14,55 → 14)
 * y agrandar no pasaba nunca (15 × 1,03 = 15,45 → 15). Para subir un píxel hacía falta un
 * gesto de más del 6% en un solo evento, y a fuente chica todavía más. En la práctica la
 * letra se achicaba sola y después no había forma de recuperarla.
 *
 * El arreglo es guardar el tamaño en float —así los incrementos chicos se acumulan— y
 * redondear sólo al dibujar.
 */
object Zoom {

    /** Nuevo tamaño acumulado, en píxeles y con decimales. */
    fun escalar(actual: Float, scale: Float, min: Float, max: Float): Float =
        (actual * scale).coerceIn(min, max)

    /** Lo que se le pasa a la vista: píxeles enteros, redondeando en vez de truncar. */
    fun aPixeles(tamano: Float): Int = Math.round(tamano)
}
