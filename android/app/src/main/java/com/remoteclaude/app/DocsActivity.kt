package com.remoteclaude.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Lista los documentos que Claude comparte en el host (~/RemoteMarvinDocs, vía
 * `marvin-share`). Tocar uno lo abre en DocViewerActivity (visor nativo). Los lee por
 * SSH (RemoteControl), que ya pasa por el túnel del Tailscale embebido.
 */
class DocsActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var botonRecargar: TextView
    private lateinit var botonOrden: TextView
    private lateinit var botonSubir: TextView
    private lateinit var control: RemoteControl
    private val prefs by lazy { getSharedPreferences("remotemarvin", Context.MODE_PRIVATE) }

    private var criterio = DocsPlan.Criterio.MODIFICACION
    private var ordenAsc = false
    private var ultimosDocs: List<RemoteControl.Doc> = emptyList()

    // Selector de archivos de Android (SAF): sin permisos de storage, multi-selección.
    private val selector = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> if (uris.isNotEmpty()) subir(uris) }
    private val titleFont by lazy { resources.getFont(R.font.osifont) }
    private val monoFont by lazy { resources.getFont(R.font.mononoki) }
    private val bodyFont by lazy { resources.getFont(R.font.ubuntu) }

    private lateinit var host: String
    private var port = 22
    private lateinit var user: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = getColor(R.color.marvin_petrol)

        host = intent.getStringExtra("hostname") ?: "remoteclaude"
        port = intent.getIntExtra("port", 22)
        user = intent.getStringExtra("user") ?: "root"
        control = RemoteControl(this, host, port, user, KeyStoreSsh.getOrCreateKeyPair())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.marvin_petrol))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(4))
        }
        header.addView(TextView(this).apply {
            text = "‹ Volver"
            typeface = monoFont
            setTextColor(getColor(R.color.marvin_green))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(4), dp(8), dp(16), dp(8))
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "Documentos"
            typeface = titleFont
            setTextColor(getColor(R.color.marvin_green))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        botonOrden = TextView(this).apply {
            text = "⇅"
            typeface = monoFont
            contentDescription = "Ordenar la lista"
            setTextColor(getColor(R.color.marvin_amber))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { menuDeOrden() }
        }
        header.addView(botonOrden)
        botonSubir = TextView(this).apply {
            text = "＋"
            typeface = monoFont
            contentDescription = "Subir archivos del teléfono"
            setTextColor(getColor(R.color.marvin_amber))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { selector.launch("*/*") }
        }
        header.addView(botonSubir)
        botonRecargar = TextView(this).apply {
            text = "⟳"
            typeface = monoFont
            contentDescription = "Actualizar la lista"
            setTextColor(getColor(R.color.marvin_amber))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(10), dp(8), dp(8), dp(8))
            setOnClickListener { loadDocs() }
        }
        header.addView(botonRecargar)
        root.addView(header)

        criterio = runCatching {
            DocsPlan.Criterio.valueOf(prefs.getString("docs_orden", null) ?: "MODIFICACION")
        }.getOrDefault(DocsPlan.Criterio.MODIFICACION)
        ordenAsc = prefs.getBoolean("docs_orden_asc", false)

        status = TextView(this).apply {
            typeface = fuenteDetalle()
            setTextColor(getColor(R.color.marvin_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(20), 0, dp(20), dp(10))
        }
        root.addView(status)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(MATCH, 0, 1f))

        setContentView(root)
        root.post {
            // id "docs2": la pantalla ganó subir/ordenar/borrar y la guía nueva tiene que
            // mostrarse una vez más a quienes ya vieron la vieja.
            Tour.lanzar(this, "docs2", listOf(
                Tour.Paso(
                    "Documentos compartidos",
                    "Aquí aparecen los archivos que Claude comparte desde el host con: " +
                        "marvin-share archivo. Toque uno para abrirlo.",
                    blanco = { status },
                ),
                Tour.Paso(
                    "Subir desde el teléfono",
                    "El botón ＋ abre el selector de Android: los archivos elegidos " +
                        "suben a la carpeta subidos/ del host (hasta 25 MB cada uno) y " +
                        "aparecen en su propia sección.",
                    blanco = { botonSubir },
                ),
                Tour.Paso(
                    "Ordenar",
                    "El botón ⇅ elige el criterio: nombre, tamaño, tipo, fecha de " +
                        "creación o de modificación, en ambas direcciones. La elección " +
                        "queda guardada.",
                    blanco = { botonOrden },
                ),
                Tour.Paso(
                    "Borrar",
                    "Mantenga presionada una tarjeta para borrar ese documento del " +
                        "host. Se pide confirmación: el borrado no se puede deshacer.",
                ),
                Tour.Paso(
                    "Actualizar",
                    "La lista no se actualiza sola: toque ⟳ después de pedirle algo " +
                        "a Claude.",
                    blanco = { botonRecargar },
                ),
            ))
        }
    }

    override fun onResume() {
        super.onResume()
        loadDocs()
    }

    private fun loadDocs() {
        status.text = "Cargando…"
        thread {
            // El motivo se conserva: "no pude conectarme" y "no hay documentos" son cosas
            // distintas y antes se veían iguales.
            var motivo: String? = null
            val docs = try { control.listDocs() } catch (e: Exception) {
                motivo = e.message ?: "no pude conectarme al host"
                emptyList()
            }
            runOnUiThread {
                list.removeAllViews()
                if (motivo != null) {
                    status.text = "No pude leer los documentos: $motivo"
                    list.addView(TextView(this).apply {
                        text = "Revisá que el host esté encendido y accesible."
                        typeface = fuenteDetalle()
                        setTextColor(getColor(R.color.marvin_muted))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        setPadding(dp(24), dp(24), dp(24), dp(24))
                    })
                    return@runOnUiThread
                }
                if (docs.isEmpty()) {
                    status.text = "Sin documentos en ~/RemoteMarvinDocs del host."
                    list.addView(TextView(this).apply {
                        text = "Compartí algo con  marvin-share <archivo>  en la PC\n" +
                            "y va a aparecer acá."
                        typeface = fuenteDetalle()
                        setTextColor(getColor(R.color.marvin_muted))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        setPadding(dp(24), dp(24), dp(24), dp(24))
                    })
                    return@runOnUiThread
                }
                ultimosDocs = docs
                // Si el filesystem del host no registra creación, ese criterio no ordena:
                // se degrada a modificación en vez de mostrar un orden que miente.
                if (criterio == DocsPlan.Criterio.CREACION && !DocsPlan.hayBtime(docs)) {
                    criterio = DocsPlan.Criterio.MODIFICACION
                    guardarOrden()
                }
                status.text = "${docs.size} documento${if (docs.size == 1) "" else "s"}" +
                    " · por ${criterio.etiqueta} ${if (ordenAsc) "↑" else "↓"}"
                val (subidos, compartidos) = docs.partition { it.subido }
                if (subidos.isEmpty()) {
                    DocsPlan.ordenar(compartidos, criterio, ordenAsc).forEach { list.addView(card(it)) }
                } else {
                    list.addView(tituloSeccion("Subidos desde el celular"))
                    DocsPlan.ordenar(subidos, criterio, ordenAsc).forEach { list.addView(card(it)) }
                    list.addView(tituloSeccion("Compartidos por Claude"))
                    DocsPlan.ordenar(compartidos, criterio, ordenAsc).forEach { list.addView(card(it)) }
                }
            }
        }
    }

    private fun tituloSeccion(texto: String) = TextView(this).apply {
        text = texto
        typeface = fuenteDetalle()
        setTextColor(getColor(R.color.marvin_muted))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(20), dp(14), dp(20), dp(2))
    }

    private fun guardarOrden() {
        prefs.edit().putString("docs_orden", criterio.name).putBoolean("docs_orden_asc", ordenAsc).apply()
    }

    private fun menuDeOrden() {
        val criterios = DocsPlan.Criterio.values().toList()
        val opciones = criterios.map { c ->
            val marca = if (c == criterio) " ${if (ordenAsc) "↑" else "↓"}" else ""
            "Por ${c.etiqueta}$marca"
        } + "Invertir dirección"
        AlertDialog.Builder(this)
            .setTitle("Ordenar documentos")
            .setItems(opciones.toTypedArray()) { _, i ->
                if (i < criterios.size) {
                    val elegido = criterios[i]
                    if (elegido == DocsPlan.Criterio.CREACION && !DocsPlan.hayBtime(ultimosDocs)) {
                        Toast.makeText(this, "El host no registra fecha de creación", Toast.LENGTH_LONG).show()
                        return@setItems
                    }
                    criterio = elegido
                } else {
                    ordenAsc = !ordenAsc
                }
                guardarOrden()
                loadDocs()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Sube los archivos elegidos, secuencial, informando por la línea de estado. */
    private fun subir(uris: List<Uri>) {
        thread {
            var subidos = 0
            uris.forEachIndexed { i, uri ->
                runOnUiThread { status.text = "Subiendo ${i + 1}/${uris.size}…" }
                val (display, declarado) = nombreYTamano(uri)
                val nombre = DocsPlan.normalizarNombre(display)
                if (nombre == null) {
                    aviso("Nombre de archivo inválido: se salteó"); return@forEachIndexed
                }
                if (declarado > MAX_SUBIDA) {
                    aviso("$nombre supera los 25 MB: se salteó"); return@forEachIndexed
                }
                val bytes = try {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } catch (_: Exception) { null }
                if (bytes == null) { aviso("No pude leer $nombre"); return@forEachIndexed }
                if (bytes.size > MAX_SUBIDA) { aviso("$nombre supera los 25 MB: se salteó"); return@forEachIndexed }
                val r = control.uploadDoc(nombre, bytes)
                if (r.ok) subidos++ else aviso("No pude subir $nombre: ${r.error}")
            }
            runOnUiThread {
                if (subidos > 0) Toast.makeText(this, "Subido${if (subidos == 1) "" else "s"} $subidos", Toast.LENGTH_SHORT).show()
                loadDocs()
            }
        }
    }

    private fun aviso(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    /** Nombre visible y tamaño declarado del content provider (tamaño -1 si no lo sabe). */
    private fun nombreYTamano(uri: Uri): Pair<String?, Long> {
        var nombre: String? = uri.lastPathSegment
        var tam = -1L
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val iN = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val iS = c.getColumnIndex(OpenableColumns.SIZE)
                    if (iN >= 0) nombre = c.getString(iN)
                    if (iS >= 0 && !c.isNull(iS)) tam = c.getLong(iS)
                }
            }
        }
        return nombre to tam
    }

    private fun card(doc: RemoteControl.Doc): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.marvin_surface))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            val lp = LinearLayout.LayoutParams(MATCH, WRAP); lp.setMargins(dp(16), dp(5), dp(16), dp(5))
            layoutParams = lp
            setOnClickListener { open(doc) }
            setOnLongClickListener { menuDeDoc(doc); true }
        }
        card.addView(TextView(this).apply {
            text = iconFor(doc.name)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(0, 0, dp(14), 0)
        })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = doc.name
            typeface = bodyFont
            setTextColor(getColor(R.color.marvin_fg))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        col.addView(TextView(this).apply {
            text = "${humanSize(doc.size)} · ${humanDate(doc.mtime)}"
            typeface = monoFont
            setTextColor(getColor(R.color.marvin_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        card.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        return card
    }

    private fun menuDeDoc(doc: RemoteControl.Doc) {
        AlertDialog.Builder(this)
            .setTitle(doc.name)
            .setItems(arrayOf("Borrar del host", "Cancelar")) { _, i ->
                if (i == 0) confirmarBorrado(doc)
            }
            .show()
    }

    private fun confirmarBorrado(doc: RemoteControl.Doc) {
        AlertDialog.Builder(this)
            .setMessage("¿Borrar \"${doc.name}\" del host? No se puede deshacer.")
            .setPositiveButton("Borrar") { _, _ ->
                thread {
                    val r = control.deleteDoc(doc.name, doc.subido)
                    runOnUiThread {
                        if (r.failed) {
                            Toast.makeText(this, "No pude borrar: ${r.error}", Toast.LENGTH_LONG).show()
                        }
                        loadDocs()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun open(doc: RemoteControl.Doc) {
        startActivity(Intent(this, DocViewerActivity::class.java).apply {
            putExtra("hostname", host); putExtra("port", port); putExtra("user", user)
            putExtra("name", doc.name); putExtra("size", doc.size)
            putExtra("subido", doc.subido)
        })
    }

    private fun iconFor(name: String) = when (DocKind.of(name)) {
        DocKind.IMAGE -> "🖼"
        DocKind.PDF -> "📕"
        DocKind.TEXT -> "📄"
        else -> "📎"
    }

    private fun humanSize(b: Long): String = when {
        b >= 1_048_576 -> String.format(Locale.US, "%.1f MB", b / 1_048_576.0)
        b >= 1024 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
        else -> "$b B"
    }

    private fun humanDate(epoch: Long): String =
        if (epoch <= 0) "" else SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date(epoch * 1000))

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /** Tope de subida por archivo (el mismo del render daemon del host). */
        private const val MAX_SUBIDA = 25 * 1024 * 1024
    }
}
