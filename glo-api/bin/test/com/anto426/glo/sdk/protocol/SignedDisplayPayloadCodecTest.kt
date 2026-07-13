package com.anto426.glo.sdk.protocol

import com.anto426.glo.sdk.model.GloBytes
import com.anto426.glo.sdk.model.SignedDisplayPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignedDisplayPayloadCodecTest {
    @Test
    fun `encodes challenge request and preserves full response`() {
        assertEquals("0001", SignedDisplayPayloadCodec.challengeRequest().toHex(""))
        assertEquals(
            "0001aabbccdd",
            SignedDisplayPayloadCodec.decodeChallenge(GloBytes.fromHex("00 01 aa bb cc dd"), 1),
        )
    }

    @Test
    fun `combines signed fields in protocol order and chunks by mtu`() {
        val combined = SignedDisplayPayloadCodec.combine(
            SignedDisplayPayload(
                protobufPayload = "0102",
                challenge = "0304",
                challengeSignature = "0506",
                protobufPayloadHashSignature = "0708",
            ),
        )
        assertEquals("0102030405060708", combined.toHex(""))
        assertEquals(listOf(4, 4), SignedDisplayPayloadCodec.chunks(combined, 7).map { it.size })
        assertEquals(512, SignedDisplayPayloadCodec.chunkSize(517))
    }

    @Test
    fun `version header contains length object id and five checksum bytes`() {
        val payload = GloBytes.fromHex("01 02 03 04")
        val header = SignedDisplayPayloadCodec.versionHeader(payload)
        assertEquals(14, header.size)
        assertTrue(header.toHex("").startsWith("010000000000000402"))
        assertEquals("08d6c05a21", header.toHex("").takeLast(10))
    }
}
