package com.remoteclaude.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.net.ConnectivityManager
import android.net.Network
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * M3: multi-tab. Una sola TerminalView que va intercambiando la sesión activa.
 * Cada tab = una SshTerminalSession con su propia sesión tmux persistente
 * (remoteclaude-N), así corren en paralelo y sobreviven independientes.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var terminalView: TerminalView

    private class Tab(val session: SshTerminalSession)
    private val tabs = mutableListOf<Tab>()
    private var activeIndex = 0
    private lateinit var keyPair: java.security.KeyPair
    private lateinit var control: RemoteControl
    private lateinit var tabBar: LinearLayout
    private val prefs by lazy { getSharedPreferences("remotemarvin", Context.MODE_PRIVATE) }

    // Host al que conectarse (viene de HostsActivity por Intent).
    private lateinit var host: String
    private var port = 22
    private lateinit var user: String
    private lateinit var hostId: String
    private lateinit var hostLabel: String
    private val monoFont by lazy { resources.getFont(R.font.mononoki) }
    private val tabsKey get() = "tabs_$hostId"
    private val activeKey get() = "active_$hostId"

    /**
     * Sesión del tab activo (la usan el teclado y los modificadores).
     *
     * Nullable a propósito: entre que se abre la pantalla y que la primera pestaña queda
     * creada hay una ventana (el alta hace I/O de red con timeout de 10s) en la que NO hay
     * sesión. Antes esto era `tabs[activeIndex]` y cualquier tecla en esa ventana tiraba
     * IndexOutOfBounds. Lo mismo al cerrar la última pestaña.
     */
    private val session get() = tabs.getOrNull(activeIndex)?.session

    private var fontSizePx = 0
    private var minFontPx = 0
    private var maxFontPx = 0

    private var ctrlActive = false
    private var altActive = false
    private var shiftActive = false
    private lateinit var ctrlButton: Button
    private lateinit var altButton: Button
    private lateinit var shiftButton: Button
    private lateinit var micButton: Button
    private lateinit var dictBubble: TextView
    private val recorder = WavRecorder()
    @Volatile private var transcribing = false
    private var live: LiveDictation? = null
    private var selectMode = false   // dedo = selección nativa de tmux (en vez de scroll)
    private lateinit var selButton: Button

    private var overflowShown = false
    private var keyboardUp = false   // teclado QWERTY visible -> ocultar la fila Shift
    private lateinit var rowTop: LinearLayout
    private lateinit var shiftRow: LinearLayout   // fila extra (debajo de las flechas)
    private lateinit var chevron: Button

    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        // OJO: sin Handler estas llegan en el hilo de ConnectivityManager. Se itera una
        // copia porque el hilo principal puede estar agregando/quitando pestañas.
        override fun onAvailable(network: Network) { tabs.toList().forEach { it.session.onNetworkAvailable() } }
        override fun onLost(network: Network) { tabs.toList().forEach { it.session.onNetworkLost() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Host elegido en HostsActivity (con defaults por compatibilidad).
        host = intent.getStringExtra("hostname") ?: "remoteclaude"
        port = intent.getIntExtra("port", 22)
        user = intent.getStringExtra("user") ?: "root"
        hostId = intent.getStringExtra("hostId") ?: "default"
        hostLabel = intent.getStringExtra("label") ?: host

        // Terminal con colores de marca: fondo petróleo, cursor verde, texto claro.
        // Se cambia el default del motor (sobrevive resets; no toca los colores ANSI).
        com.termux.terminal.TerminalColors.COLOR_SCHEME.mDefaultColors.let {
            it[com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND] = 0xFF0F232D.toInt()
            it[com.termux.terminal.TextStyle.COLOR_INDEX_FOREGROUND] = 0xFFE6ECE6.toInt()
            it[com.termux.terminal.TextStyle.COLOR_INDEX_CURSOR] = 0xFF71BF44.toInt()
        }

        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )

        fontSizePx = sp(15f)
        minFontPx = sp(8f)
        maxFontPx = sp(28f)

        terminalView = TerminalView(this, null)
        terminalView.setTerminalViewClient(viewClient)
        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true
        terminalView.setTextSize(fontSizePx)
        terminalView.setTypeface(Typeface.MONOSPACE)
        terminalView.setBackgroundColor(KEYPAD_BG)   // petróleo, evita flash negro

        keyPair = KeyStoreSsh.getOrCreateKeyPair()
        control = RemoteControl(this, host, port, user, keyPair)

        // burbuja del dictado en vivo (fase 2): parciales mientras sostenés 🎤
        dictBubble = TextView(this).apply {
            visibility = View.GONE
            setBackgroundColor(0xF20F232D.toInt())
            setTextColor(Color.parseColor("#A9CCE8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
            maxLines = 3
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(buildHostBar(), LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(buildTabBar(), LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(terminalView, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(dictBubble, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(buildKeypad(), LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(root)

        connectivity.registerDefaultNetworkCallback(networkCallback, android.os.Handler(mainLooper))
        watchKeyboard()

        restoreTabsOrNew()

        terminalView.requestFocus()
        terminalView.post { showKeyboard() }
    }

    override fun onDestroy() {
        try { connectivity.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        recorder.cancel()
        live?.cancel(); live = null
        tabs.forEach { it.session.finishIfRunning() }
        super.onDestroy()
    }

    /** Sesión activa. La usan los tests instrumentados para escribir en la terminal. */
    @androidx.annotation.VisibleForTesting
    fun currentSessionForTest(): SshTerminalSession? = tabs.getOrNull(activeIndex)?.session

    /** Contenido visible de la terminal. Lo usan los tests instrumentados. */
    @androidx.annotation.VisibleForTesting
    fun screenText(): String =
        tabs.getOrNull(activeIndex)?.session?.emulator?.screen?.transcriptText.orEmpty()

    private fun showKeyboard() {
        terminalView.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Barra superior: muestra a qué host estás conectado y permite volver al menú. */
    private fun buildHostBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(CHEV_BG)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { finish() }   // volver al menú de hosts
        }
        bar.addView(TextView(this).apply {
            text = "‹  $hostLabel"
            typeface = monoFont
            setTextColor(ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.marvin_isologo_bar)
            adjustViewBounds = true
        }, LinearLayout.LayoutParams(WRAP, dp(22)))
        return bar
    }

    /** Detecta si el teclado QWERTY está visible (por el alto que tapa) para mostrar/ocultar ⇧Tab. */
    private fun watchKeyboard() {
        val decor = window.decorView
        decor.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect()
            decor.getWindowVisibleDisplayFrame(r)
            val up = decor.height - r.height() > decor.height * 0.15
            if (up != keyboardUp) {
                keyboardUp = up
                if (up) shiftActive = false   // se oculta la fila: no dejar Shift colgado
                populateShiftRow()
            }
        }
    }

    // --- Tabs -----------------------------------------------------------------

    private fun buildTabBar(): View {
        tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(KEYPAD_BG)
            addView(tabBar)
        }
    }

    private fun buildSession(name: String) =
        SshTerminalSession(
            this, host, port, user, keyPair, name, sessionClient,
            onAuthFailed = { runOnUiThread { onSessionAuthFailed() } },
            onHostKeyChanged = { old, new -> runOnUiThread { onHostKeyChanged(old, new) } },
        )

    /**
     * La clave del host cambió: la conexión ya fue rechazada. Se le muestra al usuario las
     * dos huellas y se le deja decidir. Sólo la terminal pregunta esto — los caminos de
     * fondo (documentos, dictado, visor) fallan sin preguntar, para que confiar en una
     * clave nueva sea siempre una decisión explícita.
     */
    private var hostKeyDialogShown = false

    private fun onHostKeyChanged(old: String, new: String) {
        if (isFinishing || isDestroyed || hostKeyDialogShown) return
        hostKeyDialogShown = true
        AlertDialog.Builder(this)
            .setTitle("La clave del host cambió")
            .setMessage(
                "El servidor $host:$port presentó una clave distinta a la que teníamos.\n\n" +
                    "Antes: $old\nAhora: $new\n\n" +
                    "Si reinstalaste el server o lo recreaste, es esperable. Si no, alguien " +
                    "puede estar interceptando la conexión."
            )
            .setNegativeButton("Cancelar") { _, _ -> hostKeyDialogShown = false }
            .setPositiveButton("Confiar en la nueva") { _, _ ->
                HostKeys.forget(this, host, port)
                hostKeyDialogShown = false
                reconnectActiveTab()
            }
            .setCancelable(false)
            .show()
    }

    private fun openTab(name: String) {
        tabs.add(Tab(buildSession(name)))
        switchTo(tabs.size - 1)
    }

    /** Reintentar la pestaña activa con una sesión nueva (tras autorizar la clave). */
    private fun reconnectActiveTab() {
        val idx = activeIndex
        val name = tabs[idx].session.tmuxSession
        tabs[idx].session.finishIfRunning()
        tabs[idx] = Tab(buildSession(name))
        switchTo(idx)
    }

    // La auth SSH falló (clave no autorizada): AUTO-ENROLAMIENTO. Pedir la contraseña de
    // enrolamiento una vez; la app sube sola su clave y reconecta (estilo ssh-copy-id).
    @Volatile private var authDialogShown = false
    /**
     * La clave de la app no está autorizada en el host.
     *
     * Antes esto pedía una "contraseña de enrolamiento" y la mandaba por SSH: la mitad
     * server de ese flujo se borró del repo hace rato, así que no podía funcionar — y
     * encima entrenaba a tipear una contraseña contra un host que ni verificábamos.
     * Ahora se muestra la clave pública y qué hacer con ella.
     */
    private fun onSessionAuthFailed() {
        if (authDialogShown) return   // varias pestañas fallan a la vez: un solo diálogo
        authDialogShown = true
        val pub = KeyStoreSsh.openSshPublicKey("remoteclaude-app")
        AlertDialog.Builder(this)
            .setTitle("Falta autorizar este dispositivo")
            .setMessage(
                "El host $user@$host:$port rechazó la clave de la app.\n\n" +
                    "Agregala a ~/.ssh/authorized_keys en el host:\n\n$pub"
            )
            .setNeutralButton("Copiar clave") { _, _ ->
                copyToClipboard(pub)
            }
            .setPositiveButton("Reintentar") { _, _ ->
                authDialogShown = false
                reconnectActiveTab()
            }
            .setNegativeButton("Cerrar") { _, _ -> authDialogShown = false }
            .setOnCancelListener { authDialogShown = false }
            .show()
    }

    /** Nuevo tab con el "term N" libre más bajo (mira abiertas + detacheadas). */
    private fun newTab() {
        thread {
            val used = tabs.map { it.session.tmuxSession }.toMutableSet()
            used.addAll(control.listSessions())
            var k = 1
            while ("term $k" in used) k++
            runOnUiThread { openTab("term $k") }
        }
    }

    private fun reattach(name: String) = openTab(name)

    /** Al abrir la app: reengancha las pestañas que estaban abiertas, o crea una nueva. */
    private fun restoreTabsOrNew() {
        val saved = prefs.getString(tabsKey, "").orEmpty().split("\n").filter { it.isNotBlank() }
        if (saved.isEmpty()) {
            newTab()
        } else {
            // Leer el tab activo ANTES de abrir: cada openTab() -> switchTo() -> refreshTabBar()
            // -> saveTabs() pisa activeKey con el índice del último abierto. Si lo leyéramos
            // después, siempre restauraría a la última pestaña.
            val wantActive = prefs.getInt(activeKey, 0)
            saved.forEach { openTab(it) }   // tmux -A reengancha (o recrea si fue matada)
            switchTo(wantActive.coerceIn(0, tabs.size - 1))
        }
    }

    /** Persiste los nombres de las pestañas abiertas y cuál está activa. */
    private fun saveTabs() {
        prefs.edit()
            .putString(tabsKey, tabs.joinToString("\n") { it.session.tmuxSession })
            .putInt(activeKey, activeIndex)
            .apply()
    }

    private fun switchTo(index: Int) {
        if (index !in tabs.indices) return
        activeIndex = index
        terminalView.attachSession(tabs[index].session)
        terminalView.onScreenUpdated()
        refreshTabBar()
        terminalView.requestFocus()
    }

    // ✕: preguntar si matar la sesión tmux o solo cerrar la pestaña (dejarla viva).
    private fun onCloseTabClicked(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        AlertDialog.Builder(this)
            .setTitle("Cerrar ${tab.session.tmuxSession}")
            .setItems(arrayOf("Matar la sesión", "Dejar viva (solo cerrar)", "Cancelar")) { _, which ->
                when (which) {
                    0 -> { val name = tab.session.tmuxSession; thread { control.killSession(name) }; closeTabKeep(index) }
                    1 -> closeTabKeep(index)
                }
            }
            .show()
    }

    private fun closeTabKeep(index: Int) {
        if (index !in tabs.indices) return
        tabs.removeAt(index).session.finishIfRunning()
        if (tabs.isEmpty()) {
            // refrescar ANTES de volver: si no, queda en pantalla el chip de la pestaña
            // cerrada y las prefs siguen listándola (al reabrir resucitaba).
            activeIndex = 0
            refreshTabBar()
            newTab()
            return
        }
        // mantener la pestaña en la que estabas: si cerraste una anterior, su índice bajó.
        val newActive = when {
            index < activeIndex -> activeIndex - 1
            else -> activeIndex.coerceAtMost(tabs.size - 1)
        }
        switchTo(newActive)
    }

    /** Long-press en una pestaña: renombrarla (renombra también la sesión tmux). */
    private fun showRenameDialog(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        val input = EditText(this).apply {
            setText(tab.session.tmuxSession)
            setSelection(text.length)
            setSingleLine()
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("Renombrar pestaña")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val newName = TmuxName.sanitize(input.text.toString())
                val old = tab.session.tmuxSession
                if (newName == old) return@setPositiveButton
                if (tabs.any { it.session.tmuxSession == newName }) {
                    Toast.makeText(this, "Ya hay una pestaña con ese nombre", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // Renombrar en el host PRIMERO: si falla (host caído, nombre duplicado) y
                // hubiéramos persistido igual, al reconectar `tmux new -A` abriría una
                // sesión NUEVA vacía y la sesión real quedaría huérfana e inalcanzable.
                thread {
                    val ok = control.renameSession(old, newName)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (ok) {
                            tab.session.tmuxSession = newName
                            refreshTabBar()
                        } else {
                            Toast.makeText(this, "No pude renombrar en el host", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ⟳: reenganchar una sesión tmux viva (no abierta), con preview de la última línea.
    private fun showReattachMenu() {
        thread {
            val open = tabs.map { it.session.tmuxSession }.toSet()
            val items = control.sessionsWithLastLine().filter { it.first !in open }
            runOnUiThread {
                if (items.isEmpty()) {
                    Toast.makeText(this, "No hay sesiones detacheadas", Toast.LENGTH_SHORT).show()
                } else {
                    val adapter = object : ArrayAdapter<Pair<String, String>>(this, 0, items) {
                        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                            val (name, last) = getItem(position)!!
                            return LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(dp(20), dp(12), dp(20), dp(12))
                                addView(TextView(this@MainActivity).apply {
                                    text = name
                                    setTextColor(KEY_FG)
                                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                                })
                                if (last.isNotEmpty()) addView(TextView(this@MainActivity).apply {
                                    text = last
                                    setTextColor(CHEV_FG)
                                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                                    maxLines = 1
                                    ellipsize = android.text.TextUtils.TruncateAt.END
                                })
                            }
                        }
                    }
                    AlertDialog.Builder(this)
                        .setTitle("Reenganchar sesión")
                        .setAdapter(adapter) { _, i -> reattach(items[i].first) }
                        .show()
                }
            }
        }
    }

    private fun refreshTabBar() {
        tabBar.removeAllViews()
        tabs.forEachIndexed { i, tab ->
            val active = i == activeIndex
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(if (active) TAB_ACTIVE_BG else Color.TRANSPARENT)
                setPadding(dp(12), dp(7), dp(8), dp(7))
            }
            chip.addView(TextView(this).apply {
                text = tab.session.tmuxSession
                typeface = monoFont
                setTextColor(if (active) ACCENT else CHEV_FG)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setOnClickListener { switchTo(i) }
                setOnLongClickListener { showRenameDialog(i); true }  // renombrar
            })
            chip.addView(TextView(this).apply {
                text = "  ✕"
                setTextColor(CHEV_FG)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(4), 0, dp(4), 0)
                setOnClickListener { onCloseTabClicked(i) }
            })
            tabBar.addView(chip)
        }
        tabBar.addView(TextView(this).apply {
            text = "  +  "
            setTextColor(KEY_FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { newTab() }
        })
        tabBar.addView(TextView(this).apply {
            text = "⟳"
            setTextColor(CHEV_FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(8), dp(4), dp(12), dp(4))
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { showReattachMenu() }  // sesiones detacheadas
        })
        tabBar.addView(TextView(this).apply {
            text = "🖥"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(6), dp(4), dp(8), dp(4))
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { openDisplay() }  // visor noVNC del navegador headed
        })
        tabBar.addView(TextView(this).apply {
            text = "📄"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(6), dp(4), dp(10), dp(4))
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { openDocs() }  // visor de documentos compartidos
        })
        tabBar.addView(TextView(this).apply {
            text = "🔑"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(6), dp(4), dp(12), dp(4))
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { showPublicKeyDialog() }  // clave pública de la app
        })
        saveTabs()  // persistir el estado de las pestañas
    }

    /** Abre el visor noVNC del navegador headed (M6), para ESTE host. */
    private fun openDisplay() {
        startActivity(android.content.Intent(this, DisplayActivity::class.java).apply {
            putExtra("hostname", host)
            putExtra("port", port)
            putExtra("user", user)
        })
    }

    /** Abre el visor de documentos compartidos (host:~/RemoteMarvinDocs). */
    private fun openDocs() {
        startActivity(android.content.Intent(this, DocsActivity::class.java).apply {
            putExtra("hostname", host); putExtra("port", port); putExtra("user", user)
        })
    }

    /** Muestra la clave pública (Keystore) para agregar a authorized_keys del gateway. */
    private fun showPublicKeyDialog() {
        val pub = KeyStoreSsh.openSshPublicKey("remoteclaude-app")
        AlertDialog.Builder(this)
            .setTitle("Clave pública (agregar a authorized_keys)")
            .setMessage(pub)
            .setPositiveButton("Copiar") { _, _ ->
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cb.setPrimaryClip(android.content.ClipData.newPlainText("clave pública", pub))
                Toast.makeText(this, "Clave copiada", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    // --- Teclado de teclas extra ---------------------------------------------

    private fun buildKeypad(): View {
        val pad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(KEYPAD_BG)
        }

        rowTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chevron = chevronButton()

        val arrows = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        arrows.addView(weightKey("←") { sendKey(KeyEvent.KEYCODE_DPAD_LEFT) })
        arrows.addView(weightKey("↓") { sendKey(KeyEvent.KEYCODE_DPAD_DOWN) })
        arrows.addView(weightKey("↑") { sendKey(KeyEvent.KEYCODE_DPAD_UP) })
        arrows.addView(weightKey("→") { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) })

        shiftRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        populateTopBlock()
        populateShiftRow()

        pad.addView(rowTop, LinearLayout.LayoutParams(MATCH, WRAP))
        pad.addView(arrows, LinearLayout.LayoutParams(MATCH, WRAP))
        pad.addView(shiftRow, LinearLayout.LayoutParams(MATCH, WRAP))
        return pad
    }

    /** Fila extra debajo de las flechas: la tecla Shift, sólo con el teclado QWERTY oculto.
     *  Shift es un modificador (como Ctrl/Alt): Shift + Tab manda Shift+Tab (cambia el modo
     *  de ejecución de Claude). Con el teclado arriba la fila queda vacía (no ocupa lugar). */
    private fun populateShiftRow() {
        shiftRow.removeAllViews()
        if (!keyboardUp) {
            shiftButton = weightKey("⇧ Shift") { toggleShift() }
            shiftButton.setTextColor(if (shiftActive) ACCENT else KEY_FG)
            shiftRow.addView(shiftButton)
            selButton = weightKey("⧉ Sel") { toggleSelectMode() }
            selButton.setTextColor(if (selectMode) ACCENT else KEY_FG)
            shiftRow.addView(selButton)
            micButton = weightKey("🎤 Dictar") {}
            micButton.setOnTouchListener { _, ev -> onMicTouch(ev) }
            shiftRow.addView(micButton)
        }
    }

    // --- Dictado por voz (push-to-talk): grabás mientras apretás 🎤; al soltar, el WAV
    // viaja por SSH al host (cliente marvin-stt -> faster-whisper) y el texto aparece
    // en el terminal como si lo hubieras tipeado (sin Enter: revisás y mandás).
    private fun onMicTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> startDictation()
            MotionEvent.ACTION_UP -> finishDictation()
            MotionEvent.ACTION_CANCEL -> {
                recorder.cancel()
                live?.cancel(); live = null
                hideDictBubble(); micIdle()
            }
        }
        return true
    }

    private fun startDictation() {
        if (transcribing) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        // sesión de streaming best-effort; se crea ANTES del recorder para que los
        // primeros chunks queden encolados hasta que el túnel/WS abra (no se pierden)
        val l = LiveDictation(this, host, port, user, keyPair) { txt ->
            runOnUiThread {
                if (dictBubble.visibility == View.VISIBLE) dictBubble.text = txt.takeLast(220)
            }
        }
        live = l
        if (recorder.start(onChunk = { chunk -> live?.feed(chunk) })) {
            micButton.text = "🎤 Grabando"
            micButton.setTextColor(0xFFE05555.toInt())
            thread {
                val ok = l.start()
                if (ok) {
                    runOnUiThread {
                        if (recorder.isRecording) {
                            dictBubble.text = "🎙 Escuchando…"
                            dictBubble.visibility = View.VISIBLE
                        }
                    }
                } else {
                    if (live === l) live = null
                    // sin server en vivo: fase 1 cubre este dictado; arrancarlo para el próximo
                    try { control.kickLiveStt() } catch (_: Exception) {}
                }
            }
        } else {
            live = null
            Toast.makeText(this, "No pude abrir el micrófono", Toast.LENGTH_SHORT).show()
        }
    }

    private fun finishDictation() {
        val l = live; live = null
        val wav = recorder.stop() ?: run { l?.cancel(); hideDictBubble(); micIdle(); return }
        // La sesión destino se fija ACÁ, no al terminar: el round-trip tarda segundos y si
        // mientras tanto cambiás de pestaña, el texto se escribía en la pestaña equivocada.
        val target = session ?: run { l?.cancel(); hideDictBubble(); micIdle(); return }
        transcribing = true
        micButton.text = "🎤 …"
        micButton.setTextColor(ACCENT)
        thread {
            var text: String? = null
            if (l != null && l.available) {
                text = try { l.stop() } catch (_: Exception) { null }
            }
            if (text.isNullOrBlank()) {
                l?.cancel()
                text = try { control.transcribe(wav) } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Dictado falló: ${e.message ?: "sin conexión"}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    null
                }
            }
            runOnUiThread {
                if (!text.isNullOrBlank()) {
                    val bytes = "$text ".toByteArray(Charsets.UTF_8)
                    target.write(bytes, 0, bytes.size)
                }
                hideDictBubble()
                micIdle()
                // dentro del post al main: si se soltaba en el worker, el main podía no ver
                // el cambio y el botón de dictado quedaba inutilizable el resto de la sesión.
                transcribing = false
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cb.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
        Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
    }

    private fun hideDictBubble() {
        dictBubble.visibility = View.GONE
        dictBubble.text = ""
    }

    private fun micIdle() {
        if (::micButton.isInitialized) {
            micButton.text = "🎤 Dictar"
            micButton.setTextColor(KEY_FG)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Listo: mantené 🎤 apretado para dictar", Toast.LENGTH_SHORT)
                .show()
        }
    }

    /** Alterna el modo selección: con el dedo arrastrás para seleccionar (selección nativa
     *  de tmux, que scrollea sola) y al soltar copia al portapapeles vía OSC 52. */
    private fun toggleSelectMode() {
        selectMode = !selectMode
        terminalView.setSelectionDragMode(selectMode)
        if (::selButton.isInitialized) selButton.setTextColor(if (selectMode) ACCENT else KEY_FG)
        Toast.makeText(
            this,
            if (selectMode) "Selección ON: arrastrá para marcar, soltá para copiar"
            else "Selección OFF (vuelve el scroll)",
            Toast.LENGTH_SHORT,
        ).show()
        terminalView.requestFocus()
    }

    private fun toggleOverflow() {
        overflowShown = !overflowShown
        chevron.text = if (overflowShown) "‹" else "›"
        populateTopBlock()
        terminalView.requestFocus()
    }

    private fun populateTopBlock() {
        rowTop.removeAllViews()
        if (!overflowShown) {
            rowTop.addView(weightKey("Esc") { sendKey(KeyEvent.KEYCODE_ESCAPE) })
            rowTop.addView(weightKey("Tab") { onTabKey() })
            ctrlButton = weightKey("Ctrl") { toggleCtrl() }
            altButton = weightKey("Alt") { toggleAlt() }
            rowTop.addView(ctrlButton)
            rowTop.addView(altButton)
            ctrlButton.setTextColor(if (ctrlActive) ACCENT else KEY_FG)
            altButton.setTextColor(if (altActive) ACCENT else KEY_FG)
        } else {
            rowTop.addView(weightKey("Home") { sendKey(KeyEvent.KEYCODE_MOVE_HOME) })
            rowTop.addView(weightKey("End") { sendKey(KeyEvent.KEYCODE_MOVE_END) })
            rowTop.addView(weightKey("PgUp") { sendKey(KeyEvent.KEYCODE_PAGE_UP) })
            rowTop.addView(weightKey("PgDn") { sendKey(KeyEvent.KEYCODE_PAGE_DOWN) })
        }
        rowTop.addView(chevron)
    }

    private fun chevronButton(): Button {
        return Button(this).apply {
            text = "›"
            isAllCaps = false
            setTextColor(CHEV_FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setBackgroundColor(CHEV_BG)
            minWidth = 0
            minHeight = 0
            setPadding(dp(4), dp(10), dp(4), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(34), MATCH)
            setOnClickListener {
                toggleOverflow()
                terminalView.requestFocus()
            }
        }
    }

    private fun keyButton(label: String, onTap: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            typeface = monoFont
            setTextColor(KEY_FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setBackgroundColor(Color.TRANSPARENT)
            minWidth = 0
            minHeight = 0
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnClickListener {
                onTap()
                terminalView.requestFocus()
            }
        }
    }

    private fun weightKey(label: String, onTap: () -> Unit): Button {
        return keyButton(label, onTap).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
    }

    private fun sendKey(keyCode: Int) = terminalView.handleKeyCode(keyCode, 0)

    /** Tab: con Shift activo manda Shift+Tab (back-tab) y suelta el Shift; si no, Tab normal. */
    private fun onTabKey() {
        if (shiftActive) {
            sendShiftTab()
            shiftActive = false
            if (::shiftButton.isInitialized) shiftButton.setTextColor(KEY_FG)
        } else {
            sendKey(KeyEvent.KEYCODE_TAB)
        }
    }

    private fun toggleShift() {
        shiftActive = !shiftActive
        shiftButton.setTextColor(if (shiftActive) ACCENT else KEY_FG)
    }

    /** Shift+Tab = back-tab (CSI Z): la secuencia que Claude lee para cambiar de modo. */
    private fun sendShiftTab() =
        session?.write(byteArrayOf(0x1b, '['.code.toByte(), 'Z'.code.toByte()), 0, 3)

    private fun toggleCtrl() {
        ctrlActive = !ctrlActive
        ctrlButton.setTextColor(if (ctrlActive) ACCENT else KEY_FG)
    }

    private fun toggleAlt() {
        altActive = !altActive
        altButton.setTextColor(if (altActive) ACCENT else KEY_FG)
    }

    private fun resetModifiers() {
        if (ctrlActive) toggleCtrl()
        if (altActive) toggleAlt()
    }

    // --- Clientes -------------------------------------------------------------

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            if (tabs.getOrNull(activeIndex)?.session === changedSession) terminalView.onScreenUpdated()
        }
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (text.isNullOrEmpty()) return
            // OSC 52: lo dispara el HOST, no vos. Para una selección de tmux es lo
            // esperado, pero el buffer admite 1 MB, así que un proceso remoto podría
            // escribirte el portapapeles del teléfono en silencio. Arriba de cierto
            // tamaño se pide confirmación.
            if (text.length > OSC52_CONFIRM_OVER) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("¿Copiar al portapapeles?")
                    .setMessage(
                        "El host quiere copiar ${text.length / 1024} KB al portapapeles " +
                            "del teléfono."
                    )
                    .setNegativeButton("Descartar", null)
                    .setPositiveButton("Copiar") { _, _ -> copyToClipboard(text) }
                    .show()
                return
            }
            copyToClipboard(text)
        }
        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = cb.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString()
            if (text.isNullOrEmpty()) return
            val bytes = text.toByteArray(Charsets.UTF_8)
            this@MainActivity.session?.write(bytes, 0, bytes.size)
        }
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            val newSize = (fontSizePx * scale).toInt().coerceIn(minFontPx, maxFontPx)
            if (newSize != fontSizePx) {
                fontSizePx = newSize
                terminalView.setTextSize(newSize)
            }
            return 1.0f
        }

        override fun onSingleTapUp(e: MotionEvent?) = showKeyboard()
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
        override fun onLongPress(event: MotionEvent?): Boolean = false
        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
            if (ctrlActive) {
                resetModifiers()
                val b = when (codePoint) {
                    in 'a'.code..'z'.code -> codePoint - 'a'.code + 1
                    in 'A'.code..'Z'.code -> codePoint - 'A'.code + 1
                    ' '.code, '@'.code -> 0
                    '['.code -> 27
                    '\\'.code -> 28
                    ']'.code -> 29
                    else -> -1
                }
                if (b >= 0) {
                    this@MainActivity.session?.write(byteArrayOf(b.toByte()), 0, 1)
                    return true
                }
            }
            if (altActive) {
                resetModifiers()
                this@MainActivity.session?.write(byteArrayOf(27), 0, 1)
            }
            return false
        }

        override fun onEmulatorSet() {}
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    // --- utils ----------------------------------------------------------------
    private fun sp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics).toInt()
    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        // Paleta CTR de Marvin
        private val ACCENT = Color.parseColor("#71BF44")        // verde CTR
        private val KEY_FG = Color.parseColor("#F2F2F2")        // gris claro
        private const val REQ_MIC = 71                          // permiso RECORD_AUDIO
        private const val OSC52_CONFIRM_OVER = 100_000          // OSC 52: pedir confirmación
        private val KEYPAD_BG = Color.parseColor("#0F232D")     // verde petróleo
        private val CHEV_FG = Color.parseColor("#5E8B7E")       // verde alt apagado
        private val CHEV_BG = Color.parseColor("#0A1A20")       // petróleo más oscuro
        private val TAB_ACTIVE_BG = Color.parseColor("#16323D") // panel
    }
}
