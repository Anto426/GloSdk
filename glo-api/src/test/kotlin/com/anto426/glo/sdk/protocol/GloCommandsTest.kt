package com.anto426.glo.sdk.protocol

import com.anto426.glo.sdk.model.LockState
import org.junit.Assert.assertEquals
import org.junit.Test

class GloCommandsTest {
    @Test
    fun `uses captured payloads and confirmation modes`() {
        val lock = GloCommands.setLock(LockState.LOCKED)
        assertEquals("01", lock.payload.toHex())
        assertEquals(CommandResponseMode.NOTIFICATION_AFTER_WRITE, lock.responseMode)

        val find = GloCommands.startFind()
        assertEquals("01 3c", find.payload.toHex())

        val autoStartOff = GloCommands.setAutoStart(false)
        assertEquals("02 00", autoStartOff.payload.toHex())

        val display = GloCommands.readDisplayConfiguration()
        assertEquals("00 03", display.payload.toHex())
        assertEquals(GloCharacteristic.DEVICE_INFO, display.characteristic)
    }
}
