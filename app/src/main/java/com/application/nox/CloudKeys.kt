package com.application.nox

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
 * Clés des API en ligne, chiffrées par une clé AES du Keystore Android (jamais en clair sur le stockage),
 * modèle choisi par service, et compteurs d'usage (appels, temps moyen, dernière erreur).
 */
class CloudKeys(ctx: Context) {

    private val sp = ctx.getSharedPreferences("nox_cloud", Context.MODE_PRIVATE)

    init {
        // FLUX.1-dev devient le modèle d'image par défaut (meilleure qualité gratuite) : une seule fois
        if (!sp.getBoolean("img_v2", false)) {
            if (sp.getString("m_nvidia", "") == "black-forest-labs/flux.1-schnell") sp.edit().putString("m_nvidia", "").apply()
            sp.edit().putBoolean("img_v2", true).apply()
        }
    }

    fun key(api: String): String = sp.getString("k_$api", null)?.let { decrypt(it) }.orEmpty()

    fun setKey(api: String, value: String) {
        val v = value.trim()
        sp.edit().apply { if (v.isEmpty()) remove("k_$api") else putString("k_$api", encrypt(v)) }.apply()
    }

    fun has(api: String) = sp.contains("k_$api")

    fun model(api: String): String = sp.getString("m_$api", "").orEmpty()
    fun setModel(api: String, m: String) = sp.edit().putString("m_$api", m.trim()).apply()

    // ---------------------------------------------------------------- usage

    data class Usage(val calls: Int, val errors: Int, val avgMs: Long, val lastError: String, val lastOk: Long)

    fun usage(api: String) = Usage(
        sp.getInt("u_calls_$api", 0), sp.getInt("u_err_$api", 0),
        sp.getLong("u_ms_$api", 0L) / sp.getInt("u_calls_$api", 0).coerceAtLeast(1),
        sp.getString("u_last_$api", "").orEmpty(), sp.getLong("u_ok_$api", 0L),
    )

    fun record(api: String, ms: Long, error: String?) {
        val e = sp.edit()
        if (error == null) {
            e.putInt("u_calls_$api", sp.getInt("u_calls_$api", 0) + 1)
                .putLong("u_ms_$api", sp.getLong("u_ms_$api", 0L) + ms)
                .putLong("u_ok_$api", System.currentTimeMillis())
        } else {
            e.putInt("u_err_$api", sp.getInt("u_err_$api", 0) + 1).putString("u_last_$api", error.take(160))
        }
        e.apply()
    }

    fun resetUsage(api: String) = sp.edit().remove("u_calls_$api").remove("u_err_$api").remove("u_ms_$api")
        .remove("u_last_$api").remove("u_ok_$api").apply()

    // ---------------------------------------------------------------- chiffrement

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret()) }
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = try {
        val all = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, all, 0, 12)) }
        String(c.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
    } catch (t: Throwable) {
        NoxLog.w("CLOUD", "Clé illisible (Keystore réinitialisé ?)", t); null
    }

    companion object { private const val ALIAS = "nox_cloud_keys" }
}
