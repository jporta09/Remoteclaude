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
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/** Menú principal: lista de hosts a los que conectarse. Tocar uno abre la terminal;
 *  mantener apretado permite editar/borrar. El "+" agrega uno nuevo. */
class HostsActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var vpnStatus: TextView
    private lateinit var botonAgregar: Button
    private val titleFont by lazy { resources.getFont(R.font.osifont) }     // títulos (marca)
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
            // El replay de la demo (lo enseña la última burbuja del tour de hosts).
            setOnLongClickListener { preguntarRepetirDemo(); true }
        })
        // Estado de la VPN (Tailscale embebido) — tocar para configurar el auth key
        vpnStatus = TextView(this).apply {
            typeface = fuenteDetalle()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(20), 0, dp(20), dp(14))
            setOnClickListener { showVpnDialog() }
        }
        root.addView(vpnStatus)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(MATCH, 0, 1f))

        // Botón agregar
        botonAgregar = Button(this).apply {
            text = "+  Agregar host"
            isAllCaps = false
            typeface = bodyFont
            setTextColor(getColor(R.color.marvin_petrol))
            setBackgroundColor(getColor(R.color.marvin_green))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setOnClickListener { showEditDialog(null) }
            val lp = LinearLayout.LayoutParams(MATCH, WRAP); lp.setMargins(dp(16), dp(8), dp(16), dp(16))
            layoutParams = lp
        }
        root.addView(botonAgregar)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        updateVpnStatus()
        vpnStatus.post { Tour.lanzar(this, "hosts", pasosDemo()) }
    }

    private fun pasosDemo() = listOf(
        Tour.Paso(
            "Bienvenida a RemoteMarvin",
            "RemoteMarvin es una terminal para controlar Claude en su PC desde el " +
                "teléfono. Esta guía muestra las funciones básicas de cada pantalla: " +
                "toque en cualquier lugar para continuar.",
        ),
        Tour.Paso(
            "Preparación de la PC",
            "Antes de conectarse, la PC debe configurarse una única vez: clone el " +
                "repositorio de RemoteMarvin y ejecute estos comandos. Dejan listos " +
                "SSH, tmux y los servicios; el manual completo quedará luego " +
                "disponible en Documentos.",
            codigo = "bash scripts/setup-host.sh\ndocker compose up -d --build",
        ),
        Tour.Paso(
            "Agregar un host",
            "Toque este botón para registrar su PC o servidor: nombre, IP y usuario. " +
                "La tarjeta creada se toca para conectar, y se mantiene presionada " +
                "para editar o eliminar.",
            blanco = { botonAgregar },
        ),
        Tour.Paso(
            "Conexión desde cualquier red",
            "Toque esta línea para activar el Tailscale integrado: en la PC ejecute " +
                "ts-link-qr y escanee el código QR. No requiere IP fija ni abrir puertos.",
            blanco = { vpnStatus },
        ),
        Tour.Paso(
            "Fin de esta guía",
            "Cada pantalla nueva presenta su propia guía la primera vez que se abre. " +
                "Para repetir esta, mantenga presionado el subtítulo \"_hosts\".",
        ),
    )

    private fun preguntarRepetirDemo() {
        AlertDialog.Builder(this)
            .setMessage("¿Repetir la demostración de primer uso?")
            .setPositiveButton("Repetir") { _, _ ->
                Tour.reiniciar(this)
                Tour.lanzar(this, "hosts", pasosDemo())
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private val vpnPoll = Runnable { updateVpnStatus() }

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
        // Mientras conecta, refrescar solo hasta que muestre conectada ✓ (o error).
        vpnStatus.removeCallbacks(vpnPoll)
        if (TailscaleBridge.isEnabled() && !TailscaleBridge.isReady() && TailscaleBridge.error() == null) {
            vpnStatus.postDelayed(vpnPoll, 1500)
        }
    }

    private fun showVpnDialog() {
        // No se precarga la key: mostrarla entera en pantalla (y dejarla en la jerarquía
        // de vistas) es innecesario. Se indica si hay una configurada y listo.
        val current = SecretStore.get(this, "ts_authkey")
        val input = EditText(this).apply {
            hint = if (current.isBlank()) "tskey-auth-…"
                   else "configurada (…${current.takeLast(6)}) — pegá otra para reemplazar"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
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
                val typed = input.text.toString().trim()
                // vacío = no tocar la que ya está (antes la borraba sin querer)
                if (typed.isNotEmpty() || current.isBlank()) applyTailscaleKey(typed)
            }
            .setNeutralButton("Escanear QR") { _, _ ->
                qrScanner.launch(
                    ScanOptions().apply {
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        setPrompt("Apuntá al QR de la PC (ts-link-qr)")
                        setBeepEnabled(false)
                        setOrientationLocked(false)
                        setCaptureActivity(PortraitCaptureActivity::class.java)
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
                typeface = fuenteDetalle()
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
            .setItems(arrayOf("Conectar", "Editar", "Olvidar clave del host", "Borrar")) { _, which ->
                when (which) {
                    0 -> connect(host)
                    1 -> showEditDialog(host)
                    // Salida de emergencia del pinning: para reinstalaciones legítimas del
                    // server, sin tener que borrar y recrear el host.
                    2 -> {
                        HostKeys.forget(this, host.hostname, host.port)
                        Toast.makeText(this, "Clave olvidada: se fijará de nuevo al conectar", Toast.LENGTH_LONG).show()
                    }
                    3 -> { HostStore.delete(this, host.id); refresh() }
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

        // Dictado: el modo vive en el HOST (~/.config/marvin/stt-mode), no acá. Al abrir
        // se consulta el estado real por SSH; al guardar, si cambió, se aplica.
        val stt = Switch(this).apply {
            text = "⚡ Dictado siempre encendido"
            isEnabled = false
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        var sttInitial: Boolean? = null   // estado consultado (null = desconocido)
        if (existing != null) {
            thread {
                val st = try {
                    RemoteControl(this, existing.hostname, existing.port, existing.user,
                        KeyStoreSsh.getOrCreateKeyPair()).sttMode("status")
                } catch (_: Exception) { "" }
                runOnUiThread {
                    if (st.contains("modo:")) {
                        sttInitial = st.contains("modo: always")
                        stt.isChecked = sttInitial == true
                        stt.isEnabled = true
                    } else {
                        stt.text = "⚡ Dictado siempre encendido (host inaccesible o sin dictado)"
                    }
                }
            }
        } else {
            stt.isEnabled = true   // host nuevo: se intenta aplicar al guardar
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(label); addView(hostname); addView(port); addView(user); addView(stt)
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
                // aplicar el modo de dictado si cambió (baseline: ondemand si no se supo)
                if (stt.isEnabled && (sttInitial ?: false) != stt.isChecked) {
                    val action = if (stt.isChecked) "always" else "ondemand"
                    thread {
                        val msg = try {
                            RemoteControl(this, host.hostname, host.port, host.user,
                                KeyStoreSsh.getOrCreateKeyPair()).sttMode(action)
                        } catch (_: Exception) { "" }
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                msg.ifBlank { "No se pudo aplicar el modo de dictado (host inaccesible)" },
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
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
