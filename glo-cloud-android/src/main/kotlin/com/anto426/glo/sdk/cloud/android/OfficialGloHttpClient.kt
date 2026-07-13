package com.anto426.glo.sdk.cloud.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection

internal class OfficialGloHttpResponse(
    val statusCode: Int,
    val body: String,
) {
    val isSuccessful: Boolean
        get() = statusCode in 200..299

    override fun toString(): String = "OfficialGloHttpResponse(statusCode=$statusCode, body=redacted)"
}

internal class OfficialGloHttpClient {
    suspend fun request(
        path: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): OfficialGloHttpResponse = withContext(Dispatchers.IO) {
        require(path.startsWith('/')) { "Cloud API paths must be relative to the fixed HTTPS origin" }
        val connection = URL(API_ORIGIN + path).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach(connection::setRequestProperty)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { output ->
                    output.write(body.toByteArray(StandardCharsets.UTF_8))
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            OfficialGloHttpResponse(status, stream.readUtf8Limited())
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream?.readUtf8Limited(): String {
        if (this == null) return ""
        return bufferedReader(StandardCharsets.UTF_8).use { reader ->
            val output = StringBuilder()
            val buffer = CharArray(4_096)
            while (output.length < MAX_RESPONSE_CHARACTERS) {
                val count = reader.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_CHARACTERS - output.length))
                if (count < 0) break
                output.append(buffer, 0, count)
            }
            output.toString()
        }
    }

    private companion object {
        const val API_ORIGIN = "https://dfa00k0zv1pye.cloudfront.net/v1.0"
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
        const val MAX_RESPONSE_CHARACTERS = 1_000_000
    }
}
