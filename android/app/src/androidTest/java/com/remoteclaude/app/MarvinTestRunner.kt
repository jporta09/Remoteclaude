package com.remoteclaude.app

import android.os.Build
import androidx.test.runner.AndroidJUnitRunner

/**
 * Runner de los E2E. Concede POST_NOTIFICATIONS a la app ANTES de correr cualquier test.
 *
 * Sin esto, el pedido de permiso de F7 (`MainActivity.pedirPermisoNotificaciones()`) abre en
 * cada arranque el diálogo del sistema, que PAUSA la activity bajo test — y ActivityScenario
 * falla con "Activity never becomes RESUMED". Concedido de antemano, el pedido ve el permiso
 * ya dado y no abre nada; el camino de producción (pedir el permiso) queda intacto.
 */
class MarvinTestRunner : AndroidJUnitRunner() {
    override fun onStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                uiAutomation.grantRuntimePermission(
                    targetContext.packageName, "android.permission.POST_NOTIFICATIONS",
                )
            }
        }
        super.onStart()
    }
}
