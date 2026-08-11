package com.remoteclaude.app

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
    private lateinit var control: RemoteControl
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
        header.addView(TextView(this).apply {
            text = "⟳"
            typeface = monoFont
            setTextColor(getColor(R.color.marvin_amber))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(12), dp(8), dp(8), dp(8))
            setOnClickListener { loadDocs() }
        })
        root.addView(header)

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
    }

    override fun onResume() {
        super.onResume()
        loadDocs()
    }

    private fun loadDocs() {
        status.text = "Cargando…"
        thread {
            val docs = try { control.listDocs() } catch (_: Exception) { emptyList() }
            runOnUiThread {
                list.removeAllViews()
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
                status.text = "${docs.size} documento${if (docs.size == 1) "" else "s"}"
                docs.forEach { (name, size, mtime) -> list.addView(card(name, size, mtime)) }
            }
        }
    }

    private fun card(name: String, size: Long, mtimeEpoch: Long): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.marvin_surface))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            val lp = LinearLayout.LayoutParams(MATCH, WRAP); lp.setMargins(dp(16), dp(5), dp(16), dp(5))
            layoutParams = lp
            setOnClickListener { open(name, size) }
        }
        card.addView(TextView(this).apply {
            text = iconFor(name)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(0, 0, dp(14), 0)
        })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = name
            typeface = bodyFont
            setTextColor(getColor(R.color.marvin_fg))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        col.addView(TextView(this).apply {
            text = "${humanSize(size)} · ${humanDate(mtimeEpoch)}"
            typeface = monoFont
            setTextColor(getColor(R.color.marvin_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        card.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        return card
    }

    private fun open(name: String, size: Long) {
        startActivity(Intent(this, DocViewerActivity::class.java).apply {
            putExtra("hostname", host); putExtra("port", port); putExtra("user", user)
            putExtra("name", name); putExtra("size", size)
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
    }
}
