package com.saltchang.whisalt

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** OpenAI API key, encrypted with an AES-GCM key held in the Android Keystore. */
object ApiKeyStore {
    private const val TAG = "ApiKeyStore"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "whisalt_api_key"
    private const val PREF = "api_key_enc"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12

    fun get(ctx: Context): String {
        val stored = prefs(ctx).getString(PREF, null) ?: return ""
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_LEN))
            String(cipher.doFinal(bytes, IV_LEN, bytes.size - IV_LEN), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt API key", e)
            ""
        }
    }

    fun set(ctx: Context, apiKey: String) {
        if (apiKey.isBlank()) {
            prefs(ctx).edit().remove(PREF).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        prefs(ctx).edit().putString(PREF, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("whisalt", Context.MODE_PRIVATE)
}
