package com.remoteclaude.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
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
    private lateinit var aprobacion: AprobacionController

    /** F7: si la app está adelante (entre onResume y onPause). El controlador de aprobación lo
     *  consulta: adelante muestra la hoja; atrás avisa por notificación. */
    @Volatile private var enPrimerPlano = false

    private lateinit var control: RemoteControl
    private lateinit var keyPair: java.security.KeyPair
    private lateinit var barraHost: View
    private lateinit var barraLabel: TextView
    private lateinit var barraReconectar: TextView
    private var demoPendiente = false
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
        // Con la demo pendiente el teclado arranca cerrado: si no, tapa la fila
        // Shift/Sel/Dictar que la demo tiene que mostrar, y el resize mueve todo.
        demoPendiente = Tour.pendiente(this, "terminal")
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                if (demoPendiente) WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                else WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )

        keyPair = KeyStoreSsh.getOrCreateKeyPair()
        control = RemoteControl(this, host, port, user, keyPair)

        terminalView = TerminalView(this, null)
        aprobacion = AprobacionController(
            act = this,
            sesionActiva = { tabs.sesionActiva },
            // SIN unir líneas: transcriptText (el de screenText) junta las filas con espacios
            // y el prompt queda en UNA línea; el parser necesita cada opción en su renglón.
            screenText = { tabs.sesionActiva?.emulator?.screen?.transcriptTextWithoutJoinedLines.orEmpty() },
            enPrimerPlano = { enPrimerPlano },
        )
        clients = TerminalClients(
            act = this,
            vista = { terminalView },
            sesionActiva = { tabs.sesionActiva },
            teclado = { if (::keypad.isInitialized) keypad else null },
            mostrarTeclado = { mostrarTeclado() },
            alCambiarTexto = { aprobacion.alCambiarTexto(); actualizarA11yTerminal() },
        )
        terminalView.apply {
            setTerminalViewClient(clients.vistaCliente)
            isFocusable = true
            isFocusableInTouchMode = true
            setTextSize(clients.fuenteInicialPx())
            setTypeface(Typeface.MONOSPACE)
            setBackgroundColor(Paleta.KEYPAD_BG)   // evita el flash negro al abrir
            // A11y: el motor de terminal (GPL) no expone nada a TalkBack, así que desde acá lo
            // hacemos enfocable y le damos una content-description con la salida. Sin esto un
            // lector de pantalla no anunciaba NADA del área de terminal.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Terminal — salida del host"
        }

        val burbuja = crearBurbujaDictado()
        dictado = DictationController(
            act = this, host = host, port = port, user = user, keyPair = keyPair,
            control = control,
            burbuja = burbuja, burbujaTexto = burbujaTexto, burbujaBotones = burbujaBotones,
            btnInsertar = burbujaInsertar, btnDescartar = burbujaDescartar,
            teclado = { if (::keypad.isInitialized) keypad else null },
            sesionActiva = { tabs.sesionActiva },
        )

        keypad = KeypadView(this, object : KeypadView.Io {
            override fun enviarTecla(keyCode: Int) = enviarTeclaEspecial(keyCode)
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
                pintarBarra()   // al cambiar de pestaña, la barra refleja SU estado
                aprobacion.cerrarYolvidar()   // un prompt de OTRA pestaña no aplica a ésta
                aprobacion.alCambiarTexto()   // ¿la nueva pestaña tiene su propio prompt?
            },
            acciones = object : TabsController.Acciones {
                override fun abrirVisor() = abrirOtraPantalla(DisplayActivity::class.java)
                override fun abrirDocumentos() = abrirOtraPantalla(DocsActivity::class.java)
                override fun mostrarClavePublica() = this@MainActivity.mostrarClavePublica()
            },
        )

        barraHost = barraDeHost()
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(barraHost, LinearLayout.LayoutParams(Paleta.MATCH, Paleta.WRAP))
            addView(tabs.vista, LinearLayout.LayoutParams(Paleta.MATCH, Paleta.WRAP))
            addView(terminalView, LinearLayout.LayoutParams(Paleta.MATCH, 0, 1f))
            addView(burbuja, LinearLayout.LayoutParams(Paleta.MATCH, Paleta.WRAP))
            addView(keypad, LinearLayout.LayoutParams(Paleta.MATCH, Paleta.WRAP))
        })

        connectivity.registerDefaultNetworkCallback(networkCallback, Handler(mainLooper))
        vigilarTecladoDelSistema()
        tabs.restaurarOCrear()
        pedirPermisoNotificaciones()

        terminalView.requestFocus()
        if (!demoPendiente) terminalView.post { mostrarTeclado() }
    }

    /** Los pasos de la demo de la terminal, de arriba hacia abajo de la pantalla. */
    private fun pasosDemo() = listOf(
        Tour.Paso(
            "Host conectado",
            "Esta barra indica a qué equipo está conectado. Tóquela para volver a la " +
                "lista de hosts.",
            blanco = { barraHost },
        ),
        Tour.Paso(
            "Pestañas = sesiones tmux",
            "Cada pestaña es una sesión tmux que sigue activa en el host aunque se " +
                "cierre la aplicación. Mantenga presionado el nombre para renombrarla; " +
                "la ✕ la cierra.",
            blanco = { tabs.chipActivo() },
        ),
        Tour.Paso(
            "Sesiones en paralelo",
            "El botón + abre otra pestaña: útil para tener un Claude por proyecto.",
            blanco = { tabs.botonBarra("Pestaña nueva") },
            preparar = { tabs.scrollAlFinal() },
        ),
        Tour.Paso(
            "Reenganchar sesiones",
            "Si una sesión quedó activa en el host pero cerrada en la aplicación, " +
                "este botón permite recuperarla, con una vista previa de su última línea.",
            blanco = { tabs.botonBarra("Reenganchar una sesión") },
        ),
        Tour.Paso(
            "Visor del escritorio",
            "Abre el escritorio del host (noVNC), para cuando Claude usa el navegador " +
                "en modo visible.",
            blanco = { tabs.botonBarra("Ver el escritorio del host") },
        ),
        Tour.Paso(
            "Documentos",
            "Los archivos que Claude comparte con marvin-share aparecen ahí: " +
                "imágenes, PDF y texto.",
            blanco = { tabs.botonBarra("Documentos compartidos") },
        ),
        Tour.Paso(
            "Clave pública del teléfono",
            "Muestra la clave SSH de este teléfono, para autorizarla en el archivo " +
                "authorized_keys de otro host.",
            blanco = { tabs.botonBarra("Clave pública de la app") },
        ),
        Tour.Paso(
            "La terminal",
            "Pellizque para ajustar el tamaño de la letra. Toque para abrir el teclado.",
            blanco = { terminalView },
        ),
        Tour.Paso(
            "Más teclas",
            "El botón › alterna Esc/Tab/Ctrl/Alt por Home/End/PgUp/PgDn. PgUp y PgDn " +
                "sirven para desplazarse por las respuestas de Claude.",
            blanco = { keypad.vistaChevron() },
        ),
        Tour.Paso(
            "Una fila que aparece y desaparece",
            "Shift, Sel y Dictar viven en esta fila. Cuando el teclado del sistema " +
                "está abierto, la fila se oculta para dejar espacio; al cerrarlo, vuelve.",
            blanco = { keypad.vistaFilaShift() },
        ),
        Tour.Paso(
            "Copiar con Sel",
            "Con Sel activado, arrastre el dedo para marcar líneas; al soltar se " +
                "copian automáticamente. Es la forma recomendada de copiar comandos.",
            blanco = { keypad.vistaSel() },
        ),
        Tour.Paso(
            "Dictado por voz",
            "Mantenga presionado el botón y hable: rojo indica que graba, … que " +
                "transcribe. Al soltar, la transcripción aparece en una burbuja: " +
                "Insertar la escribe en la terminal (sin Enter, para revisarla) o " +
                "Descartar la tira.",
            blanco = { keypad.vistaMic() },
        ),
    )

    override fun onResume() {
        super.onResume()
        enPrimerPlano = true
        // Volvimos adelante: bajar cualquier aviso y mostrar la hoja del prompt pendiente.
        if (::aprobacion.isInitialized) aprobacion.alPrimerPlano()
    }

    override fun onPause() {
        enPrimerPlano = false
        super.onPause()
    }

    override fun onDestroy() {
        try { connectivity.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        dictado.soltar()
        tabs.cerrarTodas()
        super.onDestroy()
    }

    /** Pide el permiso de notificaciones (Android 13+) una sola vez, para poder avisar cuando
     *  Claude quede esperando aprobación con la app en segundo plano. Best-effort: si se niega,
     *  la hoja igual aparece al volver a la app. */
    private fun pedirPermisoNotificaciones() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
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
    /** Manda una tecla especial (End, Home, PgUp/Dn, flechas…) propagando los modificadores
     *  pegajosos del keypad. Sin esto Ctrl+End/etc. salían pelados (keyMod=0) y el host nunca
     *  veía el modificador. handleKeyCode usa el bitmask de termux (KEYMOD_*), no el metaState. */
    private fun enviarTeclaEspecial(keyCode: Int) {
        var mod = 0
        if (keypad.ctrlActivo) mod = mod or com.termux.terminal.KeyHandler.KEYMOD_CTRL
        if (keypad.altActivo) mod = mod or com.termux.terminal.KeyHandler.KEYMOD_ALT
        terminalView.handleKeyCode(keyCode, mod)
        keypad.soltarModificadores()   // one-shot, igual que el camino de caracteres
    }

    fun screenText(): String = tabs.sesionActiva?.emulator?.screen?.transcriptText.orEmpty()

    private val a11yManager by lazy {
        getSystemService(android.view.accessibility.AccessibilityManager::class.java)
    }

    /** A11y: refleja la salida reciente en la content-description de la terminal, para que un
     *  lector de pantalla la lea al enfocar (el motor GPL no lo expone). Sólo hace el trabajo si
     *  hay un lector activo — si no, construir screenText() en cada cambio sería un costo al pedo. */
    private fun actualizarA11yTerminal() {
        if (a11yManager?.isTouchExplorationEnabled != true) return
        val t = screenText()
        terminalView.contentDescription =
            if (t.isBlank()) "Terminal — salida del host"
            else if (t.length > 1500) "…" + t.takeLast(1500)
            else t
    }

    @androidx.annotation.VisibleForTesting
    fun activarCtrlKeypadForTest() { keypad.activarCtrlParaTest() }

    @androidx.annotation.VisibleForTesting
    fun enviarTeclaEspecialForTest(keyCode: Int) = enviarTeclaEspecial(keyCode)

    @androidx.annotation.VisibleForTesting
    fun aprobacionVisibleForTest(): Boolean = ::aprobacion.isInitialized && aprobacion.hojaVisibleParaTest()

    @androidx.annotation.VisibleForTesting
    fun aprobacionElegirForTest(numero: Int) { if (::aprobacion.isInitialized) aprobacion.elegirParaTest(numero) }

    /** F7: los tests corren con la activity RESUMED; esto simula que la app pasó a segundo plano
     *  (y de vuelta) para verificar que un prompt nuevo se avise por notificación en vez de por
     *  la hoja, y que al volver el aviso se baje y la hoja aparezca. Al volver replica onResume. */
    @androidx.annotation.VisibleForTesting
    fun simularSegundoPlanoParaTest(atras: Boolean) {
        enPrimerPlano = !atras
        if (!atras && ::aprobacion.isInitialized) aprobacion.alPrimerPlano()
    }

    @androidx.annotation.VisibleForTesting
    fun avisoAprobacionActivoForTest(): Boolean =
        ::aprobacion.isInitialized && aprobacion.avisoActivoParaTest()

    /** Texto actual de la barra de host (con el estado real). Para los tests instrumentados. */
    @androidx.annotation.VisibleForTesting
    fun barLabelForTest(): String = if (::barraLabel.isInitialized) barraLabel.text.toString() else ""

    companion object {
        /** Código del pedido de permiso POST_NOTIFICATIONS (Android 13+). */
        private const val REQ_NOTIF = 72
    }

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
        onEstadoCambio = { runOnUiThread { alCambiarEstadoConexion() } },
        onSesionPerdida = { nombre ->
            runOnUiThread {
                sesionPerdidaCount++   // observable para los tests (el texto en la terminal lo pisa tmux)
                if (!isFinishing && !isDestroyed && tabs.sesionActiva?.tmuxSession == nombre) {
                    Toast.makeText(
                        this,
                        "La sesión anterior se perdió (el host se reinició). Esta es nueva.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        },
    )

    @Volatile private var sesionPerdidaCount = 0

    /** Cuántas veces se avisó "sesión perdida". Para los tests (el texto en la terminal lo
     *  borra el redibujo de tmux al recrear la sesión, así que se verifica el callback). */
    @androidx.annotation.VisibleForTesting
    fun sesionPerdidaCountForTest(): Int = sesionPerdidaCount

    /** El estado real de conexión cambió: refrescar la barra y, si recién se estableció la
     *  conexión, disparar la demo de la terminal (antes se disparaba al activar la pestaña,
     *  aunque la auth hubiera fallado — mostraba "Host conectado" sobre una conexión muerta). */
    private fun alCambiarEstadoConexion() {
        pintarBarra()
        if (demoPendiente && tabs.sesionActiva?.estado == SshTerminalSession.Estado.CONECTADO) {
            demoPendiente = false
            terminalView.post { Tour.lanzar(this, "terminal", pasosDemo()) { mostrarTeclado() } }
        }
    }

    /** Pinta la barra según el estado REAL de la pestaña activa (no del extra del Intent). */
    private fun pintarBarra() {
        val (sufijo, color) = when (tabs.sesionActiva?.estado) {
            SshTerminalSession.Estado.CONECTADO -> "" to Paleta.ACCENT
            SshTerminalSession.Estado.RECONECTANDO -> " · reconectando…" to getColor(R.color.marvin_amber)
            SshTerminalSession.Estado.CAIDO -> " · sin conexión" to Paleta.REC_FG
            else -> " · conectando…" to getColor(R.color.marvin_amber)   // CONECTANDO o null
        }
        barraLabel.text = "‹  $hostLabel$sufijo"
        barraLabel.setTextColor(color)
        barraReconectar.visibility =
            if (tabs.sesionActiva?.estado == SshTerminalSession.Estado.CAIDO) View.VISIBLE else View.GONE
    }

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
                // Todas, no sólo la activa: confiar en la clave nueva vale para el host
                // entero, y las pestañas que ya habían fallado quedaban muertas.
                tabs.reconectarTodas()
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
            .setView(vistaAutorizacion("El host $user@$host:$port rechazó la clave de la app. " +
                "Autorizá el teléfono en el host —el comando lo hace en un solo pegado:", pub))
            .setPositiveButton("Reintentar") { _, _ ->
                dialogoAuthVisible = false
                // Ídem: la autorización de la clave es del host, no de una pestaña.
                tabs.reconectarTodas()
            }
            .setNegativeButton("Cerrar") { _, _ -> dialogoAuthVisible = false }
            .setOnCancelListener { dialogoAuthVisible = false }
            .show()
    }

    private fun mostrarClavePublica() {
        val pub = KeyStoreSsh.openSshPublicKey("remoteclaude-app")
        AlertDialog.Builder(this)
            .setTitle("Autorizar este dispositivo en el host")
            .setView(vistaAutorizacion("Pegá el comando en la PC (agrega la clave a " +
                "authorized_keys y le pone los permisos correctos), o copiá sólo la clave:", pub))
            .setPositiveButton("Cerrar", null)
            .show()
    }

    /** El comando listo-para-pegar que autoriza el teléfono en el host en un solo paso: crea
     *  ~/.ssh con permisos, agrega la clave y deja authorized_keys en 600. Antes el diálogo
     *  sólo daba la clave cruda y había que recordar el resto (sev-3 "autorización sin comando"). */
    private fun comandoAutorizar(pub: String) =
        "mkdir -p ~/.ssh && chmod 700 ~/.ssh && " +
            "echo '$pub' >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"

    /** Vista compartida de los diálogos de autorización: explicación + la clave (seleccionable)
     *  + dos botones de copia (el comando completo, recomendado, y sólo la clave). */
    private fun vistaAutorizacion(encabezado: String, pub: String): View {
        fun dpx(v: Int) =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpx(20), dpx(8), dpx(20), dpx(8))
        }
        box.addView(TextView(this).apply {
            text = encabezado
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
        box.addView(TextView(this).apply {
            text = pub
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dpx(10), 0, dpx(12))
        })
        box.addView(Button(this).apply {
            text = "⧉ Copiar comando (pegá en la PC)"
            isAllCaps = false
            setOnClickListener { copiarAlPortapapeles(comandoAutorizar(pub)) }
        })
        box.addView(Button(this).apply {
            text = "⧉ Copiar solo la clave"
            isAllCaps = false
            setOnClickListener { copiarAlPortapapeles(pub) }
        })
        return ScrollView(this).apply { addView(box) }
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

    /** Barra superior: a qué host estás conectado (con estado REAL), y volver al menú. */
    private fun barraDeHost(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Paleta.CHEV_BG)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        contentDescription = "Volver a la lista de hosts"
        setOnClickListener { finish() }
        barraLabel = TextView(this@MainActivity).apply {
            text = "‹  $hostLabel"
            typeface = monoFont
            setTextColor(Paleta.ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        addView(barraLabel, LinearLayout.LayoutParams(0, Paleta.WRAP, 1f))
        // "Reconectar" aparece SOLO cuando la conexión cayó (auth/host-key): antes no había
        // un botón claro para reintentar — el que existía ("Reenganchar") reattachea tmux,
        // no reconecta SSH. Su propio click lo consume; el resto de la barra sigue volviendo.
        barraReconectar = TextView(this@MainActivity).apply {
            text = "↻ Reconectar"
            typeface = monoFont
            setTextColor(getColor(R.color.marvin_amber))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(10), 0, dp(10), 0)
            visibility = View.GONE
            setOnClickListener { tabs.reconectarActiva() }
        }
        addView(barraReconectar, LinearLayout.LayoutParams(Paleta.WRAP, Paleta.WRAP))
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.marvin_isologo_bar)
            adjustViewBounds = true
        }, LinearLayout.LayoutParams(Paleta.WRAP, dp(22)))
    }

    /** Burbuja del dictado en vivo: los parciales mientras sostenés 🎤. */
    // La burbuja de dictado dejó de ser un TextView suelto: ahora es un contenedor con el
    // texto (parciales en vivo + transcripción final) y una fila de acciones Descartar/Insertar
    // que aparece SOLO en el preview (F6). Las partes las maneja DictationController.
    private lateinit var burbujaTexto: TextView
    private lateinit var burbujaBotones: LinearLayout
    private lateinit var burbujaInsertar: TextView
    private lateinit var burbujaDescartar: TextView

    private fun crearBurbujaDictado(): View {
        burbujaTexto = TextView(this).apply {
            setTextColor(Paleta.BUBBLE_FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
            maxLines = 4
        }
        fun accion(txt: String, color: Int) = TextView(this).apply {
            text = txt
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(18), dp(8), dp(18), dp(6))
        }
        // Descartar tan fácil como Insertar (recomendación del Arq. IA: la ruta de menor
        // esfuerzo no debe ser "aceptar sí o sí").
        burbujaDescartar = accion("Descartar", Paleta.REC_FG)
        burbujaInsertar = accion("Insertar", Paleta.ACCENT)
        burbujaBotones = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            visibility = View.GONE
            addView(burbujaDescartar)
            addView(burbujaInsertar)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(0xF20F232D.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(burbujaTexto)
            addView(burbujaBotones)
        }
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
