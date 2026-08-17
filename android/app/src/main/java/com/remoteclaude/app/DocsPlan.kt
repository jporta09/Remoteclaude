package com.remoteclaude.app

/**
 * La parte pura de la pantalla de Documentos: criterio de orden y normalización de
 * nombres de subida. Separada de la activity para poder testearse en JVM, como
 * [TabPlan] y [Tour].
 */
object DocsPlan {

    enum class Criterio(val etiqueta: String) {
        NOMBRE("nombre"),
        TAMANO("tamaño"),
        TIPO("tipo"),
        CREACION("creación"),
        MODIFICACION("modificación"),
    }

    /**
     * Ordena según el criterio, ascendente o descendente. Desempates por nombre para
     * que el orden sea estable a la vista. Con CREACION, los docs sin btime (el
     * filesystem no lo registra) van siempre al final, en cualquier dirección: no
     * tienen dato, no compiten.
     */
    fun ordenar(docs: List<RemoteControl.Doc>, criterio: Criterio, asc: Boolean): List<RemoteControl.Doc> {
        val base: Comparator<RemoteControl.Doc> = when (criterio) {
            Criterio.NOMBRE -> compareBy { it.name.lowercase() }
            Criterio.TAMANO -> compareBy({ it.size }, { it.name.lowercase() })
            Criterio.TIPO -> compareBy({ DocKind.of(it.name).ordinal }, { it.name.lowercase() })
            Criterio.CREACION -> compareBy({ it.btime }, { it.name.lowercase() })
            Criterio.MODIFICACION -> compareBy({ it.mtime }, { it.name.lowercase() })
        }
        val ordenados = docs.sortedWith(if (asc) base else base.reversed())
        if (criterio != Criterio.CREACION) return ordenados
        val (con, sin) = ordenados.partition { it.btime > 0L }
        return con + sin
    }

    /** Si ningún doc trae btime, el criterio "creación" no tiene con qué ordenar. */
    fun hayBtime(docs: List<RemoteControl.Doc>): Boolean = docs.any { it.btime > 0L }

    /**
     * Nombre seguro para subir al host, con la misma regla que marvin-show.sh:
     * espacios a '_', solo [A-Za-z0-9._-], tope 120. Null si no queda nada usable.
     */
    fun normalizarNombre(display: String?): String? {
        val base = display?.substringAfterLast('/')?.trim() ?: return null
        val limpio = base.replace(' ', '_').filter { it.isLetterOrDigit() && it.code < 128 || it in "._-" }
        val recortado = limpio.take(120).trimStart('.')
        return recortado.ifBlank { null }
    }
}
