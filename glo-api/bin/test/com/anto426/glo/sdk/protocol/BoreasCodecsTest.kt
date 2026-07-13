package com.anto426.glo.sdk.protocol

import com.anto426.glo.sdk.model.DeviceAuthenticity
import com.anto426.glo.sdk.model.FindState
import com.anto426.glo.sdk.model.GloBytes
import com.anto426.glo.sdk.model.HeatingProfile
import com.anto426.glo.sdk.model.LockState
import org.junit.Assert.assertEquals
import org.junit.Test

class BoreasCodecsTest {
    @Test
    fun `decodes dynamically captured battery`() {
        val battery = BoreasBatteryCodec.decode(GloBytes.fromHex("63 00 13 00"))

        assertEquals(99, battery.levelPercent)
        assertEquals(0, battery.chargingState.value)
        assertEquals(19, battery.remainingSessions)
        assertEquals(DeviceAuthenticity.AUTHENTIC, battery.authenticity)
    }

    @Test
    fun `decodes dynamically captured session status`() {
        val status = BoreasSessionStatusCodec.decode(
            GloBytes.fromHex("00 00 01 01 00 01 f4 00 03"),
        )

        assertEquals(0, status.sessionStateCode)
        assertEquals(0, status.deviceStateCode)
        assertEquals(true, status.autoStart)
        assertEquals(true, status.autoStop)
        assertEquals(500, status.cleaningThreshold)
        assertEquals(3, status.cleaningCount)
    }

    @Test
    fun `decodes captured Boreas device info with correct field boundaries`() {
        val info = BoreasDeviceInfoCodec.decode(
            GloBytes.fromHex(
                "07 03 f3 bb 03 00 4d 42 32 33 42 41 54 34 33 32 31 37 39 39 00 00 00 05 05 00 ee 6c",
            ),
        )

        assertEquals("7.3", info.firmwareVersion)
        assertEquals(0xf3bb, info.softwareRevision)
        assertEquals(0x0300, info.boardClassification)
        assertEquals("MB23BAT4321799", info.serialNumber)
        assertEquals(5L, info.bootloaderVersion)
        assertEquals("5.0", info.bleFirmwareVersion)
        assertEquals(0xee6c, info.bleSoftwareRevision)
    }

    @Test
    fun `round trips simple packets`() {
        assertEquals(LockState.LOCKED, LockStateCodec.decode(LockStateCodec.encode(LockState.LOCKED)))
        assertEquals(
            HeatingProfile.BOOST,
            HeatingProfileCodec.decode(HeatingProfileCodec.encode(HeatingProfile.BOOST)),
        )
        assertEquals(
            FindState(true, 60),
            FindStateCodec.decode(FindStateCodec.encode(FindState(true, 60))),
        )
        assertEquals(1_784_024_780L, EpochSecondsCodec.decode(EpochSecondsCodec.encode(1_784_024_780L)))
    }
}
