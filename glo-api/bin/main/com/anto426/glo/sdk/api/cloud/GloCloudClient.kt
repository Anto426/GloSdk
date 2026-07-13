package com.anto426.glo.sdk.api.cloud

import com.anto426.glo.sdk.model.SignedDisplayPayload
import kotlinx.coroutines.flow.StateFlow

public sealed interface GloCloudAuthState {
    public data object SignedOut : GloCloudAuthState

    /** The web login succeeded; the existing secure-vault PIN must still be verified. */
    public data object VaultPinRequired : GloCloudAuthState

    /** The official account is new and its secure vault must be created. */
    public data object VaultSetupRequired : GloCloudAuthState

    public data object Authenticated : GloCloudAuthState
}

public data class GloTenantConfiguration(
    public val tenantId: String,
    public val locale: String,
    public val loginUrl: String,
    public val signUpUrl: String,
    public val redirectUrl: String,
    public val greetingLimits: Map<String, Int>,
    public val greetingExcludedCharactersPattern: String?,
    public val vaultQuestionIds: List<String>,
) {
    public fun greetingLimit(deviceType: String, fallback: Int = 10): Int =
        greetingLimits[deviceType]?.takeIf { it > 0 } ?: fallback
}

public enum class CustomGreetingFrame(public val wireValue: String) {
    INTRO("intro"),
    GREETING("greeting"),
    OUTRO("outro"),
}

/** All three texts are required by the official signing endpoint for every frame update. */
public data class CustomGreetingRequest(
    public val challenge: String,
    public val intro: String,
    public val greeting: String,
    public val outro: String,
    public val frame: CustomGreetingFrame,
    public val firmwareVersion: String,
    public val deviceType: String,
) {
    /** Never expose the challenge or the three private display texts through incidental logging. */
    override fun toString(): String = "CustomGreetingRequest(redacted)"
}

public sealed interface GloCloudError {
    public data object NetworkUnavailable : GloCloudError
    public data object ConfigurationUnavailable : GloCloudError
    public data object InvalidLoginCallback : GloCloudError
    public data object AuthenticationRequired : GloCloudError
    public data object VaultPinRequired : GloCloudError
    public data class InvalidVaultPin(public val attemptsRemaining: Int? = null) : GloCloudError
    public data object InvalidVaultSetup : GloCloudError
    public data object GreetingRejected : GloCloudError
    public data object InvalidServerResponse : GloCloudError
    public data class ServiceUnavailable(public val statusCode: Int? = null) : GloCloudError
}

public sealed interface GloCloudResult<out T> {
    public data class Success<T>(public val value: T) : GloCloudResult<T>
    public data class Failure(public val error: GloCloudError) : GloCloudResult<Nothing>
}

/** Official myglo cloud boundary. Credentials are entered only on the official web page. */
public interface GloCloudClient : AutoCloseable {
    public val authState: StateFlow<GloCloudAuthState>
    public val tenantConfiguration: StateFlow<GloTenantConfiguration?>

    public suspend fun loadTenantConfiguration(
        preferredLocale: String = "it_IT",
    ): GloCloudResult<GloTenantConfiguration>

    /** Consumes `myglo://open`; the callback itself is never persisted. */
    public suspend fun completeWebLogin(callbackUrl: String): GloCloudResult<Unit>

    /** Validates an already-created official secure-vault PIN. */
    public suspend fun validateVaultPin(pin: String): GloCloudResult<Unit>

    /** Creates the official secure vault for a newly registered account. */
    public suspend fun createVault(
        pin: String,
        securityQuestionId: String,
        securityAnswer: String,
    ): GloCloudResult<Unit>

    public suspend fun generateCustomGreeting(
        request: CustomGreetingRequest,
    ): GloCloudResult<SignedDisplayPayload>

    /** Erases the local encrypted session and all locally retained cloud credentials. */
    public suspend fun signOut(): GloCloudResult<Unit>
}
