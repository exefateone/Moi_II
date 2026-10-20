package com.iipauk.spider

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
 * Stores the app-to-backend bearer token encrypted with an Android Keystore AES-GCM key.
 * The external model-provider API key must remain on the backend and must never be entered here.
 */
class SpiderProviderConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun save(endpointUrl: String, clientToken: String) {
        val cleanEndpoint = endpointUrl.trim()
        SpiderProviderConnection.validateEndpoint(cleanEndpoint)
        require(clientToken.length in 24..4096) { "Client token has invalid length" }
        require(clientToken.none { it.code < 0x20 || it.code == 0x7f }) { "Client token contains control characters" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(clientToken.toByteArray(Charsets.UTF_8))
        val committed = prefs.edit()
            .putString(KEY_ENDPOINT, cleanEndpoint)
            .putString(KEY_TOKEN_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_TOKEN_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .commit()
        check(committed) { "Unable to persist planner connection settings" }
    }

    @Synchronized
    fun load(): SpiderProviderConnection? {
        val endpoint = prefs.getString(KEY_ENDPOINT, null) ?: return null
        val ivText = prefs.getString(KEY_TOKEN_IV, null) ?: return null
        val ciphertextText = prefs.getString(KEY_TOKEN_CIPHERTEXT, null) ?: return null
        return runCatching {
            SpiderProviderConnection.validateEndpoint(endpoint)
            val iv = Base64.decode(ivText, Base64.NO_WRAP)
            val encrypted = Base64.decode(ciphertextText, Base64.NO_WRAP)
            require(iv.size == GCM_IV_LENGTH_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val token = cipher.doFinal(encrypted).toString(Charsets.UTF_8)
            SpiderProviderConnection(endpoint, token)
        }.getOrNull()
    }

    @Synchronized
    fun clear() {
        check(prefs.edit().clear().commit()) { "Unable to clear planner connection settings" }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFS_NAME = "spider-agent-provider"
        private const val KEY_ENDPOINT = "endpoint_url"
        private const val KEY_TOKEN_IV = "token_iv"
        private const val KEY_TOKEN_CIPHERTEXT = "token_ciphertext"
        private const val KEY_ALIAS = "com.iipauk.spider.agent.backend-token.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
