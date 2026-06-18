package com.remoteclaude.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/** Menú principal: lista de hosts a los que conectarse. Tocar uno abre la terminal;
 *  mantener apretado permite editar/borrar. El "+" agrega uno nuevo. */
class HostsActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var vpnStatus: TextView
    private val titleFont by lazy { resources.getFont(R.font.isocpeur) }     // títulos (marca)
    private val monoFont by lazy { resources.getFont(R.font.mononoki) }      // code / técnico
    private val bodyFont by lazy { resources.getFont(R.font.ubuntu) }        // cuerpo de texto (marca)

    // Scanner de QR (ZXing): el resultado es la auth key de Tailscale (ts-link-qr en la PC).
    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        val key = result.contents
        when {
            key.isNullOrBlank() -> {}   // cancelado
            key.startsWith("tskey-") -> applyTailscaleKey(key.trim())
            else -> Toast.makeText(this, "El QR no es una auth key de Tailscale", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = getColor(R.color.marvin_petrol)
        TailscaleBridge.init(this)   // levanta el Tailscale embebido si hay auth key

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.marvin_petrol))
        }

        // Encabezado: título "RemoteMarvin" (izq) + isotipo chico (arriba a la derecha)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(26), dp(16), dp(2))
        }
        header.addView(TextView(this).apply {
            text = "RemoteMarvin"
            typeface = titleFont
            setTextColor(getColor(R.color.marvin_green))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 27f)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.marvin_iso)
            adjustViewBounds = true
        }, LinearLayout.LayoutParams(WRAP, dp(34)))
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "_hosts"
            typeface = monoFont
            setTextColor(getColor(R.color.marvin_amber))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(20), 0, dp(20), dp(8))
        })
        // Estado de la VPN (Tailscale embebido) — tocar para configurar el auth key
        vpnStatus = TextView(this).apply {
            typeface = monoFont
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(20), 0, dp(20), dp(14))
            setOnClickListener { showVpnDialog() }
        }
        root.addView(vpnStatus)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(MATCH, 0, 1f))

        // Botón agregar
        root.addView(Button(this).apply {
            text = "+  Agregar host"
            isAllCaps = false
            typeface = bodyFont
            setTextColor(getColor(R.color.marvin_petrol))
            setBackgroundColor(getColor(R.color.marvin_green))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setOnClickListener { showEditDialog(null) }
            val lp = LinearLayout.LayoutParams(MATCH, WRAP); lp.setMargins(dp(16), dp(8), dp(16), dp(16))
            layoutParams = lp
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        updateVpnStatus()
    }

    private fun updateVpnStatus() {
        val (txt, color) = when {
            !TailscaleBridge.isEnabled() ->
                "🔒 VPN: directa · tocá para usar Tailscale embebido" to R.color.marvin_muted
            TailscaleBridge.isReady() ->
                "🔒 Tailscale: conectada ✓" to R.color.marvin_green
            TailscaleBridge.error() != null ->
                "🔒 Tailscale: error — ${TailscaleBridge.error()} · tocá" to R.color.marvin_amber
            else -> "🔒 Tailscale: conectando…" to R.color.marvin_amber
        }
        vpnStatus.text = txt
        vpnStatus.setTextColor(getColor(color))
    }

    private fun showVpnDialog() {
        val current = getSharedPreferences("remotemarvin", Context.MODE_PRIVATE)
            .getString("ts_authkey", "").orEmpty()
        val input = EditText(this).apply {
            hint = "tskey-auth-…"
            setText(current)
            setSingleLine()
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Tailscale embebido")
            .setMessage(
                "La app levanta su propio nodo Tailscale (no necesitás la app de Tailscale " +
                    "aparte). Lo más fácil: en la PC corré  docker compose exec gateway " +
                    "ts-link-qr  y escaneá el QR. O pegá una auth key a mano. Vacío = " +
                    "conexión directa."
            )
            .setView(input)
            .setPositiveButton("Guardar y conectar") { _, _ ->
                applyTailscaleKey(input.text.toString())
            }
            .setNeutralButton("Escanear QR") { _, _ ->
                qrScanner.launch(
                    ScanOptions().apply {
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        setPrompt("Apuntá al QR de la PC (ts-link-qr)")
                        setBeepEnabled(false)
                        setOrientationLocked(false)
                    }
                )
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Guarda la auth key, reinicia el nodo embebido y refresca el estado. */
    private fun applyTailscaleKey(key: String) {
        TailscaleBridge.configure(this, key)
        updateVpnStatus()
        vpnStatus.postDelayed({ updateVpnStatus() }, 3000)
        vpnStatus.postDelayed({ updateVpnStatus() }, 9000)
    }

    private fun refresh() {
        list.removeAllViews()
        val hosts = HostStore.load(this)
        if (hosts.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Todavía no hay hosts.\nAgregá tu PC o un server con \"+\"."
                typeface = bodyFont
                setTextColor(getColor(R.color.marvin_muted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(24), dp(24), dp(24), dp(24))
            })
            return
        }
        hosts.forEach { host -> list.addView(card(host)) }
    }

    private fun card(host: Host): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.marvin_surface))
            setPadding(dp(18), dp(16), dp(18), dp(16))
            val lp = LinearLayout.LayoutParams(MATCH, WRAP); lp.setMargins(dp(16), dp(6), dp(16), dp(6))
            layoutParams = lp
            setOnClickListener { connect(host) }
            setOnLongClickListener { hostMenu(host); true }
        }
        card.addView(TextView(this).apply {
            text = host.label
            typeface = bodyFont
            setTextColor(getColor(R.color.marvin_fg))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        })
        card.addView(TextView(this).apply {
            text = "${host.user}@${host.hostname}:${host.port}"
            typeface = monoFont
            setTextColor(getColor(R.color.marvin_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        return card
    }

    private fun connect(host: Host) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra("hostId", host.id)
            putExtra("label", host.label)
            putExtra("hostname", host.hostname)
            putExtra("port", host.port)
            putExtra("user", host.user)
        })
    }

    private fun hostMenu(host: Host) {
        AlertDialog.Builder(this)
            .setTitle(host.label)
            .setItems(arrayOf("Conectar", "Editar", "Borrar")) { _, which ->
                when (which) {
                    0 -> connect(host)
                    1 -> showEditDialog(host)
                    2 -> { HostStore.delete(this, host.id); refresh() }
                }
            }.show()
    }

    private fun showEditDialog(existing: Host?) {
        fun field(hint: String, value: String, number: Boolean = false) = EditText(this).apply {
            this.hint = hint
            setText(value)
            setSingleLine()
            if (number) inputType = InputType.TYPE_CLASS_NUMBER
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val label = field("Nombre (ej: Mi PC)", existing?.label ?: "")
        val hostname = field("Host / IP (ej: remoteclaude)", existing?.hostname ?: "")
        val port = field("Puerto", existing?.port?.toString() ?: "22", number = true)
        val user = field("Usuario", existing?.user ?: "root")

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(label); addView(hostname); addView(port); addView(user)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Nuevo host" else "Editar host")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Guardar") { _, _ ->
                val l = label.text.toString().trim()
                val h = hostname.text.toString().trim()
                if (l.isEmpty() || h.isEmpty()) return@setPositiveButton
                val host = Host(
                    id = existing?.id ?: System.nanoTime().toString(),
                    label = l,
                    hostname = h,
                    port = port.text.toString().toIntOrNull() ?: 22,
                    user = user.text.toString().trim().ifEmpty { "root" },
                )
                HostStore.upsert(this, host)
                refresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
