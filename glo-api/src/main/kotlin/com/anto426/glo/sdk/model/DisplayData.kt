package com.anto426.glo.sdk.model

/** The three user-visible text slots exposed by the device display configuration. */
public enum class DisplaySlot(public val protocolName: String) {
    STARTUP("Intro"),
    READY("Greeting"),
    SESSION_END("Outro"),
    ;

    public companion object {
        @JvmStatic
        public fun fromProtocolName(value: String): DisplaySlot? =
            entries.firstOrNull { it.protocolName.equals(value, ignoreCase = true) }
    }
}

public data class DisplayMessage(
    public val slot: DisplaySlot,
    public val text: String,
) {
    init {
        require(text.length <= MAX_TEXT_LENGTH) { "Display text cannot exceed $MAX_TEXT_LENGTH characters" }
    }

    override fun toString(): String = "DisplayMessage(slot=$slot, text=redacted)"

    public companion object {
        public const val MAX_TEXT_LENGTH: Int = 15
    }
}

public data class DisplayConfiguration(
    public val version: String,
    public val messages: Map<DisplaySlot, String>,
) {
    public operator fun get(slot: DisplaySlot): String = messages[slot].orEmpty()

    override fun toString(): String = "DisplayConfiguration(version=$version, messages=redacted)"
}

/**
 * Opaque fields returned by the official greeting-signing service.
 *
 * The SDK deliberately does not expose a way to alter these bytes after signing. A payload is
 * accepted for upload only when [challenge] still matches the device's current challenge.
 */
public class SignedDisplayPayload(
    public val protobufPayload: String,
    public val challenge: String,
    public val challengeSignature: String,
    public val protobufPayloadHashSignature: String,
) {
    init {
        requireHex("protobufPayload", protobufPayload)
        requireHex("challenge", challenge)
        requireHex("challengeSignature", challengeSignature)
        requireHex("protobufPayloadHashSignature", protobufPayloadHashSignature)
    }

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is SignedDisplayPayload &&
                protobufPayload == other.protobufPayload &&
                challenge == other.challenge &&
                challengeSignature == other.challengeSignature &&
                protobufPayloadHashSignature == other.protobufPayloadHashSignature
            )

    override fun hashCode(): Int {
        var result = protobufPayload.hashCode()
        result = 31 * result + challenge.hashCode()
        result = 31 * result + challengeSignature.hashCode()
        result = 31 * result + protobufPayloadHashSignature.hashCode()
        return result
    }

    /** Deliberately omits all signed material. */
    override fun toString(): String = "SignedDisplayPayload(redacted)"
}

public sealed interface DisplayUploadProgress {
    public data object Preparing : DisplayUploadProgress

    public data class Sending(
        public val sentChunks: Int,
        public val totalChunks: Int,
    ) : DisplayUploadProgress {
        init {
            require(totalChunks > 0) { "The upload must contain at least one chunk" }
            require(sentChunks in 0..totalChunks) { "Sent chunk count is out of range" }
        }
    }

    public data object Verifying : DisplayUploadProgress
}

private fun requireHex(label: String, value: String) {
    require(value.isNotBlank()) { "$label cannot be blank" }
    require(value.length % 2 == 0) { "$label must contain an even number of hexadecimal digits" }
    require(value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
        "$label contains a non-hexadecimal character"
    }
}
