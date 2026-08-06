package com.remoteclaude.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.termux.view.TerminalView

/**
 * La pantalla de terminal: arma el layout y conecta las piezas.
 *
 * Cada responsabilidad vive en su archivo — [TabsController] (pestañas y sesiones tmux),
 * [KeypadView] (teclas extra y modificadores), [DictationController] (dictado por voz) y
 * [TerminalClients] (los callbacks del motor vendorizado). Acá queda el cableado y lo que es
 * genuinamente de la activity: ciclo de vida, permisos, red y los diálogos de identidad del
 * host, que son decisiones de seguridad y por eso se preguntan en primer plano.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var terminalView: TerminalView
    private lateinit var tabs: TabsController
    private lateinit var keypad: KeypadView
    private lateinit var dictado: DictationController
    private lateinit var clients: TerminalClients

    private lateinit var control: RemoteControl
    private lateinit var keyPair: java.security.KeyPair
    private val prefs by lazy { getSharedPreferences("remotemarvin", Context.MODE_PRIVATE) }
    private val monoFont: Typeface by lazy { resources.getFont(R.font.mononoki) }

    // Host al que conectarse (viene de HostsActivity por Intent).
    private lateinit var host: String
    private var port = 22
    private lateinit var user: String
    private lateinit var hostId: String
    private lateinit var hostLabel: String

    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Al cambiar de red hay que re-alimentar la lista de interfaces al nodo embebido:
            // en Android, Go no puede enumerarlas, y sin esto el Tailscale embebido no se
            // recupera de un wifi -> datos (el roaming que promete el diseño).
            TailscaleBridge.refreshInterfaces()
            tabs.avisarRed(true)
        }
        override fun onLost(network: Network) = tabs.avisarRed(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        host = intent.getStringExtra("hostname") ?: "remoteclaude"
        port = intent.getIntExtra("port", 22)
        user = intent.getStringExtra("user") ?: "root"
        hostId = intent.getStringExtra("hostId") ?: "default"
        hostLabel = intent.getStringExtra("label") ?: host

        aplicarColoresDeMarca()
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )

        keyPair = KeyStoreSsh.getOrCreateKeyPair()
        control = RemoteControl(this, host, port, user, keyPair)

        terminalView = TerminalView(this, null)
        clients = TerminalClients(
            act = this,
            vista = { terminalView },
            sesionActiva = { tabs.sesionActiva },
            teclado = { if (::keypad.isInitialized) keypad else null },
            mostrarTeclado = { mostrarTeclado() },
            copiar = { copiarAlPortapapeles(it) },
        )
        terminalView.apply {
            setTerminalViewClient(clients.vistaCliente)
            isFocusable = true
            isFocusableInTouchMode = true
            setTextSize(clients.fuenteInicialPx())
            setTypeface(Typeface.MONOSPACE)
            setBackgroundColor(Paleta.KEYPAD_BG)   // evita el flash negro al abrir
        }

        val burbuja = crearBurbujaDictado()
        dictado = DictationController(
            act = this, host = host, port = port, user = user, keyPair = keyPair,
            control = control, burbuja = burbuja,
            teclado = { if (::keypad.isInitialized) keypad else null },
            sesionActiva = { tabs.sesionActiva },
        )

        keypad = KeypadView(this, object : KeypadView.Io {
            override fun enviarTecla(keyCode: Int) { terminalView.handleKeyCode(keyCode, 0) }
            override fun escribir(bytes: ByteArray) { tabs.sesionActiva?.write(bytes, 0, bytes.size) }
            override fun modoSeleccion(activo: Boolean) = alCambiarSeleccion(activo)
            override fun tocaronMicrofono(ev: MotionEvent) = dictado.alTocarMicrofono(ev)
            override fun volverElFoco() { terminalView.requestFocus() }
        })

        tabs = TabsController(
            act = this, prefs = prefs, hostId = hostId, control = control, mono = monoFont,
            crearSesion = { nombre -> crearSesion(nombre) },
            alActivar = { sesion ->
                terminalView.attachSession(sesion)
                terminalView.onScreenUpdated()
                terminalView.requestFocus()
            },
            acciones = object : TabsController.Acciones {
                override fun abrirVisor() = abrirOtraPantalla(DisplayActivity::class.java)
                override fun abrirDocumentos() = abrirOtraPantalla(DocsActivity::class.java)
                override fun mostrarClavePublica() = this@MainActivity.mostrarClavePublica()
            },
        )

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(barraDeHost(), LinearLayout.LayoutParams(Paleta.MATCH, Paleta.WRAP))
            addView(tabs.vista, LinearLayout.LayoutParams(Paleta.MATCH, Paleta.WRAP))
            addView(terminalView, LinearLayout.LayoutParams(Paleta.MATCH, 0, 1f))
            addView(burbuja, LinearLayout.LayoutParams(Paleta.MATCH, Paleta.WRAP))
            addView(keypad, LinearLayout.LayoutParams(Paleta.MATCH, Paleta.WRAP))
        })

        connectivity.registerDefaultNetworkCallback(networkCallback, Handler(mainLooper))
        vigilarTecladoDelSistema()
        tabs.restaurarOCrear()

        terminalView.requestFocus()
        terminalView.post { mostrarTeclado() }
    }

    override fun onDestroy() {
        try { connectivity.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        dictado.soltar()
        tabs.cerrarTodas()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == DictationController.REQ_MIC &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            dictado.avisarPermisoConcedido()
        }
    }

    /** Sesión activa. La usan los tests instrumentados para escribir en la terminal. */
    @androidx.annotation.VisibleForTesting
    fun currentSessionForTest(): SshTerminalSession? = tabs.sesionActiva

    /** Contenido visible de la terminal. Lo usan los tests instrumentados. */
    @androidx.annotation.VisibleForTesting
    fun screenText(): String = tabs.sesionActiva?.emulator?.screen?.transcriptText.orEmpty()

    // --- identidad del host ----------------------------------------------------
    // Los dos diálogos que siguen son decisiones de seguridad, y por eso los hace la activity
    // y sólo la terminal: los caminos de fondo (documentos, dictado, visor) fallan sin
    // preguntar, para que confiar en una clave nueva sea siempre algo explícito.

    private var dialogoClaveVisible = false
    @Volatile private var dialogoAuthVisible = false

    private fun crearSesion(nombre: String) = SshTerminalSession(
        this, host, port, user, keyPair, nombre, clients.sesion,
        onAuthFailed = { runOnUiThread { faltaAutorizar() } },
        onHostKeyChanged = { vieja, nueva -> runOnUiThread { cambioLaClave(vieja, nueva) } },
    )

    /** La clave del host cambió: la conexión YA fue rechazada; acá sólo se decide qué hacer. */
    private fun cambioLaClave(vieja: String, nueva: String) {
        if (isFinishing || isDestroyed || dialogoClaveVisible) return
        dialogoClaveVisible = true
        AlertDialog.Builder(this)
            .setTitle("La clave del host cambió")
            .setMessage(
                "El servidor $host:$port presentó una clave distinta a la que teníamos.\n\n" +
                    "Antes: $vieja\nAhora: $nueva\n\n" +
                    "Si reinstalaste el server o lo recreaste, es esperable. Si no, alguien " +
                    "puede estar interceptando la conexión."
            )
            .setNegativeButton("Cancelar") { _, _ -> dialogoClaveVisible = false }
            .setPositiveButton("Confiar en la nueva") { _, _ ->
                HostKeys.forget(this, host, port)
                dialogoClaveVisible = false
                tabs.reconectarActiva()
            }
            .setCancelable(false)
            .show()
    }

    /** La clave de la app no está autorizada en el host: se muestra cuál es y qué hacer. */
    private fun faltaAutorizar() {
        if (isFinishing || isDestroyed || dialogoAuthVisible) return
        dialogoAuthVisible = true   // varias pestañas fallan a la vez: un solo diálogo
        val pub = KeyStoreSsh.openSshPublicKey("remoteclaude-app")
        AlertDialog.Builder(this)
            .setTitle("Falta autorizar este dispositivo")
            .setMessage(
                "El host $user@$host:$port rechazó la clave de la app.\n\n" +
                    "Agregala a ~/.ssh/authorized_keys en el host:\n\n$pub"
            )
            .setNeutralButton("Copiar clave") { _, _ -> copiarAlPortapapeles(pub) }
            .setPositiveButton("Reintentar") { _, _ ->
                dialogoAuthVisible = false
                tabs.reconectarActiva()
            }
            .setNegativeButton("Cerrar") { _, _ -> dialogoAuthVisible = false }
            .setOnCancelListener { dialogoAuthVisible = false }
            .show()
    }

    private fun mostrarClavePublica() {
        val pub = KeyStoreSsh.openSshPublicKey("remoteclaude-app")
        AlertDialog.Builder(this)
            .setTitle("Clave pública (agregar a authorized_keys)")
            .setMessage(pub)
            .setPositiveButton("Copiar") { _, _ -> copiarAlPortapapeles(pub) }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    // --- layout y utilidades ---------------------------------------------------

    /** Colores de marca de la terminal: cambia el default del motor, no los colores ANSI. */
    private fun aplicarColoresDeMarca() {
        com.termux.terminal.TerminalColors.COLOR_SCHEME.mDefaultColors.let {
            it[com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND] = 0xFF0F232D.toInt()
            it[com.termux.terminal.TextStyle.COLOR_INDEX_FOREGROUND] = 0xFFE6ECE6.toInt()
            it[com.termux.terminal.TextStyle.COLOR_INDEX_CURSOR] = 0xFF71BF44.toInt()
        }
    }

    /** Barra superior: a qué host estás conectado, y volver al menú. */
    private fun barraDeHost(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Paleta.CHEV_BG)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        contentDescription = "Volver a la lista de hosts"
        setOnClickListener { finish() }
        addView(TextView(this@MainActivity).apply {
            text = "‹  $hostLabel"
            typeface = monoFont
            setTextColor(Paleta.ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }, LinearLayout.LayoutParams(0, Paleta.WRAP, 1f))
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.marvin_isologo_bar)
            adjustViewBounds = true
        }, LinearLayout.LayoutParams(Paleta.WRAP, dp(22)))
    }

    /** Burbuja del dictado en vivo: los parciales mientras sostenés 🎤. */
    private fun crearBurbujaDictado() = TextView(this).apply {
        visibility = View.GONE
        setBackgroundColor(0xF20F232D.toInt())
        setTextColor(Paleta.BUBBLE_FG)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.MONOSPACE
        maxLines = 3
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    /** Detecta si el teclado QWERTY está visible (por el alto que tapa) para la fila de ⇧Tab. */
    private fun vigilarTecladoDelSistema() {
        val decor = window.decorView
        decor.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect()
            decor.getWindowVisibleDisplayFrame(r)
            keypad.tecladoDelSistema(decor.height - r.height() > decor.height * 0.15)
        }
    }

    private fun alCambiarSeleccion(activo: Boolean) {
        terminalView.setSelectionDragMode(activo)
        Toast.makeText(
            this,
            if (activo) "Selección ON: arrastrá para marcar, soltá para copiar"
            else "Selección OFF (vuelve el scroll)",
            Toast.LENGTH_SHORT,
        ).show()
        terminalView.requestFocus()
    }

    private fun abrirOtraPantalla(destino: Class<*>) {
        startActivity(Intent(this, destino).apply {
            putExtra("hostname", host); putExtra("port", port); putExtra("user", user)
        })
    }

    private fun mostrarTeclado() {
        terminalView.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun copiarAlPortapapeles(texto: String) {
        val cb = getSystemService(ClipboardManager::class.java)
        cb?.setPrimaryClip(ClipData.newPlainText("terminal", texto))
        Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
    }
}
