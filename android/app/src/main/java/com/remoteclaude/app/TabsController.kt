package com.remoteclaude.app

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * Las pestañas y su barra. Cada una es una [SshTerminalSession] con su propia sesión tmux
 * persistente, así corren en paralelo en el host y sobreviven a que se cierre la app.
 *
 * Todo lo delicado de acá viene de esa persistencia: los nombres tienen que no pisar sesiones
 * que ya existen en el host, y el estado (qué pestañas y cuál activa) se guarda en prefs para
 * poder reenganchar al volver.
 */
class TabsController(
    private val act: AppCompatActivity,
    private val prefs: SharedPreferences,
    hostId: String,
    private val control: RemoteControl,
    private val mono: Typeface,
    private val crearSesion: (String) -> SshTerminalSession,
    private val alActivar: (SshTerminalSession) -> Unit,
    private val acciones: Acciones,
) {
    /** Los botones de la barra que no son pestañas: abren otras pantallas. */
    interface Acciones {
        fun abrirVisor()
        fun abrirDocumentos()
        fun mostrarClavePublica()
    }

    private class Tab(val session: SshTerminalSession)

    private val tabs = mutableListOf<Tab>()
    private var activo = 0
    private val claveTabs = "tabs_$hostId"
    private val claveActivo = "active_$hostId"

    // CENTER_VERTICAL explícito: sin gravedad, un LinearLayout horizontal alinea los hijos
    // ARRIBA, y como el chip de la pestaña es el más alto de la fila, el "+" y los íconos
    // quedaban flotando hacia el tope (se notó recién con los vectores, pero el texto de
    // antes también estaba alto).
    private val barra = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** La vista de la barra, para colgarla del layout. */
    val vista: View = HorizontalScrollView(act).apply {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(Paleta.KEYPAD_BG)
        addView(barra)
    }

    /**
     * Sesión de la pestaña activa, o null.
     *
     * Nullable a propósito: entre que se abre la pantalla y que la primera pestaña queda
     * creada hay una ventana (el alta hace I/O de red con timeout de 10s) sin sesión. Cuando
     * esto era `tabs[activo]`, cualquier tecla en esa ventana tiraba IndexOutOfBounds. Lo
     * mismo al cerrar la última.
     */
    val sesionActiva: SshTerminalSession? get() = tabs.getOrNull(activo)?.session

    // --- Blancos para la demo de primer uso --------------------------------------------
    // refrescarBarra() reconstruye todo, así que se buscan al momento, nunca se guardan.

    /** El chip de la pestaña activa, o null si la barra todavía no se pobló. */
    fun chipActivo(): View? = barra.getChildAt(activo)

    /** Un botón de la barra por su contentDescription (sobrevive al rebuild). */
    fun botonBarra(descripcion: String): View? {
        for (i in 0 until barra.childCount) {
            val v = barra.getChildAt(i)
            if (v.contentDescription == descripcion) return v
        }
        return null
    }

    /** Deja visible el final de la barra (donde viven los botones de acción). */
    fun scrollAlFinal() = (vista as HorizontalScrollView).fullScroll(View.FOCUS_RIGHT)

    val nombres: List<String> get() = tabs.map { it.session.tmuxSession }

    /** Al abrir: reengancha las pestañas que estaban abiertas, o crea una nueva. */
    fun restaurarOCrear() {
        val guardadas = TabPlan.parseSaved(prefs.getString(claveTabs, ""))
        if (guardadas.isEmpty()) {
            nueva()
            return
        }
        // Leer la activa ANTES de abrir: cada abrir() -> activar() -> refrescarBarra() ->
        // guardar() pisa claveActivo con el índice de la última abierta. Leyéndola después,
        // siempre restauraría a la última.
        val quiero = prefs.getInt(claveActivo, 0)
        guardadas.forEach { abrir(it) }   // tmux -A reengancha (o recrea si fue matada)
        activar(quiero.coerceIn(0, tabs.size - 1))
    }

    /** Pestaña nueva con el "term N" libre más bajo (mira abiertas y detacheadas). */
    fun nueva() {
        thread {
            val usados = tabs.map { it.session.tmuxSession }.toMutableSet()
            usados.addAll(control.listSessions())
            val nombre = TabPlan.nextFreeName(usados)
            act.runOnUiThread { if (!act.isFinishing && !act.isDestroyed) abrir(nombre) }
        }
    }

    /** Reintenta la pestaña activa con una sesión nueva (tras autorizar una clave). */
    fun reconectarActiva() {
        val i = activo
        val tab = tabs.getOrNull(i) ?: return
        val nombre = tab.session.tmuxSession
        tab.session.finishIfRunning()
        tabs[i] = Tab(crearSesion(nombre))
        activar(i)
    }

    /**
     * Recrea TODAS las sesiones, no sólo la activa. Es lo que corresponde tras autorizar
     * la clave o confiar en una host key nueva: esas decisiones valen para el host entero,
     * pero cada pestaña que ya había fallado quedó con su loop de conexión terminado
     * (`userClosed`) y no revive sola — el usuario la tocaba y la encontraba muerta.
     * Recrear el objeto alcanza: la conexión real es perezosa (se abre al engancharse la
     * vista), así que las de fondo no cuestan nada hasta que las toques.
     */
    fun reconectarTodas() {
        tabs.indices.forEach { i ->
            val nombre = tabs[i].session.tmuxSession
            tabs[i].session.finishIfRunning()
            tabs[i] = Tab(crearSesion(nombre))
        }
        activar(activo)
    }

    fun cerrarTodas() = tabs.forEach { it.session.finishIfRunning() }

    /** Avisa a todas las sesiones de un cambio de red. Itera una copia: estas llegan desde
     *  el callback de conectividad mientras el hilo principal puede estar tocando la lista. */
    fun avisarRed(disponible: Boolean) =
        tabs.toList().forEach { if (disponible) it.session.onNetworkAvailable() else it.session.onNetworkLost() }

    private fun abrir(nombre: String) {
        tabs.add(Tab(crearSesion(nombre)))
        activar(tabs.size - 1)
    }

    private fun activar(indice: Int) {
        if (indice !in tabs.indices) return
        activo = indice
        alActivar(tabs[indice].session)
        refrescarBarra()
    }

    private fun cerrar(indice: Int) {
        if (indice !in tabs.indices) return
        tabs.removeAt(indice).session.finishIfRunning()
        if (tabs.isEmpty()) {
            // Refrescar ANTES de crear la nueva: si no, queda en pantalla el chip de la
            // cerrada y las prefs la siguen listando (al reabrir, resucitaba).
            activo = 0
            refrescarBarra()
            nueva()
            return
        }
        activar(TabPlan.activeAfterClose(cerrada = indice, activa = activo, quedan = tabs.size))
    }

    private fun guardar() {
        prefs.edit()
            .putString(claveTabs, tabs.joinToString("\n") { it.session.tmuxSession })
            .putInt(claveActivo, activo)
            .apply()
    }

    // ✕: matar la sesión tmux o sólo cerrar la pestaña y dejarla viva.
    private fun preguntarAlCerrar(indice: Int) {
        val tab = tabs.getOrNull(indice) ?: return
        AlertDialog.Builder(act)
            .setTitle("Cerrar ${tab.session.tmuxSession}")
            .setItems(arrayOf("Matar la sesión", "Dejar viva (solo cerrar)", "Cancelar")) { _, cual ->
                when (cual) {
                    0 -> {
                        val nombre = tab.session.tmuxSession
                        thread { control.killSession(nombre) }
                        cerrar(indice)
                    }
                    1 -> cerrar(indice)
                }
            }
            .show()
    }

    /** Long-press en una pestaña: renombrarla (renombra también la sesión tmux). */
    private fun preguntarNombre(indice: Int) {
        val tab = tabs.getOrNull(indice) ?: return
        val input = EditText(act).apply {
            setText(tab.session.tmuxSession)
            setSelection(text.length)
            setSingleLine()
            setPadding(act.dp(20), act.dp(8), act.dp(20), act.dp(8))
        }
        AlertDialog.Builder(act)
            .setTitle("Renombrar pestaña")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val nuevo = TmuxName.sanitize(input.text.toString())
                val viejo = tab.session.tmuxSession
                if (nuevo == viejo) return@setPositiveButton
                if (tabs.any { it.session.tmuxSession == nuevo }) {
                    Toast.makeText(act, "Ya hay una pestaña con ese nombre", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // Renombrar en el host PRIMERO: si falla (host caído, nombre duplicado) y
                // hubiéramos persistido igual, al reconectar `tmux new -A` abriría una sesión
                // NUEVA vacía y la real quedaría huérfana e inalcanzable.
                thread {
                    val ok = control.renameSession(viejo, nuevo)
                    act.runOnUiThread {
                        if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                        if (ok) {
                            tab.session.tmuxSession = nuevo
                            refrescarBarra()
                        } else {
                            Toast.makeText(act, "No pude renombrar en el host", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ⟳: reenganchar una sesión tmux viva que no esté abierta, con preview de su última línea.
    private fun menuReenganche() {
        thread {
            val abiertas = tabs.map { it.session.tmuxSession }.toSet()
            // Distingue "no me pude conectar" de "no hay sesiones": antes ambos caían en el
            // mismo Toast "No hay sesiones detacheadas" (sessionsWithLastLine se tragaba el
            // error de red). Ahora lanza y acá se muestra el motivo real.
            var motivo: String? = null
            val items = try {
                control.sessionsWithLastLine().filter { it.first !in abiertas }
            } catch (e: Exception) {
                motivo = e.message ?: "no pude conectarme al host"
                emptyList()
            }
            act.runOnUiThread {
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                if (motivo != null) {
                    Toast.makeText(act, "No pude consultar el host: $motivo", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if (items.isEmpty()) {
                    Toast.makeText(act, "No hay sesiones detacheadas", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val adapter = object : ArrayAdapter<Pair<String, String>>(act, 0, items) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val (nombre, ultima) = getItem(position)!!
                        return LinearLayout(act).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(act.dp(20), act.dp(12), act.dp(20), act.dp(12))
                            addView(TextView(act).apply {
                                text = nombre
                                setTextColor(Paleta.KEY_FG)
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                            })
                            if (ultima.isNotEmpty()) addView(TextView(act).apply {
                                text = ultima
                                typeface = act.fuenteDetalle()
                                setTextColor(Paleta.CHEV_FG)
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                            })
                        }
                    }
                }
                AlertDialog.Builder(act)
                    .setTitle("Reenganchar sesión")
                    .setAdapter(adapter) { _, i -> abrir(items[i].first) }
                    .show()
            }
        }
    }

    private fun refrescarBarra() {
        barra.removeAllViews()
        tabs.forEachIndexed { i, tab ->
            val esActiva = i == activo
            val chip = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(if (esActiva) Paleta.TAB_ACTIVE_BG else Color.TRANSPARENT)
                setPadding(act.dp(12), act.dp(7), act.dp(8), act.dp(7))
            }
            chip.addView(TextView(act).apply {
                text = tab.session.tmuxSession
                typeface = mono
                setTextColor(if (esActiva) Paleta.ACCENT else Paleta.CHEV_FG)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setOnClickListener { activar(i) }
                setOnLongClickListener { preguntarNombre(i); true }
            })
            chip.addView(ImageView(act).apply {
                setImageDrawable(Iconos.drawable(act, Iconos.CERRAR, Paleta.CHEV_FG, 13f))
                contentDescription = "Cerrar ${tab.session.tmuxSession}"
                setPadding(act.dp(8), act.dp(2), act.dp(4), 0)
                setOnClickListener { preguntarAlCerrar(i) }
            })
            barra.addView(chip)
        }
        barra.addView(boton("  +  ", "Pestaña nueva", 17f, Paleta.KEY_FG, act.dp(10)) { nueva() })
        barra.addView(botonIcono(Iconos.REENGANCHAR, "Reenganchar una sesión", act.dp(8)) { menuReenganche() })
        barra.addView(botonIcono(Iconos.VISOR, "Ver el escritorio del host", act.dp(6)) { acciones.abrirVisor() })
        barra.addView(botonIcono(Iconos.DOCS, "Documentos compartidos", act.dp(6)) { acciones.abrirDocumentos() })
        barra.addView(botonIcono(Iconos.CLAVE, "Clave pública de la app", act.dp(6)) { acciones.mostrarClavePublica() })
        guardar()
    }

    /** Botón de la barra que es SOLO un ícono: vector tintado, mismo alto que los de texto. */
    private fun botonIcono(iconRes: Int, descripcion: String, padH: Int, alTocar: () -> Unit) =
        ImageView(act).apply {
            setImageDrawable(Iconos.drawable(act, iconRes, null, 19f))
            contentDescription = descripcion
            setPadding(padH, act.dp(6), padH, act.dp(6))
            setOnClickListener { alTocar() }
        }

    private fun boton(
        etiqueta: String,
        descripcion: String,
        sp: Float,
        color: Int?,
        padH: Int,
        alTocar: () -> Unit,
    ) = TextView(act).apply {
        text = etiqueta
        contentDescription = descripcion   // un ícono solo es ilegible para TalkBack
        color?.let { setTextColor(it) }
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setPadding(padH, act.dp(4), padH, act.dp(4))
        gravity = Gravity.CENTER_VERTICAL
        setOnClickListener { alTocar() }
    }
}
