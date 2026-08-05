package com.remoteclaude.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Guardado de secretos de la app (hoy: la auth key de Tailscale) cifrados con una clave
 * AES que vive en el AndroidKeyStore — o sea que el material nunca sale del Keystore y el
 * XML de preferencias deja de tener el secreto en texto plano.
 *
 * Mismo criterio que [KeyStoreSsh], sin dependencias nuevas (androidx.security-crypto
 * sigue en alpha y arrastra más de lo que necesitamos).
 */
object SecretStore {

    private const val ALIAS = "marvin-secrets"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return kg.generateKey()
    }

    /** Guarda [value] cifrado bajo [name]. Un valor vacío borra la entrada. */
    fun put(ctx: Context, name: String, value: String) {
        val prefs = ctx.applicationContext
            .getSharedPreferences("remotemarvin", Context.MODE_PRIVATE)
        if (value.isEmpty()) {
            prefs.edit().remove("${name}_enc").apply()
            return
        }
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(value.toByteArray(Charsets.UTF_8))   // IV || ciphertext
        prefs.edit().putString("${name}_enc", Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    /** Devuelve el secreto descifrado, o "" si no hay (o si ya no se puede descifrar). */
    fun get(ctx: Context, name: String): String {
        val prefs = ctx.applicationContext
            .getSharedPreferences("remotemarvin", Context.MODE_PRIVATE)
        val blob = prefs.getString("${name}_enc", null) ?: return ""
        return try {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            val c = Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, raw, 0, IV_LEN))
            }
            String(c.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
        } catch (_: Exception) {
            // p.ej. si se reinstaló la app y la clave del Keystore ya no existe
            ""
        }
    }

    /**
     * Migra un valor que estaba en claro bajo [plainName] al almacén cifrado y borra el
     * original. Devuelve el valor vigente.
     */
    fun migrate(ctx: Context, plainName: String, name: String): String {
        val prefs = ctx.applicationContext
            .getSharedPreferences("remotemarvin", Context.MODE_PRIVATE)
        val plain = prefs.getString(plainName, null)
        if (!plain.isNullOrEmpty()) {
            put(ctx, name, plain)
            prefs.edit().remove(plainName).apply()
            return plain
        }
        return get(ctx, name)
    }
}
