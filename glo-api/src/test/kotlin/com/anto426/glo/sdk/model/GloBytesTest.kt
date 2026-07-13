package com.anto426.glo.sdk.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GloBytesTest {
    @Test
    fun `copies source and returned arrays`() {
        val source = byteArrayOf(1, 2, 3)
        val value = GloBytes.copyOf(source)
        source[0] = 9
        val returned = value.toByteArray()
        returned[1] = 9

        assertArrayEquals(byteArrayOf(1, 2, 3), value.toByteArray())
    }

    @Test
    fun `uses binary content equality`() {
        assertEquals(GloBytes.fromHex("01 02 ff"), GloBytes.of(1, 2, 255))
        assertNotEquals(GloBytes.of(1), GloBytes.of(2))
        assertEquals("01:02:ff", GloBytes.of(1, 2, 255).toHex(":"))
    }
}
