package com.remoteclaude.app

/**
 * Estado compartido entre la Activity y el [AvisosService]. El service corre en otro componente
 * (no en la Activity), así que necesita saber si la app está adelante para NO postear la notif
 * "Claude te espera" cuando ya la estás mirando. La Activity lo actualiza en onResume/onPause.
 */
object EstadoApp {
    @Volatile var enPrimerPlano = false
}
