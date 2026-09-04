package com.remoteclaude.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
    // La validación/aplicación vive en EnrolarTailscale (compartida con el ↺ de la terminal).
    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        if (EnrolarTailscale.aplicar(this, result.contents)) refrescarVpnEscalonado()
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

        // Guía rápida OFFLINE: rompe la dependencia circular del manual (que vive en
        // Documentos y sólo abre CONECTADO, pero para conectar hay que autorizar la clave
        // que el manual explica). Accesible sin conexión desde acá.
        root.addView(TextView(this).apply {
            text = "＄_ ¿Primera vez? Guía rápida (sin conexión)"
            typeface = monoFont
            setTextColor(getColor(R.color.marvin_green))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(20), 0, dp(20), dp(14))
            setOnClickListener { showQuickstart() }
        })

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

        // Si giraron la pantalla con el diálogo abierto, reabrirlo con lo que había tipeado.
        savedInstanceState?.getBundle("editHost")?.let { box ->
            root.post { showEditDialog(restore = box) }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        updateVpnStatus()
        vpnStatus.post { Tour.lanzar(this, "hosts2", pasosDemo()) }
    }

    private fun pasosDemo() = listOf(
        Tour.Paso(
            "Bienvenida a RemoteMarvin",
            "RemoteMarvin sistematiza la conexión con sus PC locales y servidores " +
                "desde el teléfono, orientado al desarrollo de software con Claude " +
                "Code. Esta guía muestra las funciones básicas de cada pantalla: " +
                "toque en cualquier lugar para continuar.",
        ),
        Tour.Paso(
            "Preparación de la PC",
            "Antes de conectarse, la PC debe configurarse una única vez: clone el " +
                "repositorio de RemoteMarvin y ejecute estos comandos. El archivo .env " +
                "requiere una clave de la página de Tailscale (el único paso con login, " +
                "detallado en la Guía rápida y en .env.example). El manual completo " +
                "quedará luego disponible en Documentos.",
            codigo = "bash scripts/setup-host.sh\ncp .env.example .env\ndocker compose up -d --build",
        ),
        Tour.Paso(
            "Las skills de Claude",
            "Dentro de Claude Code, en la PC, instale el plugin de RemoteMarvin: le " +
                "enseña a Claude a usar la app — compartir documentos al teléfono, " +
                "encontrar lo que usted sube y correr el navegador visible.",
            codigo = "/plugin marketplace add /ruta/al/repo\n/plugin install remotemarvin@remotemarvin",
        ),
        Tour.Paso(
            "Agregar un host",
            "Toque este botón para registrar su PC o servidor: nombre, IP y usuario " +
                "(su usuario normal, no root). La tarjeta creada se toca para conectar, " +
                "y se mantiene presionada para editar o eliminar.",
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
                Tour.lanzar(this, "hosts2", pasosDemo())
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private val vpnPoll = Runnable { updateVpnStatus() }

    private fun updateVpnStatus() {
        if (isFinishing || isDestroyed) return
        // Todo barato y del estado real del bus (v1.32: sin cache ni sondas JNI en un hilo).
        val (txt, tono) = lineaTailscale(
            enabled = TailscaleBridge.isEnabled(),
            vencido = TailscaleBridge.accesoVencido(),
            ready = TailscaleBridge.isReady(),
            hayError = TailscaleBridge.error() != null,
            tailnet = TailscaleBridge.tailnet(),
        )
        vpnStatus.text = txt
        vpnStatus.setTextColor(getColor(when (tono) {
            TonoLinea.MUTED -> R.color.marvin_muted
            TonoLinea.GREEN -> R.color.marvin_green
            TonoLinea.AMBER -> R.color.marvin_amber
        }))
        // Repoll SIEMPRE mientras el nodo esté habilitado — también en verde: antes se cortaba al
        // quedar ready y, como el latch nunca bajaba, hosts quedaba "conectada ✓" 3-5 min con la
        // terminal en vencido (UF5-2). Ahora ready es barato (estado del bus, sin JNI bloqueante).
        vpnStatus.removeCallbacks(vpnPoll)
        if (TailscaleBridge.isEnabled()) {
            vpnStatus.postDelayed(vpnPoll, when {
                TailscaleBridge.isReady() -> 5000
                TailscaleBridge.error() != null -> 3000
                else -> 1500
            })
        }
    }

    private fun showVpnDialog() = DialogoTailscale.mostrar(
        this,
        onKey = { applyTailscaleKey(it) },
        onScan = { qrScanner.launch(EnrolarTailscale.opciones()) },
    )

    /**
     * Guarda la auth key, reinicia el nodo embebido y refresca el estado. Vacío = conexión
     * directa (se limpia la key). Con key, pasa por EnrolarTailscale.aplicar: la misma
     * validación de forma (QA4-2) y el mismo feedback de re-vinculación (A4-1) que escanear
     * el QR — antes pegar la key llamaba a configure() directo y no anunciaba nada.
     */
    private fun applyTailscaleKey(key: String) {
        if (key.isBlank()) {
            TailscaleBridge.configure(this, key)
        } else if (!EnrolarTailscale.aplicar(this, key)) {
            return   // forma inválida: aplicar ya avisó; no reiniciar el nodo con basura
        }
        refrescarVpnEscalonado()
    }

    /** Repinta el estado ya, a los 3s y a los 9s (el nodo tarda en levantar). */
    private fun refrescarVpnEscalonado() {
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
            putExtra("avisosBg", host.avisosBg)
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

    // Diálogo de alta/edición abierto: se rastrea para sobrevivir a rotación. Android
    // recrea la Activity al girar y se llevaba puesto lo tipeado (sev-2). snapshotEdicion()
    // toma una foto de los campos en onSaveInstanceState y onCreate reabre el diálogo.
    private var editDialog: AlertDialog? = null
    private var snapshotEdicion: (() -> Bundle)? = null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        snapshotEdicion?.invoke()?.let { outState.putBundle("editHost", it) }
    }

    override fun onDestroy() {
        // Cerrar el diálogo antes de que se destruya la Activity evita el "leaked window"
        // (el estado ya quedó en onSaveInstanceState y se reabre en onCreate).
        editDialog?.dismiss()
        editDialog = null
        super.onDestroy()
    }

    private fun showEditDialog(existing: Host? = null, restore: Bundle? = null) {
        val idActual = restore?.getString("id")?.ifBlank { null } ?: existing?.id
        val esEdicion = idActual != null

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        // Label persistente (el hint desaparecía al tipear: sev-2 "campos sin label").
        fun etiqueta(txt: String) = box.addView(TextView(this).apply {
            text = txt
            // Token del tema: #666 (gris oscuro) quedaba ilegible sobre el diálogo oscuro.
            setTextColor(getColor(R.color.marvin_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(16), dp(10), dp(16), dp(2))
        })
        fun campo(value: String, hint: String, number: Boolean = false) = EditText(this).apply {
            this.hint = hint
            setText(value)
            setSingleLine()
            setSelectAllOnFocus(true)   // tocar un campo precargado selecciona todo para reemplazar fácil
            if (number) inputType = InputType.TYPE_CLASS_NUMBER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            box.addView(this)
        }

        etiqueta("Nombre")
        val label = campo(restore?.getString("label") ?: existing?.label ?: "", "ej: Mi PC")
        etiqueta("Host / IP")
        val hostname = campo(restore?.getString("hostname") ?: existing?.hostname ?: "", "ej: remoteclaude")
        etiqueta("Puerto")
        val port = campo(restore?.getString("port") ?: existing?.port?.toString() ?: "22", "22", number = true)
        etiqueta("Usuario")
        // Default vacío + placeholder: la app promete "sin root" y precargar "root" empujaba
        // al máximo privilegio (sev-3). Vacío obliga a tipear el usuario real de la PC.
        val user = campo(restore?.getString("user") ?: existing?.user ?: "", "tu usuario en la PC (no root)")

        // Dictado: el modo vive en el HOST (~/.config/marvin/stt-mode), no acá. Al abrir
        // se consulta el estado real por SSH; al guardar, si cambió, se aplica.
        val stt = Switch(this).apply {
            text = "⚡ Dictado siempre encendido"
            isEnabled = false
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        box.addView(stt)
        var sttInitial: Boolean? = null   // estado consultado (null = desconocido)
        if (esEdicion) {
            val qHost = restore?.getString("hostname") ?: existing?.hostname ?: ""
            val qPort = (restore?.getString("port") ?: existing?.port?.toString())?.toIntOrNull() ?: 22
            val qUser = restore?.getString("user") ?: existing?.user ?: ""
            // Al reabrir por rotación NO piso lo que el usuario ya haya tocado en el switch.
            val restaurando = restore != null
            thread {
                val st = try {
                    RemoteControl(this, qHost, qPort, qUser,
                        KeyStoreSsh.getOrCreateKeyPair()).sttMode("status")
                } catch (_: Exception) { "" }
                runOnUiThread {
                    if (st.contains("modo:")) {
                        sttInitial = st.contains("modo: always")
                        if (!restaurando) stt.isChecked = sttInitial == true
                        stt.isEnabled = true
                    } else {
                        stt.text = "⚡ Dictado siempre encendido (host inaccesible o sin dictado)"
                    }
                }
            }
        } else {
            stt.isEnabled = true   // host nuevo: se intenta aplicar al guardar
        }

        // Avisos en segundo plano (foreground service). Default OFF: es ADITIVO — sin prenderlo, el
        // aviso "Claude te espera" sigue funcionando best-effort (puede demorar/perderse en Doze);
        // prendido, un service liviano mantiene el canal vivo y la notif llega al instante aun con la
        // pantalla apagada. Pref local del host (no toca el host).
        val avisos = Switch(this).apply {
            text = "🔔 Avisos en segundo plano"
            isChecked = restore?.getBoolean("avisosBg") ?: existing?.avisosBg ?: false
            setPadding(dp(16), dp(12), dp(16), dp(0))
        }
        box.addView(avisos)
        box.addView(TextView(this).apply {
            text = "Prendelo para que la notificación \"Claude te espera\" llegue al instante, aun con la " +
                "pantalla apagada o la app cerrada. Apagado igual avisa, pero en reposo (Doze) puede " +
                "demorar o perderse."
            setTextColor(getColor(R.color.marvin_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(16), dp(0), dp(16), dp(10))
        })

        // Foto de los campos para sobrevivir a rotación.
        snapshotEdicion = {
            Bundle().apply {
                idActual?.let { putString("id", it) }
                putString("label", label.text.toString())
                putString("hostname", hostname.text.toString())
                putString("port", port.text.toString())
                putString("user", user.text.toString())
                putBoolean("avisosBg", avisos.isChecked)
            }
        }

        editDialog = AlertDialog.Builder(this)
            .setTitle(if (!esEdicion) "Nuevo host" else "Editar host")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Guardar") { _, _ ->
                val l = label.text.toString().trim()
                val h = hostname.text.toString().trim()
                val u = user.text.toString().trim()
                if (l.isEmpty() || h.isEmpty() || u.isEmpty()) {
                    Toast.makeText(this, "Completá nombre, host y usuario", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val host = Host(
                    id = idActual ?: System.nanoTime().toString(),
                    label = l,
                    hostname = h,
                    port = port.text.toString().toIntOrNull() ?: 22,
                    user = u,
                    avisosBg = avisos.isChecked,
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
            .setOnDismissListener { snapshotEdicion = null; editDialog = null }
            .show()
    }

    /**
     * Guía rápida OFFLINE (WS-G). El manual detallado vive en Documentos, que sólo abre
     * conectado; y conectar exige autorizar la clave que el manual explica. Esta guía da los
     * pasos mínimos —con comandos copiables— sin necesidad de estar conectado.
     */
    private fun showQuickstart() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        fun titulo(txt: String) = box.addView(TextView(this).apply {
            text = txt
            typeface = bodyFont
            setTypeface(typeface, Typeface.BOLD)
            // Tokens del tema (la app es mono-oscuro): antes hardcodeaba #222 = negro sobre el
            // diálogo oscuro → ilegible.
            setTextColor(getColor(R.color.marvin_fg))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(14), 0, dp(4))
        })
        fun parrafo(txt: String) = box.addView(TextView(this).apply {
            text = txt
            setTextColor(getColor(R.color.marvin_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        fun comando(cmd: String) {
            box.addView(TextView(this).apply {
                text = cmd
                typeface = monoFont
                setTextColor(getColor(R.color.marvin_fg))
                setBackgroundColor(getColor(R.color.marvin_petrol))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                val lp = LinearLayout.LayoutParams(MATCH, WRAP); lp.setMargins(0, dp(4), 0, dp(2))
                layoutParams = lp
            })
            box.addView(TextView(this).apply {
                text = "⧉ Copiar"
                typeface = monoFont
                setTextColor(getColor(R.color.marvin_green))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(4), dp(2), dp(4), dp(8))
                setOnClickListener {
                    getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("quickstart", cmd))
                    Toast.makeText(this@HostsActivity, "Copiado", Toast.LENGTH_SHORT).show()
                }
            })
        }

        titulo("1 · En la PC: preparar el host (una sola vez)")
        parrafo("Cloná el repo de RemoteMarvin y corré (dentro de la carpeta clonada):")
        comando("git clone https://github.com/jporta09/Remoteclaude\ncd Remoteclaude\nbash scripts/setup-host.sh\ncp .env.example .env   # completá TS_AUTHKEY (ver abajo)\ndocker compose up -d --build")
        parrafo("Las claves del .env salen de la página de Tailscale (login.tailscale.com — "
            + "es el único momento con login; en el teléfono nunca): TS_AUTHKEY en Settings → "
            + "Keys → Generate (Reusable, NO Ephemeral, con el tag tag:remotemarvin — sin tag "
            + "vence a los ~180 días), y el OAuth client para los QR en Settings → OAuth "
            + "clients → Generate (scope Auth Keys write + el mismo tag). El paso a paso, "
            + "ACL incluida, está en .env.example.")

        titulo("2 · Autorizar el teléfono")
        parrafo("Al agregar/conectar un host, la app muestra su clave SSH pública (ícono de "
            + "llave). Copiala y pegala en la PC, en ~/.ssh/authorized_keys. La clave privada "
            + "nunca sale del teléfono (Android Keystore).")

        titulo("3 · (Opcional) Conectar desde cualquier red — Tailscale")
        parrafo("En la PC generá un QR de un solo uso (requiere el OAuth client cargado en el "
            + ".env) y escanealo desde la línea “VPN” de esta pantalla. No hace falta IP fija "
            + "ni abrir puertos. Si a la cámara le cuesta el QR de la terminal: --png.")
        comando("./scripts/ts-link-qr.sh --png")

        titulo("4 · Agregar el host y conectar")
        parrafo("Tocá “+ Agregar host”: nombre, host/IP, puerto y tu usuario de la PC "
            + "(no root). Después tocá la tarjeta para conectar.")

        titulo("5 · Enseñarle las skills a Claude")
        parrafo("Dentro de Claude Code, en la PC:")
        comando("/plugin marketplace add /ruta/al/repo\n/plugin install remotemarvin@remotemarvin")

        parrafo("\nEl manual completo queda en Documentos una vez que conectás.")

        AlertDialog.Builder(this)
            .setTitle("Guía rápida")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Entendido", null)
            .show()
    }

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

/** Tono de la línea de estado de Tailscale en hosts (sin R.color, para testear la lógica en JVM). */
enum class TonoLinea { MUTED, GREEN, AMBER }

/**
 * Qué dice la línea de estado de Tailscale en hosts. ORDEN: el vencido va ANTES que ready.
 * Antes ready ganaba, y como ready era un latch que nunca bajaba, con el nodo vencido a mitad
 * de sesión hosts seguía en verde (UF5-2, 5ª pasada). Ámbar y reencuadrado para el vencido
 * (QA4-1/UX-1): lo que venció es el enrolamiento del nodo embebido; los hosts por LAN siguen.
 * Sin filtrar el error crudo del control-plane (QA4-3).
 */
internal fun lineaTailscale(enabled: Boolean, vencido: Boolean, ready: Boolean, hayError: Boolean, tailnet: String = ""): Pair<String, TonoLinea> =
    when {
        !enabled -> "🔒 VPN: directa · tocá para usar Tailscale embebido" to TonoLinea.MUTED
        vencido -> "🔒 Tailscale: enrolamiento vencido — reescaneá el QR (los hosts de LAN siguen)" to TonoLinea.AMBER
        // La identidad de red visible SIEMPRE, no sólo en un toast de 3,5 s (A5-5/UX5-5).
        ready -> (if (tailnet.isBlank()) "🔒 Tailscale: conectada ✓" else "🔒 Tailscale: conectada ✓ · $tailnet") to TonoLinea.GREEN
        hayError -> "🔒 Tailscale: no se pudo conectar — tocá para reintentar o reescanear" to TonoLinea.AMBER
        else -> "🔒 Tailscale: conectando…" to TonoLinea.AMBER
    }

