package com.anto426.glo.sdk.protocol

import com.anto426.glo.sdk.model.GloBytes
import com.anto426.glo.sdk.model.SignedDisplayPayload
import java.security.MessageDigest

/** Binary framing used by the device-management payload characteristics. */
public object SignedDisplayPayloadCodec {
    public const val CUSTOM_DISPLAY_PAYLOAD_CODE: Int = 1
    public const val CUSTOM_DISPLAY_FILE_OBJECT_ID: Int = 2
    public const val MAX_CHUNK_SIZE: Int = 512

    @JvmStatic
    public fun challengeRequest(payloadCode: Int = CUSTOM_DISPLAY_PAYLOAD_CODE): GloBytes {
        require(payloadCode in 0..0xffff) { "Payload code must fit in an unsigned 16-bit value" }
        return GloBytes.of(payloadCode ushr 8, payloadCode and 0xff)
    }

    @JvmStatic
    public fun decodeChallenge(response: GloBytes, expectedPayloadCode: Int): String {
        require(response.size >= 2) { "Challenge response is shorter than its payload code" }
        val actualCode = ((response[0].toInt() and 0xff) shl 8) or (response[1].toInt() and 0xff)
        require(actualCode == expectedPayloadCode) {
            "Challenge response belongs to payload code $actualCode, expected $expectedPayloadCode"
        }
        require(response.size > 2) { "Challenge response does not contain random data" }
        return response.toHex(separator = "")
    }

    @JvmStatic
    public fun combine(payload: SignedDisplayPayload): GloBytes = GloBytes.fromHex(
        payload.protobufPayload +
            payload.challenge +
            payload.challengeSignature +
            payload.protobufPayloadHashSignature,
    )

    @JvmStatic
    public fun chunkSize(negotiatedMtu: Int): Int =
        (negotiatedMtu - ATT_WRITE_OVERHEAD).coerceAtMost(MAX_CHUNK_SIZE).also {
            require(it > 0) { "Negotiated MTU is too small for a payload write" }
        }

    @JvmStatic
    public fun chunks(payload: GloBytes, negotiatedMtu: Int): List<GloBytes> {
        require(!payload.isEmpty) { "Signed display payload cannot be empty" }
        val bytes = payload.toByteArray()
        return bytes.asList().chunked(chunkSize(negotiatedMtu)).map { chunk ->
            GloBytes.copyOf(chunk.toByteArray())
        }
    }

    /** Version 1.0.0, length, custom-display object id, then the first five MD5 bytes. */
    @JvmStatic
    public fun versionHeader(payload: GloBytes): GloBytes {
        require(!payload.isEmpty) { "Signed display payload cannot be empty" }
        val length = payload.size
        val checksumPrefix = MessageDigest.getInstance("MD5").digest(payload.toByteArray()).copyOf(5)
        val header = ByteArray(VERSION_HEADER_LENGTH)
        header[0] = 1
        header[1] = 0
        header[2] = 0
        header[3] = 0
        header[4] = (length ushr 24).toByte()
        header[5] = (length ushr 16).toByte()
        header[6] = (length ushr 8).toByte()
        header[7] = length.toByte()
        header[8] = CUSTOM_DISPLAY_FILE_OBJECT_ID.toByte()
        checksumPrefix.copyInto(header, destinationOffset = 9)
        return GloBytes.copyOf(header)
    }

    private const val ATT_WRITE_OVERHEAD: Int = 3
    private const val VERSION_HEADER_LENGTH: Int = 14
}
