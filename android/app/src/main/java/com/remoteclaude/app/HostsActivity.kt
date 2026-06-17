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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Menú principal: lista de hosts a los que conectarse. Tocar uno abre la terminal;
 *  mantener apretado permite editar/borrar. El "+" agrega uno nuevo. */
class HostsActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private val titleFont by lazy { resources.getFont(R.font.isocpeur) }
    private val monoFont by lazy { resources.getFont(R.font.mononoki) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = getColor(R.color.marvin_petrol)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.marvin_petrol))
        }

        // Encabezado
        root.addView(TextView(this).apply {
            text = "[ MARVIN ]"
            typeface = titleFont
            setTextColor(getColor(R.color.marvin_green))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setPadding(dp(20), dp(28), dp(20), dp(2))
        })
        root.addView(TextView(this).apply {
            text = "_hosts"
            typeface = monoFont
            setTextColor(getColor(R.color.marvin_amber))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(20), 0, dp(20), dp(16))
        })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(MATCH, 0, 1f))

        // Botón agregar
        root.addView(Button(this).apply {
            text = "+  Agregar host"
            isAllCaps = false
            typeface = monoFont
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
    }

    private fun refresh() {
        list.removeAllViews()
        val hosts = HostStore.load(this)
        if (hosts.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Todavía no hay hosts.\nAgregá tu PC o un server con \"+\"."
                typeface = monoFont
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
            typeface = monoFont
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
