package com.anto426.glo.sdk.api.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SensitiveCloudModelsTest {
    @Test
    fun `greeting request never prints private fields`() {
        val request = CustomGreetingRequest(
            challenge = "private-challenge",
            intro = "private-intro",
            greeting = "private-greeting",
            outro = "private-outro",
            frame = CustomGreetingFrame.GREETING,
            firmwareVersion = "1.2.3",
            deviceType = "Boreas",
        )

        assertEquals("CustomGreetingRequest(redacted)", request.toString())
        assertFalse(request.toString().contains("private"))
    }
}
