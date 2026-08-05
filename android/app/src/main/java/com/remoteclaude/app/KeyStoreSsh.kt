package com.remoteclaude.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Clave SSH propia de la app, generada y guardada en el **Android Keystore**
 * (privada NO exportable; el Keystore firma sin entregar los bytes). Es una EC
 * P-256 → en SSH es `ecdsa-sha2-nistp256`. Reemplaza la clave de prueba bundleada.
 */
object KeyStoreSsh {

    private const val ALIAS = "remoteclaude_ssh"
    private const val TYPE = "ecdsa-sha2-nistp256"

    /** Devuelve el par (creándolo en el Keystore la primera vez). */
    @Volatile private var cached: java.security.KeyPair? = null

    /**
     * Sincronizado y cacheado: sin lock, dos hilos podían generar a la vez y el segundo
     * PISABA el alias, dejando la app des-autorizada en un host donde la clave anterior
     * ya estaba en authorized_keys. Además evita I/O del Keystore en el hilo principal.
     */
    @Synchronized
    fun getOrCreateKeyPair(): KeyPair {
        cached?.let { return it }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let {
            return KeyPair(it.certificate.publicKey, it.privateKey).also { kp -> cached = kp }
        }
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        kpg.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        return kpg.generateKeyPair().also { cached = it }
    }

    /** Línea para `authorized_keys`: `ecdsa-sha2-nistp256 <base64> <comment>`. */
    fun openSshPublicKey(comment: String): String {
        val pub = getOrCreateKeyPair().public as ECPublicKey
        val x = toFixed(pub.w.affineX, 32)
        val y = toFixed(pub.w.affineY, 32)
        val q = ByteArray(65).also { it[0] = 0x04; x.copyInto(it, 1); y.copyInto(it, 33) }
        val blob = ByteArrayOutputStream().apply {
            sshString(this, TYPE.toByteArray(Charsets.US_ASCII))
            sshString(this, "nistp256".toByteArray(Charsets.US_ASCII))
            sshString(this, q)
        }.toByteArray()
        return "$TYPE ${Base64.encodeToString(blob, Base64.NO_WRAP)} $comment"
    }

    private fun sshString(out: ByteArrayOutputStream, data: ByteArray) {
        val n = data.size
        out.write((n ushr 24) and 0xff); out.write((n ushr 16) and 0xff)
        out.write((n ushr 8) and 0xff); out.write(n and 0xff)
        out.write(data)
    }

    /** BigInteger → big-endian de largo fijo (sin byte de signo, con padding). */
    private fun toFixed(bi: BigInteger, len: Int): ByteArray {
        val b = bi.toByteArray()
        return when {
            b.size == len -> b
            b.size == len + 1 && b[0].toInt() == 0 -> b.copyOfRange(1, b.size)
            else -> ByteArray(len).also { b.copyInto(it, len - b.size) }
        }
    }
}
