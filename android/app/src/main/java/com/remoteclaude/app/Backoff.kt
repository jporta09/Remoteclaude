package com.remoteclaude.app

/**
 * Esperas de reconexión, puras para testearlas en JVM (5ª pasada, SRE-5-3).
 *
 * Con backoff sin jitter, N pestañas (o N clientes) que caen juntas vuelven a golpear el host
 * exactamente al mismo tiempo, y bajo el nodo vencido el canal de avisos registraba un reintento
 * cada 30 s durante horas. El jitter "full" (50-100 % del intervalo) reparte los reintentos; el
 * tope crece con los fallos seguidos para no martillar un host que no vuelve.
 */

/** Espera antes del reintento `attempt` (1, 2, 3…) de la terminal: lineal con tope 8 s, con jitter. */
fun esperaRetry(attempt: Int, rnd: Double): Long {
    val base = minOf(1000L * attempt.coerceAtLeast(1), 8000L)
    return conJitter(base, rnd)
}

/**
 * Próximo backoff del canal de avisos: exponencial desde 2 s, tope 30 s hasta 10 fallos seguidos y
 * 5 min después (un host apagado toda la noche no merece un reintento cada 30 s). `rnd` en [0,1).
 */
fun proximoBackoff(actualMs: Long, fallosSeguidos: Int, rnd: Double): Long {
    val tope = if (fallosSeguidos >= 10) 300_000L else 30_000L
    val base = (actualMs * 2).coerceIn(2_000L, tope)
    return conJitter(base, rnd)
}

/** Full jitter acotado: entre el 50 % y el 100 % de `base`. */
internal fun conJitter(base: Long, rnd: Double): Long {
    val r = rnd.coerceIn(0.0, 0.999999)
    return (base * (0.5 + 0.5 * r)).toLong().coerceAtLeast(1L)
}
