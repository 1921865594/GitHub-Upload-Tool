package com.githubupload.mt

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/**
 * 用 Android Keystore 加密保存 Git PAT。
 * 令牌不会以明文写入 SharedPreferences。
 */
class SecurePrefs(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val alias = "${context.packageName}.github_upload_token"

    init {
        ensureKey()
    }

    fun putToken(token: String) {
        if (token.isBlank()) {
            prefs.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).apply()
            return
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val encrypted = cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8))

        prefs.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun getToken(): String {
        val encoded = prefs.getString(KEY_CIPHERTEXT, null) ?: return ""
        val encodedIv = prefs.getString(KEY_IV, null) ?: return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(encodedIv, Base64.NO_WRAP))
            )
            String(
                cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)),
                StandardCharsets.UTF_8
            )
        } catch (_: Exception) {
            ""
        }
    }

    private fun ensureKey() {
        val keyStore = java.security.KeyStore.getInstance(KEYSTORE)
        keyStore.load(null)
        if (!keyStore.containsAlias(alias)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            keyGenerator.generateKey()
        }
    }

    private fun getKey(): SecretKey {
        ensureKey()
        val keyStore = java.security.KeyStore.getInstance(KEYSTORE)
        keyStore.load(null)
        return keyStore.getKey(alias, null) as SecretKey
    }

    companion object {
        private const val PREFS_NAME = "github_upload_config"
        private const val KEY_CIPHERTEXT = "token_ciphertext"
        private const val KEY_IV = "token_iv"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
