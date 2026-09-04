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
import kotlin.concurrent.thread

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
    private lateinit var vigia: VigiaUi

    /** Canal de avisos best-effort en la Activity. Sólo se usa cuando el host NO tiene "Avisos en
     *  segundo plano" (con el toggle ON el canal lo hostea [AvisosService], para no duplicar el tail). */
    private var notifsRemotas: NotificacionesRemotas? = null

    /** Avisos en segundo plano prendidos para este host (pref local, viene por Intent). */
    private var avisosBg = false

    /** Cuándo se fue a background (elapsedRealtime), para decidir si al volver forzar reconexión. */
    private var pausadoEnMs = 0L

    /** Cuándo se soltó el último arrastre en modo Sel (elapsedRealtime). Abre una ventana de UN SOLO
     *  USO para aceptar la copia OSC 52 que tmux emite al soltar, y sólo esa (handshake, no toggle). */
    @Volatile private var selDragSoltadoEnMs = 0L
    // Throttle del despertar-STT (el callback de estado dispara por pestaña y por reconexión).
    @Volatile private var ultimoDespertarStt = -60_000L

    private lateinit var control: RemoteControl
    private lateinit var keyPair: java.security.KeyPair
    private lateinit var barraHost: View
    private lateinit var bannerApp: TextView
    private lateinit var barraLabel: TextView
    private lateinit var barraReconectar: TextView
    private lateinit var barraLogo: ImageView
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
    // El callback de red corría en el main y onLost cerraba la Connection synchronized de trilead
    // ahí mismo (SRE-5-4): con un connect en vuelo contra un endpoint colgado, ANR. Hilo propio.
    private val hiloRed by lazy { android.os.HandlerThread("red").apply { start() } }
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
        avisosBg = intent.getBooleanExtra("avisosBg", false)

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

        // Diagnóstico: recuperar los eventos de conexión de una corrida anterior (si el proceso murió)
        // ANTES de habilitar la persistencia, para no re-persistir lo recuperado.
        Diagnostico.cargarPersistidos(this)
        Diagnostico.init(this)

        // Vigía del hilo de UI (#1): sube al Diagnóstico cuelgues de una corrida anterior (si la app
        // se cerró en el freeze) y arranca a vigilar el main en primer plano.
        VigiaUi.cargarPersistidos(this)
        vigia = VigiaUi(this)

        terminalView = TerminalView(this, null)
        // Alerta "Claude te espera" por el hook Notification del host (canal SSH que tailea notify.jsonl).
        // ADITIVO: si el host tiene "Avisos en segundo plano", el canal lo mantiene vivo AvisosService
        // (foreground service, sobrevive a background/cierre → entrega en tiempo real) y acá NO lo
        // lanzamos, para no duplicar el tail. Si no, lo corre la Activity best-effort (default de siempre).
        //
        // "Detener" sticky: en un arranque FRESCO (no rotación) re-armamos, así un "Detener" previo no
        // queda pegado. Si venís de recrear la Activity (rotación) y paraste los avisos, se respeta: NO
        // se revive el FGS; cae al best-effort de la Activity.
        if (savedInstanceState == null) AvisosService.rearmar(this)
        if (avisosBg && !AvisosService.detenidoPorUsuario(this)) {
            AvisosService.iniciar(this, host, port, user, hostLabel, hostId)
        } else {
            AvisosService.detener(this)
            val appCtx = applicationContext
            val idHost = hostId
            notifsRemotas = NotificacionesRemotas(
                ctx = appCtx, host = host, port = port, user = user, key = keyPair,
                enPrimerPlano = { EstadoApp.enPrimerPlano }, etiqueta = hostLabel,
                // Se leen de prefs (no de `tabs`, que acá aún no existe): TabsController las
                // persiste en cada cambio, así el set está siempre al día.
                sesionesDeLaApp = {
                    TabPlan.parseSaved(
                        appCtx.getSharedPreferences("remotemarvin", Context.MODE_PRIVATE)
                            .getString("tabs_$idHost", ""),
                    ).toSet()
                },
            ).also { it.iniciar() }
        }
        clients = TerminalClients(
            act = this,
            vista = { terminalView },
            sesionActiva = { tabs.sesionActiva },
            teclado = { if (::keypad.isInitialized) keypad else null },
            mostrarTeclado = { mostrarTeclado() },
            ocultarTeclado = { ocultarTeclado() },
            alCambiarTexto = { actualizarA11yTerminal() },
            copiaLaPediste = { copiaIniciadaPorVos() },
            alSoltarSelDrag = { selDragSoltadoEnMs = android.os.SystemClock.elapsedRealtime() },
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
            sesionAbierta = { s -> ::tabs.isInitialized && tabs.sesionAbierta(s) },
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
            },
            acciones = object : TabsController.Acciones {
                override fun abrirVisor() = abrirOtraPantalla(DisplayActivity::class.java)
                override fun abrirDocumentos() = abrirOtraPantalla(DocsActivity::class.java)
                override fun mostrarClavePublica() = this@MainActivity.mostrarClavePublica()
            },
            alCerrarTab = { sesion -> if (::dictado.isInitialized) dictado.tabCerrado(sesion) },
        )

        barraHost = barraDeHost()

        bannerApp = bannerDeAvisos()
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(barraHost, LinearLayout.LayoutParams(Paleta.MATCH, Paleta.WRAP))
            addView(bannerApp, LinearLayout.LayoutParams(Paleta.MATCH, Paleta.WRAP))
            addView(tabs.vista, LinearLayout.LayoutParams(Paleta.MATCH, Paleta.WRAP))
            addView(terminalView, LinearLayout.LayoutParams(Paleta.MATCH, 0, 1f))
            addView(burbuja, LinearLayout.LayoutParams(Paleta.MATCH, Paleta.WRAP))
            addView(keypad, LinearLayout.LayoutParams(Paleta.MATCH, Paleta.WRAP))
        })

        connectivity.registerDefaultNetworkCallback(networkCallback, Handler(hiloRed.looper))
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
            "Shift, Sel, Dictar y Enter viven en esta fila. Cuando el teclado del sistema " +
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
        // Compartido con AvisosService (corre en otro componente): en primer plano no molesta con la
        // notif, ya estás mirando (y el modo lectura bajó el teclado).
        EstadoApp.enPrimerPlano = true
        // Volvimos adelante: bajar cualquier aviso pendiente (ya estás mirando).
        NotificacionesRemotas.cancelar(this)
        if (::vigia.isInitialized) vigia.reanudar()
        // En background la conexión se cae; al volver, reconectar YA en vez de esperar el backoff
        // (caso "detectado", barra en reconectando). #1.
        if (::tabs.isInitialized) {
            tabs.reintentarConexiones()
            // Caso "no detectado": conexión media (muerta sin RST) que la app cree viva → terminal
            // congelada sin avisar. Si estuvimos un rato afuera, forzar reconexión (tmux -A reengancha
            // sin pérdida). Un vistazo corto no fuerza nada (no vale la pena el redibujo).
            val afueraMs = android.os.SystemClock.elapsedRealtime() - pausadoEnMs
            if (pausadoEnMs != 0L && afueraMs > UMBRAL_FORZAR_RECONEXION_MS) tabs.forzarReconexiones()
        }
        // La presencia del dictado sigue al foreground (mantiene despierto al server en vivo).
        if (::dictado.isInitialized) dictado.enPrimerPlano(true)
        barraHost.removeCallbacks(repintarBarra)
        barraHost.post(repintarBarra)
    }

    /**
     * Repinta la barra mientras la pantalla está al frente. El estado del NODO (vencido) cambia sin
     * que la conexión SSH cambie de estado — con un host por LAN la terminal sigue viva y feliz —,
     * así que sin este repoll el ↺ Reescanear QR recién aparecía al volver a entrar (visto en la
     * validación de v1.32.0). Es barato: pintarBarra sólo lee flags atómicos, sin JNI ni red.
     */
    private val repintarBarra = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            pintarBarra()
            barraHost.postDelayed(this, 3000)
        }
    }

    override fun onPause() {
        EstadoApp.enPrimerPlano = false
        pausadoEnMs = android.os.SystemClock.elapsedRealtime()
        if (::vigia.isInitialized) vigia.pausar()
        if (::dictado.isInitialized) dictado.enPrimerPlano(false)
        if (::barraHost.isInitialized) barraHost.removeCallbacks(repintarBarra)
        super.onPause()
    }

    override fun onDestroy() {
        try { connectivity.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        hiloRed.quitSafely()
        if (::vigia.isInitialized) vigia.detener()
        // El canal best-effort de la Activity muere con ella. El de AvisosService NO se toca acá: su
        // razón de ser es sobrevivir a que la app se cierre (swipe) para seguir avisando; se apaga por
        // su acción "Detener" o al conectar con el toggle apagado.
        notifsRemotas?.detener()
        dictado.soltar()
        tabs.cerrarTodas()
        super.onDestroy()
    }

    /** Pide el permiso de notificaciones (Android 13+) una sola vez, para poder avisar cuando
     *  Claude quede esperando una decisión con la app en segundo plano. Best-effort: si se niega,
     *  degrada en silencio (igual ves el prompt al abrir la app). */
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

    /** El keypad, para que los tests verifiquen el estado del mic (gate del dictado). */
    @androidx.annotation.VisibleForTesting
    fun keypadForTest(): KeypadView = keypad

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

    /** Texto actual de la barra de host (con el estado real). Para los tests instrumentados. */
    @androidx.annotation.VisibleForTesting
    fun barLabelForTest(): String = if (::barraLabel.isInitialized) barraLabel.text.toString() else ""

    /** Bloquea el hilo principal por `ms` (llamar en el hilo de UI): para testear que el [VigiaUi]
     *  detecta el cuelgue y captura el stack. */
    @androidx.annotation.VisibleForTesting
    fun bloquearMainParaTest(ms: Long) = Thread.sleep(ms)

    /** Fuerza reconexión en todas las sesiones (lo que hace el resume ante una conexión muerta). */
    @androidx.annotation.VisibleForTesting
    fun forzarReconexionForTest() { if (::tabs.isInitialized) tabs.forzarReconexiones() }

    /** Simula que soltaste un arrastre en modo Sel: abre la ventana del handshake OSC 52. */
    @androidx.annotation.VisibleForTesting
    fun marcarSelDragSoltadoForTest() { selDragSoltadoEnMs = android.os.SystemClock.elapsedRealtime() }

    /** Prende el modo Sel SIN arrastre: para verificar que el toggle ya NO alcanza para copiar. */
    @androidx.annotation.VisibleForTesting
    fun activarSelForTest() { if (::terminalView.isInitialized) terminalView.setSelectionDragMode(true) }

    companion object {
        /** Código del pedido de permiso POST_NOTIFICATIONS (Android 13+). */
        private const val REQ_NOTIF = 72

        /** Tiempo en background a partir del cual, al volver, se fuerza reconexión (por si la
         *  conexión quedó muerta sin RST). Un vistazo más corto no fuerza nada. */
        private const val UMBRAL_FORZAR_RECONEXION_MS = 3000L

        /** Ventana de un-solo-uso tras soltar un arrastre en Sel para aceptar EL OSC 52 de tmux (que
         *  llega un instante después por el round-trip). Se consume al primer OSC 52 aceptado. */
        private const val VENTANA_HANDSHAKE_MS = 1500L
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
        onHostKeyChanged = { vieja, nueva, redDistinta -> runOnUiThread { cambioLaClave(vieja, nueva, redDistinta) } },
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
        onAccesoVencido = { runOnUiThread { pintarBarra() } },
        onAvisoApp = { tipo, texto -> runOnUiThread { mostrarAvisoApp(tipo, texto) } },
    )

    // Re-enrolar de un toque: scanner de QR desde la terminal (mismo flujo que en hosts).
    private val qrScanner = registerForActivityResult(
        com.journeyapps.barcodescanner.ScanContract(),
    ) { result ->
        if (EnrolarTailscale.aplicar(this, result.contents)) {
            // Los toasts ("Re-vinculando…" + a qué tailnet te vinculaste, A4-1) los da
            // EnrolarTailscale.aplicar, común a los tres caminos de re-enrol. El estado del
            // nodo lo sigue el bus: la barra se repinta sola cuando cambie.
            pintarBarra()
            // onResume (al volver del scanner) ya reintenta; esto fuerza también las caídas.
            tabs.forzarReconexiones()
        }
    }

    @Volatile private var sesionPerdidaCount = 0

    /** Cuántas veces se avisó "sesión perdida". Para los tests (el texto en la terminal lo
     *  borra el redibujo de tmux al recrear la sesión, así que se verifica el callback). */
    @androidx.annotation.VisibleForTesting
    fun sesionPerdidaCountForTest(): Int = sesionPerdidaCount

    /** El estado real de conexión cambió: refrescar la barra y, si recién se estableció la
     *  conexión, disparar la demo de la terminal (antes se disparaba al activar la pestaña,
     *  aunque la auth hubiera fallado — mostraba "Host conectado" sobre una conexión muerta). */
    private fun alCambiarEstadoConexion() {
        // Una conexión REAL apaga el aviso de vencido (el de host-key y el de expiry quedan hasta
        // que los toques: no dependen de esta reconexión).
        if (tabs.sesionActiva?.estado == SshTerminalSession.Estado.CONECTADO &&
            avisoAppTipo == SshTerminalSession.AvisoApp.VENCIDO
        ) ocultarAvisoApp()
        pintarBarra()
        if (demoPendiente && tabs.sesionActiva?.estado == SshTerminalSession.Estado.CONECTADO) {
            demoPendiente = false
            terminalView.post { Tour.lanzar(this, "terminal", pasosDemo()) { mostrarTeclado() } }
        }
        // Despertar el motor de dictado al (re)conectar, y habilitar el mic recién cuando el
        // modelo esté cargado. Throttle de 60s: este callback dispara por CADA pestaña y por
        // cada reconexión, y despertarStt ya es idempotente (server vivo responde en ~1s).
        if (tabs.sesionActiva?.estado == SshTerminalSession.Estado.CONECTADO) {
            val ahora = android.os.SystemClock.elapsedRealtime()
            if (ahora - ultimoDespertarStt > 60_000) {
                ultimoDespertarStt = ahora
                thread { dictado.prepararStt() }
            }
        }
    }

    /**
     * Pinta la barra según el estado REAL de la pestaña activa. v1.32 ("el terminal es la
     * verdad; el nodo explica"): el color lo decide SOLO la conexión SSH — una terminal viva por
     * LAN con el nodo vencido es verde, porque anda. El nodo vencido aparece como CAUSA (sufijo
     * bajo caída) y como acción (↺ Reescanear QR), leído del bus, sin latches ni ventanas.
     */
    private fun pintarBarra() {
        val estado = tabs.sesionActiva?.estado
        val vencido = TailscaleBridge.accesoVencido()
        val (sufijo, color) = when (estado) {
            SshTerminalSession.Estado.CONECTADO -> "" to Paleta.ACCENT
            SshTerminalSession.Estado.RECONECTANDO -> " · reconectando…" to getColor(R.color.marvin_amber)
            SshTerminalSession.Estado.CAIDO ->
                (if (vencido) " · sin conexión — Tailscale vencido" else " · sin conexión") to Paleta.REC_FG
            else -> " · conectando…" to getColor(R.color.marvin_amber)   // CONECTANDO o null
        }
        barraLabel.text = "‹  $hostLabel$sufijo"
        barraLabel.setTextColor(color)
        if (vencido) {
            // El ↺ se ofrece siempre que el nodo esté vencido: en ámbar si la terminal anda igual
            // (LAN), en rojo si está caída (probablemente por eso).
            val colorRe = if (estado == SshTerminalSession.Estado.CONECTADO) Paleta.AMBER else Paleta.REC_FG
            barraReconectar.text = Iconos.etiqueta(this, Iconos.REESCANEAR, colorRe, 14f, "Reescanear QR")
            barraReconectar.contentDescription =
                "Reescanear el QR de Tailscale. Mantené apretado para pegar una auth key."
            barraReconectar.setTextColor(colorRe)
            barraReconectar.setOnClickListener { qrScanner.launch(EnrolarTailscale.opciones()) }
            // UX5-3: toque largo → pegar una auth key (si no estás en la PC para escanear el QR).
            barraReconectar.setOnLongClickListener {
                DialogoTailscale.mostrar(
                    this,
                    onKey = { k -> if (EnrolarTailscale.aplicar(this, k)) { pintarBarra(); tabs.forzarReconexiones() } },
                    onScan = { qrScanner.launch(EnrolarTailscale.opciones()) },
                )
                true
            }
            barraReconectar.visibility = View.VISIBLE
        } else {
            barraReconectar.setOnLongClickListener(null)
            barraReconectar.text = Iconos.etiqueta(this, Iconos.RECARGAR, Paleta.AMBER, 14f, "Reconectar")
            barraReconectar.contentDescription = "Reconectar"
            barraReconectar.setTextColor(Paleta.AMBER)
            barraReconectar.setOnClickListener { tabs.reconectarActiva() }
            barraReconectar.visibility =
                if (estado == SshTerminalSession.Estado.CAIDO) View.VISIBLE else View.GONE
        }
        // Con un botón de acción en la barra (↺ o ↻) el espacio es escaso: el logo
        // decorativo se lo cede al título, que si no quedaba partido en renglones.
        barraLogo.visibility =
            if (barraReconectar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    /**
     * La clave del host cambió: la conexión YA fue rechazada; acá sólo se decide qué hacer.
     * A5-2 / UX5-4 (5ª pasada): la variante DURA la decide el pin (la tailnet cambió desde que se
     * fijó la clave = escenario de key ajena), no un timer de 60 s que un atacante puede esperar;
     * ninguna rama es "aceptá y listo": para confiar hay que tipear el final de la huella nueva, y
     * el diálogo dice CÓMO verificarla en la PC (antes eso vivía sólo en la skill).
     */
    private fun cambioLaClave(vieja: String, nueva: String, redDistinta: Boolean) {
        if (isFinishing || isDestroyed || dialogoClaveVisible) return
        dialogoClaveVisible = true
        val explicacion = if (redDistinta) {
            "Además, tu nodo Tailscale está hoy en una tailnet DISTINTA de la que tenía cuando se " +
                "fijó esta clave. Ese es exactamente el escenario de una key ajena: te vinculó a otra " +
                "red y este server puede ser un impostor. No confíes salvo que verifiques la huella."
        } else {
            "Si reinstalaste el server o lo recreaste, es esperable. Si no, alguien puede estar " +
                "interceptando la conexión. Verificá la huella en la PC antes de confiar."
        }
        val mono = resources.getFont(R.font.mononoki)
        val p = (20 * resources.displayMetrics.density).toInt()
        val cuerpo = android.widget.TextView(this).apply {
            text = "El servidor $host:$port presentó una clave distinta a la que teníamos.\n\n$explicacion\n\n" +
                "Para verificar, en la PC:"
        }
        val comando = android.widget.TextView(this).apply {
            text = "ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub"
            typeface = mono; setTextIsSelectable(true); setPadding(0, p / 4, 0, p / 2)
        }
        val huellas = android.widget.TextView(this).apply {
            text = "Antes: $vieja\nAhora: $nueva"
            typeface = mono; setTextIsSelectable(true)
        }
        val confirmacion = android.widget.EditText(this).apply {
            hint = "Para confiar, tipeá los últimos 4 caracteres de la huella nueva"
            isSingleLine = true
        }
        val vista = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(p, p / 2, p, 0)
            addView(cuerpo); addView(comando); addView(huellas); addView(confirmacion)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("La clave del host cambió")
            .setView(vista)
            .setNegativeButton("Cancelar") { _, _ -> dialogoClaveVisible = false }
            .setNeutralButton("Copiar huella", null)
            .setPositiveButton("Confiar en la nueva", null)
            .setCancelable(false)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("huella del host", nueva))
                Toast.makeText(this, "Huella nueva copiada", Toast.LENGTH_SHORT).show()
            }
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!confirmaHuella(confirmacion.text.toString(), nueva)) {
                    Toast.makeText(this, "No coincide con el final de la huella nueva", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                Diagnostico.registrar(
                    Diagnostico.Nivel.AVISO, "clave-host",
                    "$host:$port — el usuario confió en la clave nueva ($nueva)" +
                        if (redDistinta) " con la tailnet CAMBIADA" else "",
                )
                HostKeys.forget(this, host, port)
                dialogoClaveVisible = false
                dlg.dismiss()
                // Todas, no sólo la activa: confiar en la clave nueva vale para el host
                // entero, y las pestañas que ya habían fallado quedaban muertas.
                tabs.reconectarTodas()
            }
        }
        dlg.show()
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
            text = Iconos.etiqueta(this@MainActivity, Iconos.COPIAR, null, 14f, "Copiar comando (pegá en la PC)")
            isAllCaps = false
            setOnClickListener { copiarAlPortapapeles(comandoAutorizar(pub)) }
        })
        box.addView(Button(this).apply {
            text = Iconos.etiqueta(this@MainActivity, Iconos.COPIAR, null, 14f, "Copiar solo la clave")
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
        contentDescription = "Volver a la lista de hosts. Mantené apretado para el diagnóstico de conexión."
        setOnClickListener { finish() }
        // F8: toque largo → pantalla de diagnóstico (timeline de conexión). Es una función de
        // troubleshooting, por eso va escondida en un long-press y no ocupa lugar en la barra.
        setOnLongClickListener {
            startActivity(Intent(this@MainActivity, DiagnosticoActivity::class.java))
            true
        }
        barraLabel = TextView(this@MainActivity).apply {
            text = "‹  $hostLabel"
            typeface = monoFont
            setTextColor(Paleta.ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            // Nunca partir el título en renglones (visto en vivo: "MiPC" quedaba "Mi/PC"
            // cuando el botón de acción achica el espacio); si no entra, ellipsis.
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        addView(barraLabel, LinearLayout.LayoutParams(0, Paleta.WRAP, 1f))
        // "Reconectar" aparece SOLO cuando la conexión cayó (auth/host-key): antes no había
        // un botón claro para reintentar — el que existía ("Reenganchar") reattachea tmux,
        // no reconecta SSH. Su propio click lo consume; el resto de la barra sigue volviendo.
        barraReconectar = TextView(this@MainActivity).apply {
            text = Iconos.etiqueta(this@MainActivity, Iconos.RECARGAR, Paleta.AMBER, 14f, "Reconectar")
            typeface = monoFont
            setTextColor(getColor(R.color.marvin_amber))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(10), 0, dp(10), 0)
            visibility = View.GONE
            setOnClickListener { tabs.reconectarActiva() }
        }
        addView(barraReconectar, LinearLayout.LayoutParams(Paleta.WRAP, Paleta.WRAP))
        // Acceso VISIBLE al diagnóstico (además del long-press de la barra): un ⓘ chico, siempre
        // presente, para que se encuentre justo cuando cae la conexión. Su propio click lo consume,
        // así que no dispara el finish() del contenedor.
        addView(TextView(this@MainActivity).apply {
            text = Iconos.etiqueta(this@MainActivity, Iconos.INFO, Paleta.CHEV_FG, 18f, "")
            typeface = monoFont
            setTextColor(Paleta.CHEV_FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(10), 0, dp(10), 0)
            // Target táctil ≥44dp (antes ~21dp de alto): la zona tocable crece con min-size + gravity
            // centrado, sin agrandar el glifo. Un ⓘ chiquito es fácil de errar justo cuando cae la red.
            minWidth = dp(44)
            minHeight = dp(44)
            gravity = android.view.Gravity.CENTER
            contentDescription = "Diagnóstico de conexión"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, DiagnosticoActivity::class.java))
            }
        }, LinearLayout.LayoutParams(Paleta.WRAP, Paleta.WRAP))
        barraLogo = ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.marvin_isologo_bar)
            adjustViewBounds = true
        }
        addView(barraLogo, LinearLayout.LayoutParams(Paleta.WRAP, dp(22)))
    }

    /**
     * Banner nativo de avisos de la APP, bajo la barra (A5-1): host-key cambió, enrolamiento del
     * celu vencido, expiry del nodo de la PC. Antes iban al pty, donde un host (o un Claude
     * inyectado) los imita byte a byte y tmux los borra al redibujar. Se cierra al tocarlo.
     */
    @Volatile private var avisoAppTipo: SshTerminalSession.AvisoApp? = null

    private fun bannerDeAvisos(): TextView = TextView(this).apply {
        typeface = resources.getFont(R.font.ubuntu)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        visibility = View.GONE
        contentDescription = "Aviso de la app (tocá para cerrarlo)"
        setOnClickListener { ocultarAvisoApp() }
    }

    private fun mostrarAvisoApp(tipo: SshTerminalSession.AvisoApp, texto: String) {
        if (isFinishing || isDestroyed) return
        avisoAppTipo = tipo
        val (fg, bg) = when (tipo) {
            SshTerminalSession.AvisoApp.HOST_KEY -> Paleta.REC_FG to Paleta.CHEV_BG
            else -> getColor(R.color.marvin_amber) to Paleta.CHEV_BG
        }
        bannerApp.text = texto
        bannerApp.setTextColor(fg)
        bannerApp.setBackgroundColor(bg)
        bannerApp.visibility = View.VISIBLE
    }

    private fun ocultarAvisoApp() {
        avisoAppTipo = null
        bannerApp.visibility = View.GONE
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

    /** ¿La copia al portapapeles la iniciaste VOS? (política A + handshake de un solo uso)
     *  - `isSelectingText()`: hay una selección nativa (long-press) activa → la copia es tuya.
     *  - Handshake: soltaste un arrastre en modo Sel hace < [VENTANA_HANDSHAKE_MS] → aceptamos el
     *    PRIMER OSC 52 (el que emite tmux al soltar) y lo CONSUMIMOS (la ventana se cierra), así un
     *    host que spamea OSC 52 no se cuela ni ahora ni después.
     *  Ya NO se usa el toggle persistente `isSelectionDragMode()`: mientras estaba prendido, cualquier
     *  OSC 52 del host pasaba (era el bypass que reportó seguridad). Si es false → se bloquea. */
    private fun copiaIniciadaPorVos(): Boolean {
        if (terminalView.isSelectingText()) return true
        val soltado = selDragSoltadoEnMs
        if (soltado != 0L &&
            android.os.SystemClock.elapsedRealtime() - soltado < VENTANA_HANDSHAKE_MS
        ) {
            selDragSoltadoEnMs = 0L   // un solo uso: consumir la ventana
            return true
        }
        return false
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

    /** Baja el teclado del sistema (leer no lo necesita): recupera filas de terminal para el
     *  "modo lectura" cuando llega un bloque grande de salida. */
    private fun ocultarTeclado() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(terminalView.windowToken, 0)
    }

    private fun copiarAlPortapapeles(texto: String) {
        val cb = getSystemService(ClipboardManager::class.java)
        cb?.setPrimaryClip(ClipData.newPlainText("terminal", texto))
        Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Confirmación explícita del mismatch de host-key: lo tipeado tiene que ser el final (≥4 chars)
 * de la huella nueva. Sin esto "Confiar" era un toque reflejo (SEC5-5 / UX5-4).
 */
internal fun confirmaHuella(tipeado: String, huella: String): Boolean {
    val t = tipeado.trim()
    val h = huella.trim()
    return t.length >= 4 && h.length >= 4 && h.endsWith(t) && t.length <= h.length
}

