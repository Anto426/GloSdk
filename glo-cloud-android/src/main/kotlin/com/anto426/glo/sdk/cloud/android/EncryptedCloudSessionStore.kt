package com.anto426.glo.sdk.cloud.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class CloudSession(
    val userId: String,
    val customerToken: String,
    val accessToken: String,
    val refreshToken: String?,
    val tenantId: String,
    val tenantUserId: String?,
    val vaultPin: String?,
    val vaultSetupRequired: Boolean,
) {
    val hasVerifiedVault: Boolean
        get() = !tenantUserId.isNullOrBlank() && !vaultPin.isNullOrBlank()

    override fun toString(): String = "CloudSession(redacted)"
}

internal class EncryptedCloudSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun load(): CloudSession? {
        val encodedIv = preferences.getString(KEY_IV, null) ?: return null
        val encodedCiphertext = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(encodedIv, Base64.NO_WRAP)),
            )
            val plaintext = cipher.doFinal(Base64.decode(encodedCiphertext, Base64.NO_WRAP))
            val json = JSONObject(String(plaintext, StandardCharsets.UTF_8))
            CloudSession(
                userId = json.getString("userId"),
                customerToken = json.getString("customerToken"),
                accessToken = json.getString("accessToken"),
                refreshToken = json.optString("refreshToken").takeIf(String::isNotBlank),
                tenantId = json.getString("tenantId"),
                tenantUserId = json.optString("tenantUserId").takeIf(String::isNotBlank),
                vaultPin = json.optString("vaultPin").takeIf(String::isNotBlank),
                vaultSetupRequired = json.optBoolean("vaultSetupRequired", false),
            )
        } catch (_: Throwable) {
            clear(deleteKey = true)
            null
        }
    }

    @Synchronized
    fun save(session: CloudSession) {
        val json = JSONObject()
            .put("userId", session.userId)
            .put("customerToken", session.customerToken)
            .put("accessToken", session.accessToken)
            .put("refreshToken", session.refreshToken.orEmpty())
            .put("tenantId", session.tenantId)
            .put("tenantUserId", session.tenantUserId.orEmpty())
            .put("vaultPin", session.vaultPin.orEmpty())
            .put("vaultSetupRequired", session.vaultSetupRequired)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(json.toString().toByteArray(StandardCharsets.UTF_8))
        check(
            preferences.edit()
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .commit(),
        ) { "Unable to persist the encrypted cloud session" }
    }

    @Synchronized
    fun clear(deleteKey: Boolean = true) {
        preferences.edit().clear().commit()
        if (deleteKey) {
            runCatching {
                val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
                if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
            }
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "glo_cloud_session"
        const val KEY_IV = "iv"
        const val KEY_CIPHERTEXT = "ciphertext"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "com.anto426.glo.cloud.session.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
