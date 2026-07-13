package com.anto426.glo.sdk.cloud.android

import android.content.Context
import com.anto426.glo.sdk.api.cloud.CustomGreetingRequest
import com.anto426.glo.sdk.api.cloud.GloCloudAuthState
import com.anto426.glo.sdk.api.cloud.GloCloudClient
import com.anto426.glo.sdk.api.cloud.GloCloudError
import com.anto426.glo.sdk.api.cloud.GloCloudResult
import com.anto426.glo.sdk.api.cloud.GloTenantConfiguration
import com.anto426.glo.sdk.model.SignedDisplayPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal class OfficialGloCloudClient(context: Context) : GloCloudClient {
    private val http = OfficialGloHttpClient()
    private val sessionStore = EncryptedCloudSessionStore(context)
    private val operationMutex = Mutex()
    private var session: CloudSession? = sessionStore.load()

    private val mutableAuthState = MutableStateFlow(session.toAuthState())
    override val authState: StateFlow<GloCloudAuthState> = mutableAuthState.asStateFlow()

    private val mutableTenantConfiguration = MutableStateFlow<GloTenantConfiguration?>(null)
    override val tenantConfiguration: StateFlow<GloTenantConfiguration?> =
        mutableTenantConfiguration.asStateFlow()

    override suspend fun loadTenantConfiguration(
        preferredLocale: String,
    ): GloCloudResult<GloTenantConfiguration> = guarded {
        loadTenantConfigurationLocked(preferredLocale)
    }

    override suspend fun completeWebLogin(callbackUrl: String): GloCloudResult<Unit> = guarded {
        val callback = WebLoginCallbackParser.parse(callbackUrl)
            ?: return@guarded GloCloudResult.Failure(GloCloudError.InvalidLoginCallback)
        val configuration = mutableTenantConfiguration.value ?: when (
            val loaded = loadTenantConfigurationLocked(DEFAULT_LOCALE)
        ) {
            is GloCloudResult.Success -> loaded.value
            is GloCloudResult.Failure -> return@guarded loaded
        }
        val newSession = CloudSession(
            userId = callback.userId,
            customerToken = callback.customerToken,
            accessToken = callback.accessToken,
            refreshToken = callback.refreshToken,
            tenantId = configuration.tenantId,
            tenantUserId = null,
            vaultPin = null,
            vaultSetupRequired = false,
        )
        val userResponse = userInfoRequest(newSession)
        if (!userResponse.isSuccessful) {
            return@guarded GloCloudResult.Failure(
                if (userResponse.statusCode == HTTP_UNAUTHORIZED) GloCloudError.AuthenticationRequired
                else GloCloudError.ServiceUnavailable(userResponse.statusCode),
            )
        }
        val vaultSetupRequired = userResponse.vaultSetupRequiredFor(newSession.userId)
            ?: return@guarded GloCloudResult.Failure(GloCloudError.InvalidServerResponse)
        val establishedSession = CloudSession(
            userId = newSession.userId,
            customerToken = newSession.customerToken,
            accessToken = newSession.accessToken,
            refreshToken = newSession.refreshToken,
            tenantId = newSession.tenantId,
            tenantUserId = null,
            vaultPin = null,
            vaultSetupRequired = vaultSetupRequired,
        )
        sessionStore.save(establishedSession)
        session = establishedSession
        mutableAuthState.value = establishedSession.toAuthState()
        GloCloudResult.Success(Unit)
    }

    override suspend fun validateVaultPin(pin: String): GloCloudResult<Unit> = guarded {
        val current = session
            ?: return@guarded GloCloudResult.Failure(GloCloudError.AuthenticationRequired)
        if (current.vaultSetupRequired) {
            return@guarded GloCloudResult.Failure(GloCloudError.InvalidVaultSetup)
        }
        if (pin.length != VAULT_PIN_LENGTH || pin.any { !it.isDigit() }) {
            return@guarded GloCloudResult.Failure(GloCloudError.InvalidVaultPin())
        }
        val tenantUserId = VaultIdentity.from(current.userId, pin)
        var activeSession = current
        var response = validatePinRequest(activeSession, pin, tenantUserId)
        if (response.statusCode == HTTP_UNAUTHORIZED) {
            activeSession = refreshSessionLocked(activeSession) ?: run {
                clearSessionLocked()
                return@guarded GloCloudResult.Failure(GloCloudError.AuthenticationRequired)
            }
            response = validatePinRequest(activeSession, pin, tenantUserId)
        }
        if (!response.isSuccessful) {
            return@guarded GloCloudResult.Failure(
                GloCloudError.ServiceUnavailable(response.statusCode),
            )
        }
        val json = response.body.toJsonObjectOrNull()
            ?: return@guarded GloCloudResult.Failure(GloCloudError.InvalidServerResponse)
        val code = json.findInt("code")
            ?: return@guarded GloCloudResult.Failure(GloCloudError.InvalidServerResponse)
        if (code != 0) {
            val attempts = json.findInt("pin_left_attempts")
                ?: json.findInt("attemptsRemaining")
                ?: json.findInt("attempts")
            return@guarded GloCloudResult.Failure(GloCloudError.InvalidVaultPin(attempts))
        }
        val verified = CloudSession(
            userId = activeSession.userId,
            customerToken = activeSession.customerToken,
            accessToken = activeSession.accessToken,
            refreshToken = activeSession.refreshToken,
            tenantId = activeSession.tenantId,
            tenantUserId = tenantUserId,
            vaultPin = pin,
            vaultSetupRequired = false,
        )
        sessionStore.save(verified)
        session = verified
        mutableAuthState.value = GloCloudAuthState.Authenticated
        GloCloudResult.Success(Unit)
    }

    override suspend fun createVault(
        pin: String,
        securityQuestionId: String,
        securityAnswer: String,
    ): GloCloudResult<Unit> = guarded {
        val current = session
            ?: return@guarded GloCloudResult.Failure(GloCloudError.AuthenticationRequired)
        if (!current.vaultSetupRequired || current.hasVerifiedVault ||
            pin.length != VAULT_PIN_LENGTH || pin.any { !it.isDigit() }
        ) {
            return@guarded GloCloudResult.Failure(GloCloudError.InvalidVaultSetup)
        }
        val configuration = mutableTenantConfiguration.value
        if (securityQuestionId.isBlank() ||
            configuration?.vaultQuestionIds?.takeIf(List<String>::isNotEmpty)
                ?.contains(securityQuestionId) == false
        ) {
            return@guarded GloCloudResult.Failure(GloCloudError.InvalidVaultSetup)
        }
        val sanitizedAnswer = securityAnswer.sanitizeSecurityAnswer()
        if (sanitizedAnswer.isBlank()) {
            return@guarded GloCloudResult.Failure(GloCloudError.InvalidVaultSetup)
        }

        val tenantUserId = VaultIdentity.from(current.userId, pin)
        val encryptedPin = CryptoJsAesPassphrase.encrypt(
            plaintext = pin,
            passphrase = sanitizedAnswer + securityQuestionId,
        )
        var activeSession = current
        var identityResponse = storeTenantUserIdRequest(activeSession, tenantUserId)
        if (identityResponse.statusCode == HTTP_UNAUTHORIZED) {
            activeSession = refreshSessionLocked(activeSession) ?: run {
                clearSessionLocked()
                return@guarded GloCloudResult.Failure(GloCloudError.AuthenticationRequired)
            }
            identityResponse = storeTenantUserIdRequest(activeSession, tenantUserId)
        }
        if (!identityResponse.isSuccessful || !identityResponse.hasSuccessfulResultCode()) {
            return@guarded GloCloudResult.Failure(
                GloCloudError.ServiceUnavailable(identityResponse.statusCode),
            )
        }

        var pinResponse = storePinRequest(activeSession, tenantUserId, encryptedPin)
        if (pinResponse.statusCode == HTTP_UNAUTHORIZED) {
            activeSession = refreshSessionLocked(activeSession) ?: run {
                clearSessionLocked()
                return@guarded GloCloudResult.Failure(GloCloudError.AuthenticationRequired)
            }
            pinResponse = storePinRequest(activeSession, tenantUserId, encryptedPin)
        }
        if (!pinResponse.isSuccessful || !pinResponse.hasSuccessfulResultCode()) {
            return@guarded GloCloudResult.Failure(
                GloCloudError.ServiceUnavailable(pinResponse.statusCode),
            )
        }

        var userResponse = markUserAsPreviouslyLoggedInRequest(activeSession)
        if (userResponse.statusCode == HTTP_UNAUTHORIZED) {
            activeSession = refreshSessionLocked(activeSession) ?: run {
                clearSessionLocked()
                return@guarded GloCloudResult.Failure(GloCloudError.AuthenticationRequired)
            }
            userResponse = markUserAsPreviouslyLoggedInRequest(activeSession)
        }
        if (!userResponse.isSuccessful || !userResponse.hasSuccessfulResultCode()) {
            return@guarded GloCloudResult.Failure(
                GloCloudError.ServiceUnavailable(userResponse.statusCode),
            )
        }

        val verified = CloudSession(
            userId = activeSession.userId,
            customerToken = activeSession.customerToken,
            accessToken = activeSession.accessToken,
            refreshToken = activeSession.refreshToken,
            tenantId = activeSession.tenantId,
            tenantUserId = tenantUserId,
            vaultPin = pin,
            vaultSetupRequired = false,
        )
        sessionStore.save(verified)
        session = verified
        mutableAuthState.value = GloCloudAuthState.Authenticated
        GloCloudResult.Success(Unit)
    }

    override suspend fun generateCustomGreeting(
        request: CustomGreetingRequest,
    ): GloCloudResult<SignedDisplayPayload> = guarded {
        val current = session
            ?: return@guarded GloCloudResult.Failure(GloCloudError.AuthenticationRequired)
        if (!current.hasVerifiedVault) {
            mutableAuthState.value = current.toAuthState()
            return@guarded GloCloudResult.Failure(GloCloudError.VaultPinRequired)
        }
        if (!request.isValid(mutableTenantConfiguration.value)) {
            return@guarded GloCloudResult.Failure(GloCloudError.GreetingRejected)
        }

        var activeSession = current
        var response = greetingRequest(activeSession, request)
        if (response.statusCode == HTTP_UNAUTHORIZED) {
            activeSession = refreshSessionLocked(activeSession) ?: run {
                clearSessionLocked()
                return@guarded GloCloudResult.Failure(GloCloudError.AuthenticationRequired)
            }
            response = greetingRequest(activeSession, request)
        }
        if (!response.isSuccessful) {
            val rejected = response.statusCode == 400 &&
                response.body.contains("profan", ignoreCase = true)
            return@guarded GloCloudResult.Failure(
                if (rejected) GloCloudError.GreetingRejected
                else GloCloudError.ServiceUnavailable(response.statusCode),
            )
        }

        val json = response.body.toJsonObjectOrNull()
            ?: return@guarded GloCloudResult.Failure(GloCloudError.InvalidServerResponse)
        val signed = runCatching {
            SignedDisplayPayload(
                protobufPayload = json.findString("protobuf_payload") ?: error("missing payload"),
                challenge = json.findString("challenge") ?: error("missing challenge"),
                challengeSignature = json.findString("challenge_signature") ?: error("missing signature"),
                protobufPayloadHashSignature = json.findString("protobuf_payload_hash_signature")
                    ?: error("missing hash signature"),
            )
        }.getOrNull() ?: return@guarded GloCloudResult.Failure(GloCloudError.InvalidServerResponse)
        if (!signed.challenge.equals(request.challenge, ignoreCase = true)) {
            return@guarded GloCloudResult.Failure(GloCloudError.InvalidServerResponse)
        }
        GloCloudResult.Success(signed)
    }

    override suspend fun signOut(): GloCloudResult<Unit> = guarded {
        clearSessionLocked()
        GloCloudResult.Success(Unit)
    }

    override fun close() = Unit

    private suspend fun loadTenantConfigurationLocked(
        preferredLocale: String,
    ): GloCloudResult<GloTenantConfiguration> {
        val locale = preferredLocale.normalizedLocale()
        mutableTenantConfiguration.value?.takeIf { it.locale == locale }?.let {
            return GloCloudResult.Success(it)
        }
        val listResponse = http.request(
            path = TENANT_CONFIGURATIONS_PATH,
            method = "GET",
            headers = mapOf(PRODUCT_CATEGORY_HEADER to PRODUCT_CATEGORY),
        )
        if (!listResponse.isSuccessful) {
            return GloCloudResult.Failure(GloCloudError.ConfigurationUnavailable)
        }
        val listJson = listResponse.body.toJsonObjectOrNull()
            ?: return GloCloudResult.Failure(GloCloudError.ConfigurationUnavailable)
        val tenantId = selectTenantId(listJson, locale)
            ?: return GloCloudResult.Failure(GloCloudError.ConfigurationUnavailable)
        val detailResponse = http.request(
            path = "$TENANT_CONFIGURATIONS_PATH/$tenantId",
            method = "GET",
            headers = mapOf(PRODUCT_CATEGORY_HEADER to PRODUCT_CATEGORY),
        )
        if (!detailResponse.isSuccessful) {
            return GloCloudResult.Failure(GloCloudError.ConfigurationUnavailable)
        }
        val detailJson = detailResponse.body.toJsonObjectOrNull()
            ?: return GloCloudResult.Failure(GloCloudError.ConfigurationUnavailable)
        val configuration = parseTenantConfiguration(detailJson, tenantId, locale)
            ?: return GloCloudResult.Failure(GloCloudError.ConfigurationUnavailable)
        mutableTenantConfiguration.value = configuration
        return GloCloudResult.Success(configuration)
    }

    private suspend fun validatePinRequest(
        activeSession: CloudSession,
        pin: String,
        tenantUserId: String,
    ): OfficialGloHttpResponse = http.request(
        path = VALIDATE_PIN_PATH,
        method = "GET",
        headers = authenticatedHeaders(activeSession, includeVault = false) + mapOf(
            "X-pin" to pin,
            "X-tenant-user-id" to tenantUserId,
        ),
    )

    private suspend fun userInfoRequest(
        activeSession: CloudSession,
    ): OfficialGloHttpResponse {
        val encodedUserId = URLEncoder.encode(
            activeSession.userId,
            StandardCharsets.UTF_8.name(),
        ).replace("+", "%20")
        return http.request(
            path = "$USERS_PATH/$encodedUserId",
            method = "GET",
            headers = authenticatedHeaders(activeSession, includeVault = false),
        )
    }

    private suspend fun greetingRequest(
        activeSession: CloudSession,
        request: CustomGreetingRequest,
    ): OfficialGloHttpResponse {
        val body = JSONObject()
            .put("challenge", request.challenge)
            .put("intro", request.intro)
            .put("greeting", request.greeting)
            .put("outro", request.outro)
            .put("frame", request.frame.wireValue)
            .put("firmware_version", request.firmwareVersion)
            .put("device_type", request.deviceType)
            .toString()
        return http.request(
            path = CUSTOM_GREETING_PATH,
            method = "POST",
            headers = authenticatedHeaders(activeSession, includeVault = true),
            body = body,
        )
    }

    private suspend fun storeTenantUserIdRequest(
        activeSession: CloudSession,
        tenantUserId: String,
    ): OfficialGloHttpResponse = http.request(
        path = STORE_TENANT_USER_ID_PATH,
        method = "POST",
        headers = authenticatedHeaders(activeSession, includeVault = false) +
            ("X-tenant-user-id" to tenantUserId),
    )

    private suspend fun storePinRequest(
        activeSession: CloudSession,
        tenantUserId: String,
        encryptedPin: String,
    ): OfficialGloHttpResponse = http.request(
        path = STORE_PIN_PATH,
        method = "POST",
        headers = authenticatedHeaders(activeSession, includeVault = false) +
            ("X-tenant-user-id" to tenantUserId),
        body = JSONObject().put("encryptedPin", encryptedPin).toString(),
    )

    private suspend fun markUserAsPreviouslyLoggedInRequest(
        activeSession: CloudSession,
    ): OfficialGloHttpResponse {
        val encodedUserId = URLEncoder.encode(
            activeSession.userId,
            StandardCharsets.UTF_8.name(),
        ).replace("+", "%20")
        val userExtra = JSONObject()
            .put("key", EVER_LOGGED_KEY)
            .put("value", true)
            .put("createdAt", EVER_LOGGED_TIMESTAMP_FORMATTER.format(Instant.now()))
        val body = JSONObject()
            .put("userExtra", JSONArray().put(userExtra))
            .toString()
        return http.request(
            path = "$USERS_PATH/$encodedUserId",
            method = "PUT",
            headers = authenticatedHeaders(activeSession, includeVault = false),
            body = body,
        )
    }

    private suspend fun refreshSessionLocked(expired: CloudSession): CloudSession? {
        val refreshToken = expired.refreshToken?.takeIf(String::isNotBlank) ?: return null
        val response = http.request(
            path = REFRESH_TOKEN_PATH,
            method = "GET",
            headers = mapOf(
                "refresh-token" to refreshToken,
                "X-customer-id" to expired.userId,
                "X-tenant-id" to expired.tenantId,
                "X-user-os" to "ANDROID",
            ),
        )
        if (!response.isSuccessful) return null
        val json = response.body.toJsonObjectOrNull() ?: return null
        val customerToken = json.findString("customer_token")?.takeIf(String::isNotBlank) ?: return null
        val refreshed = CloudSession(
            userId = expired.userId,
            customerToken = customerToken,
            accessToken = json.findString("access_token")?.takeIf(String::isNotBlank)
                ?: expired.accessToken,
            refreshToken = json.findString("refreshToken")?.takeIf(String::isNotBlank)
                ?: json.findString("refresh_token")?.takeIf(String::isNotBlank)
                ?: expired.refreshToken,
            tenantId = expired.tenantId,
            tenantUserId = expired.tenantUserId,
            vaultPin = expired.vaultPin,
            vaultSetupRequired = expired.vaultSetupRequired,
        )
        sessionStore.save(refreshed)
        session = refreshed
        return refreshed
    }

    private fun authenticatedHeaders(
        activeSession: CloudSession,
        includeVault: Boolean,
    ): Map<String, String> = buildMap {
        put("Authorization", activeSession.customerToken)
        put("Content-Type", "application/json")
        put("X-user-os", "ANDROID")
        put("X-customer-id", activeSession.userId)
        put("X-tenant-id", activeSession.tenantId)
        if (includeVault) {
            activeSession.vaultPin?.let { put("X-pin", it) }
            activeSession.tenantUserId?.let { put("X-tenant-user-id", it) }
        }
    }

    private fun clearSessionLocked() {
        sessionStore.clear()
        session = null
        mutableAuthState.value = GloCloudAuthState.SignedOut
    }

    private suspend fun <T> guarded(
        block: suspend () -> GloCloudResult<T>,
    ): GloCloudResult<T> = try {
        operationMutex.withLock { block() }
    } catch (error: CancellationException) {
        throw error
    } catch (_: IOException) {
        GloCloudResult.Failure(GloCloudError.NetworkUnavailable)
    } catch (_: JSONException) {
        GloCloudResult.Failure(GloCloudError.InvalidServerResponse)
    } catch (_: IllegalArgumentException) {
        GloCloudResult.Failure(GloCloudError.InvalidServerResponse)
    } catch (_: Throwable) {
        GloCloudResult.Failure(GloCloudError.ServiceUnavailable())
    }

    private companion object {
        const val DEFAULT_LOCALE = "it_IT"
        const val PRODUCT_CATEGORY_HEADER = "x-product-category"
        const val PRODUCT_CATEGORY = "my_glo"
        const val TENANT_CONFIGURATIONS_PATH = "/tenants/configurations"
        const val VALIDATE_PIN_PATH = "/vault/pin/validate"
        const val USERS_PATH = "/endmarket/users"
        const val STORE_TENANT_USER_ID_PATH = "/vault/tenant/user-id/store"
        const val STORE_PIN_PATH = "/vault/pin/store"
        const val CUSTOM_GREETING_PATH = "/generate-custom-greeting"
        const val REFRESH_TOKEN_PATH = "/endmarket/refresh-token"
        const val EVER_LOGGED_KEY = "MYGLO_EVER_LOGGED"
        const val HTTP_UNAUTHORIZED = 401
        const val VAULT_PIN_LENGTH = 4
        val EVER_LOGGED_TIMESTAMP_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
                .withZone(ZoneOffset.UTC)
    }
}

private fun CloudSession?.toAuthState(): GloCloudAuthState = when {
    this == null -> GloCloudAuthState.SignedOut
    hasVerifiedVault -> GloCloudAuthState.Authenticated
    vaultSetupRequired -> GloCloudAuthState.VaultSetupRequired
    else -> GloCloudAuthState.VaultPinRequired
}

private fun String.normalizedLocale(): String = replace('-', '_').ifBlank { "it_IT" }

private fun selectTenantId(root: JSONObject, locale: String): String? {
    val tenants = root.optJSONArray("data") ?: return null
    val country = locale.substringAfter('_', missingDelimiterValue = "").uppercase()
    var countryMatch: String? = null
    for (index in 0 until tenants.length()) {
        val tenant = tenants.optJSONObject(index) ?: continue
        val tenantId = tenant.optString("tenantId").takeIf(String::isNotBlank) ?: continue
        val locales = tenant.optJSONArray("supported_locales")
        if (locales.containsObjectKey(locale)) return tenantId
        if (countryMatch == null && tenant.optString("country_iso").equals(country, ignoreCase = true)) {
            countryMatch = tenantId
        }
    }
    return countryMatch
}

private fun parseTenantConfiguration(
    root: JSONObject,
    fallbackTenantId: String,
    locale: String,
): GloTenantConfiguration? {
    val data = root.optJSONObject("data") ?: root
    val urlsByLocale = data.optJSONObject("urls") ?: return null
    val urls = urlsByLocale.optJSONObject(locale) ?: urlsByLocale.firstObjectValue() ?: return null
    val loginUrl = urls.optString("WEBVIEW_LOGIN").takeIf(String::isValidHttpsUrl) ?: return null
    val signUpUrl = urls.optString("WEBVIEW_SIGNUP").takeIf(String::isValidHttpsUrl) ?: return null
    val redirectUrl = urls.optString("WEBVIEW_REDIRECT_URL").takeIf(String::isValidHttpsUrl) ?: return null
    val greetingLimits = buildMap {
        val devices = data.optJSONArray("supported_devices") ?: JSONArray()
        for (index in 0 until devices.length()) {
            val device = devices.optJSONObject(index) ?: continue
            val profile = device.optString("profile").takeIf(String::isNotBlank) ?: continue
            val limit = device.optInt("my_greetings_max_chars", -1)
            if (limit > 0) put(profile, limit)
        }
    }
    return GloTenantConfiguration(
        tenantId = data.optString("tenantId").takeIf(String::isNotBlank) ?: fallbackTenantId,
        locale = locale,
        loginUrl = loginUrl,
        signUpUrl = signUpUrl,
        redirectUrl = redirectUrl,
        greetingLimits = greetingLimits,
        greetingExcludedCharactersPattern = data.optString("my_greetings_excluded_chars")
            .takeIf(String::isNotBlank),
        vaultQuestionIds = data.optJSONArray("vault_questions").toStringList(),
    )
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }
}

private fun JSONArray?.containsObjectKey(key: String): Boolean {
    if (this == null) return false
    for (index in 0 until length()) {
        if (optJSONObject(index)?.has(key) == true) return true
    }
    return false
}

private fun JSONObject.firstObjectValue(): JSONObject? {
    val keys = keys()
    while (keys.hasNext()) {
        optJSONObject(keys.next())?.let { return it }
    }
    return null
}

private fun String.isValidHttpsUrl(): Boolean = runCatching {
    val uri = URI(this)
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
}.getOrDefault(false)

private fun String.toJsonObjectOrNull(): JSONObject? =
    if (isBlank()) null else runCatching { JSONObject(this) }.getOrNull()

private fun String.sanitizeSecurityAnswer(): String =
    trim().lowercase(Locale.ROOT).replace(Regex("[^A-Za-z0-9_]"), "")

private fun OfficialGloHttpResponse.hasSuccessfulResultCode(): Boolean {
    if (body.isBlank()) return true
    val json = body.toJsonObjectOrNull() ?: return false
    return json.findInt("code")?.let { it == 0 } ?: true
}

private fun OfficialGloHttpResponse.vaultSetupRequiredFor(expectedUserId: String): Boolean? {
    val json = body.toJsonObjectOrNull() ?: return null
    val user = json.findObject("user") ?: return null
    val returnedUserId = user.optString("id")
    if (returnedUserId.isBlank() || returnedUserId != expectedUserId) return null
    val extras = user.optJSONArray("userExtra") ?: return true
    for (index in 0 until extras.length()) {
        val entry = extras.optJSONObject(index) ?: continue
        if (entry.optString("key") == "MYGLO_EVER_LOGGED") {
            return !entry.optString("value").equals("true", ignoreCase = true)
        }
    }
    return true
}

private fun JSONObject.findObject(key: String, depth: Int = 0): JSONObject? {
    if (depth > 6) return null
    optJSONObject(key)?.let { return it }
    val keys = keys()
    while (keys.hasNext()) {
        when (val child = opt(keys.next())) {
            is JSONObject -> child.findObject(key, depth + 1)?.let { return it }
            is JSONArray -> for (index in 0 until child.length()) {
                (child.opt(index) as? JSONObject)?.findObject(key, depth + 1)?.let { return it }
            }
        }
    }
    return null
}

private fun JSONObject.findString(key: String, depth: Int = 0): String? {
    if (depth > 6) return null
    if (has(key) && !isNull(key)) return optString(key).takeIf(String::isNotBlank)
    val keys = keys()
    while (keys.hasNext()) {
        when (val child = opt(keys.next())) {
            is JSONObject -> child.findString(key, depth + 1)?.let { return it }
            is JSONArray -> for (index in 0 until child.length()) {
                (child.opt(index) as? JSONObject)?.findString(key, depth + 1)?.let { return it }
            }
        }
    }
    return null
}

private fun JSONObject.findInt(key: String, depth: Int = 0): Int? {
    if (depth > 6) return null
    if (has(key) && !isNull(key)) {
        when (val value = opt(key)) {
            is Number -> return value.toInt()
            is String -> value.toIntOrNull()?.let { return it }
        }
    }
    val keys = keys()
    while (keys.hasNext()) {
        when (val child = opt(keys.next())) {
            is JSONObject -> child.findInt(key, depth + 1)?.let { return it }
            is JSONArray -> for (index in 0 until child.length()) {
                (child.opt(index) as? JSONObject)?.findInt(key, depth + 1)?.let { return it }
            }
        }
    }
    return null
}

private fun CustomGreetingRequest.isValid(configuration: GloTenantConfiguration?): Boolean {
    val values = listOf(intro, greeting, outro)
    val limit = configuration?.greetingLimit(deviceType) ?: 10
    if (challenge.isBlank() || challenge.length % 2 != 0 ||
        challenge.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }
    ) {
        return false
    }
    if (firmwareVersion.isBlank() || deviceType.isBlank() ||
        values.any { it.codePointCount(0, it.length) > limit }
    ) {
        return false
    }
    val excluded = configuration?.greetingExcludedCharactersPattern ?: return true
    return runCatching { Regex(excluded) }.getOrNull()?.let { pattern ->
        values.none(pattern::containsMatchIn)
    } ?: true
}
