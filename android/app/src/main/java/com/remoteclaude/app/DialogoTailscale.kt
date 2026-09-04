package com.remoteclaude.app

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText

/**
 * Diálogo del Tailscale embebido (pegar auth key / escanear QR), compartido por la pantalla de
 * hosts y por el ↺ de la terminal (UX5-3: antes desde la terminal sólo se podía abrir la cámara, y
 * "vencido lejos de la PC" no tenía camino). Sabe si el enrolamiento está vencido y lo dice.
 */
object DialogoTailscale {
    fun mostrar(act: Activity, onKey: (String) -> Unit, onScan: () -> Unit) {
        val configurada = SecretStore.get(act, "ts_authkey").isNotBlank()
        val vencido = TailscaleBridge.accesoVencido()
        val tailnet = TailscaleBridge.tailnet()
        val p = (16 * act.resources.displayMetrics.density).toInt()
        val input = EditText(act).apply {
            // Sin restos de la key en pantalla (antes: los últimos 6 chars, UX5-9).
            hint = when {
                !configurada -> "tskey-auth-…"
                tailnet.isNotBlank() -> "vinculada a $tailnet · pegá otra para reemplazar"
                else -> "configurada · pegá otra para reemplazar"
            }
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(p, p * 3 / 4, p, p * 3 / 4)
        }
        val mensaje = if (vencido) {
            "El enrolamiento del celu venció (o fue revocado): los hosts por tailnet no conectan hasta " +
                "re-enrolar; los de LAN siguen. Generá una key nueva en la PC (./scripts/ts-link-qr.sh " +
                "--png y escaneá el QR) o, si no estás en la PC, en la consola de Tailscale " +
                "(Settings → Keys → auth key con el tag) y pegala acá."
        } else {
            "La app levanta su propio nodo Tailscale (no necesitás la app de Tailscale aparte). Lo " +
                "más fácil: en la PC corré  ./scripts/ts-link-qr.sh  y escaneá el QR. O pegá una auth " +
                "key a mano." + if (configurada) " Dejar vacío no cambia nada." else ""
        }
        AlertDialog.Builder(act)
            .setTitle(if (vencido) "Tailscale: enrolamiento vencido" else "Tailscale embebido")
            .setMessage(mensaje)
            .setView(input)
            .setPositiveButton("Guardar y conectar") { _, _ ->
                val typed = input.text.toString().trim()
                // vacío = no tocar la que ya está (antes la borraba sin querer)
                if (typed.isNotEmpty() || !configurada) onKey(typed)
            }
            .setNeutralButton("Escanear QR") { _, _ -> onScan() }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
