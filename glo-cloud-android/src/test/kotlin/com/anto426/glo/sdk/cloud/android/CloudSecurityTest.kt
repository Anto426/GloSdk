package com.anto426.glo.sdk.cloud.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CloudSecurityTest {
    @Test
    fun `parses only the expected myglo callback`() {
        val callback = WebLoginCallbackParser.parse(
            "myglo://open?userId=user%201&customer_token=customer%2Btoken&" +
                "access_token=access&refreshToken=refresh",
        )
        assertNotNull(callback)
        assertEquals("user 1", callback?.userId)
        assertEquals("customer+token", callback?.customerToken)
        assertEquals("access", callback?.accessToken)
        assertEquals("refresh", callback?.refreshToken)
        assertNotNull(
            WebLoginCallbackParser.parse(
                "myglo://open?userId=user&customer_token=customer",
            ),
        )
        assertNull(
            WebLoginCallbackParser.parse(
                "https://example.com/open?userId=u&customer_token=c&access_token=a",
            ),
        )
    }

    @Test
    fun `vault identity is stable lowercase hmac sha256`() {
        val first = VaultIdentity.from("customer-id", "1234")
        val second = VaultIdentity.from("customer-id", "1234")
        assertEquals(first, second)
        assertEquals(64, first.length)
        assertEquals(first.lowercase(), first)
    }

    @Test
    fun `vault pin encryption matches CryptoJS OpenSSL format`() {
        val encrypted = CryptoJsAesPassphrase.encrypt(
            plaintext = "1234",
            passphrase = "answerVAULT_STEP_QUESTION_1",
            salt = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7),
        )

        assertEquals("U2FsdGVkX18AAQIDBAUGB9baqpkb5z2kNutc876dnHI=", encrypted)
    }

}
