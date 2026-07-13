package com.anto426.glo.sdk.cloud.android

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class WebLoginCallback(
    val userId: String,
    val customerToken: String,
    val accessToken: String,
    val refreshToken: String?,
)

internal object WebLoginCallbackParser {
    fun parse(callbackUrl: String): WebLoginCallback? = runCatching {
        val uri = URI(callbackUrl)
        if (!uri.scheme.equals(EXPECTED_SCHEME, ignoreCase = true) ||
            !uri.host.equals(EXPECTED_HOST, ignoreCase = true)
        ) {
            return null
        }
        val parameters = uri.rawQuery.orEmpty()
            .split('&')
            .asSequence()
            .filter { it.isNotBlank() }
            .map { item ->
                val separator = item.indexOf('=')
                val rawKey = if (separator < 0) item else item.substring(0, separator)
                val rawValue = if (separator < 0) "" else item.substring(separator + 1)
                decode(rawKey) to decode(rawValue)
            }
            .toMap()
        val userId = parameters["userId"].orEmpty()
        val customerToken = parameters["customer_token"].orEmpty()
        val accessToken = parameters["access_token"].orEmpty()
        // The official client authenticates login with userId + customer_token;
        // access_token is retained when present but is not mandatory for this flow.
        if (userId.isBlank() || customerToken.isBlank()) return null
        WebLoginCallback(
            userId = userId,
            customerToken = customerToken,
            accessToken = accessToken,
            refreshToken = parameters["refreshToken"]?.takeIf(String::isNotBlank),
        )
    }.getOrNull()

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private const val EXPECTED_SCHEME = "myglo"
    private const val EXPECTED_HOST = "open"
}

internal object VaultIdentity {
    fun from(customerId: String, pin: String): String {
        val key = (customerId + pin).toByteArray(StandardCharsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(customerId.toByteArray(StandardCharsets.UTF_8)).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
    }
}

/** Compatible with `CryptoJS.AES.encrypt(value, passphrase).toString()`. */
internal object CryptoJsAesPassphrase {
    private val secureRandom = SecureRandom()
    private val saltedPrefix = "Salted__".toByteArray(StandardCharsets.US_ASCII)

    fun encrypt(plaintext: String, passphrase: String): String {
        require(passphrase.isNotEmpty()) { "Passphrase must not be empty" }
        val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
        return encrypt(plaintext, passphrase, salt)
    }

    internal fun encrypt(plaintext: String, passphrase: String, salt: ByteArray): String {
        require(salt.size == SALT_BYTES) { "CryptoJS salt must be 8 bytes" }
        val passphraseBytes = passphrase.toByteArray(StandardCharsets.UTF_8)
        val derived = evpBytesToKey(passphraseBytes, salt, KEY_BYTES + IV_BYTES)
        val key = derived.copyOfRange(0, KEY_BYTES)
        val iv = derived.copyOfRange(KEY_BYTES, KEY_BYTES + IV_BYTES)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv),
        )
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(saltedPrefix + salt + ciphertext)
    }

    private fun evpBytesToKey(passphrase: ByteArray, salt: ByteArray, outputSize: Int): ByteArray {
        val digest = MessageDigest.getInstance("MD5")
        val output = ByteArray(outputSize)
        var previous = ByteArray(0)
        var offset = 0
        while (offset < outputSize) {
            digest.reset()
            digest.update(previous)
            digest.update(passphrase)
            digest.update(salt)
            previous = digest.digest()
            val count = minOf(previous.size, outputSize - offset)
            previous.copyInto(output, destinationOffset = offset, endIndex = count)
            offset += count
        }
        return output
    }

    private const val SALT_BYTES = 8
    private const val KEY_BYTES = 32
    private const val IV_BYTES = 16
}
