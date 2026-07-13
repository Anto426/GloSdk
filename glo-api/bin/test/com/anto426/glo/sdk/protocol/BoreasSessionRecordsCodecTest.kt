package com.anto426.glo.sdk.protocol

import com.anto426.glo.sdk.model.GloBytes
import com.anto426.glo.sdk.model.SessionExitReason
import com.anto426.glo.sdk.model.SessionHeatingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BoreasSessionRecordsCodecTest {
    @Test
    fun `maps every verified session exit code`() {
        val expected = mapOf(
            0 to SessionExitReason.COMPLETED,
            1 to SessionExitReason.HEATING_STOPPED,
            101 to SessionExitReason.BATTERY_HOT_BEFORE_SESSION,
            102 to SessionExitReason.BATTERY_EMPTY,
            103 to SessionExitReason.BATTERY_LOW,
            104 to SessionExitReason.THERMOCOUPLE_SPIKE,
            105 to SessionExitReason.THERMOCOUPLE_ERROR,
            106 to SessionExitReason.HEATING_ZONE_HOT,
            107 to SessionExitReason.HEATING_ZONE_HOT_BEFORE_SESSION,
            108 to SessionExitReason.BATTERY_CURRENT_SENSOR_ERROR,
            109 to SessionExitReason.HARDWARE_ERROR,
            110 to SessionExitReason.BOARD_TEMPERATURE_HIGH,
            111 to SessionExitReason.POWER_OVERLOAD,
            112 to SessionExitReason.TARGET_TEMPERATURE_DIFFERENCE,
            201 to SessionExitReason.BATTERY_DAMAGE,
            301 to SessionExitReason.BATTERY_DISCHARGE_CURRENT_HIGH,
            302 to SessionExitReason.BATTERY_HOT,
            303 to SessionExitReason.BATTERY_COLD,
            304 to SessionExitReason.END_OF_LIFE,
            305 to SessionExitReason.COLD_JUNCTION_HOT,
            306 to SessionExitReason.USB_HOT,
        )

        expected.forEach { (code, reason) ->
            assertEquals(reason, SessionExitReason.fromProtocolCode(code))
            assertEquals(code, reason.protocolCode)
        }
        assertEquals(SessionExitReason.UNKNOWN, SessionExitReason.fromProtocolCode(0xffff))
    }

    @Test
    fun `decodes legacy uint16 and Boreas uint32 counts`() {
        assertEquals(
            BoreasSessionRecordsPacket.RecordsCount(258, 2),
            BoreasSessionRecordsCodec.decode(GloBytes.fromHex("01 02")),
        )
        assertEquals(
            BoreasSessionRecordsPacket.RecordsCount(0x8000_0001L, 4),
            BoreasSessionRecordsCodec.decode(GloBytes.fromHex("80 00 00 01")),
        )
    }

    @Test
    fun `decodes stream boundary markers`() {
        assertEquals(
            BoreasSessionRecordsPacket.StartOfFile(42),
            BoreasSessionRecordsCodec.decode(GloBytes.fromHex("ff ff ff fe 00 00 00 2a")),
        )
        assertSame(
            BoreasSessionRecordsPacket.EndOfFile,
            BoreasSessionRecordsCodec.decode(GloBytes.fromHex("ff ff ff fd")),
        )
    }

    @Test
    fun `decodes one signed-temperature session record`() {
        val packet = BoreasSessionRecordsCodec.decode(RECORD_ONE)
        val record = (packet as BoreasSessionRecordsPacket.Record).value

        assertEquals(42, record.count)
        assertEquals(1_700_000_000L, record.startedAtEpochSeconds)
        assertEquals(300, record.durationSeconds)
        assertEquals(112, record.exitCode)
        assertEquals(SessionExitReason.TARGET_TEMPERATURE_DIFFERENCE, record.exitReason)
        assertEquals(SessionHeatingMode.BOOST, record.heatingMode)
        assertEquals(-10, record.zone1MaxTemperatureRaw)
        assertEquals(250, record.zone2MaxTemperatureRaw)
        assertEquals(-32768, record.batteryMaxTemperatureRaw)
        assertTrue(record.trusted)
        assertEquals(RECORD_ONE, record.raw)
    }

    @Test
    fun `decodes version four batch preserving every raw record`() {
        val packet = BoreasSessionRecordsCodec.decode(
            GloBytes.fromHex("04 ${RECORD_ONE.toHex()} ${RECORD_TWO.toHex()}"),
        )
        val records = (packet as BoreasSessionRecordsPacket.RecordBatch).values

        assertEquals(2, records.size)
        assertEquals(listOf(42L, 43L), records.map { it.count })
        assertEquals(SessionExitReason.BATTERY_DAMAGE, records[1].exitReason)
        assertEquals(SessionHeatingMode.STANDARD, records[1].heatingMode)
        assertFalse(records[1].trusted)
        assertEquals(RECORD_TWO, records[1].raw)
    }

    private companion object {
        val RECORD_ONE: GloBytes = GloBytes.fromHex(
            "00 00 00 2a 65 53 f1 00 01 2c 00 70 01 ff f6 00 fa 80 00 00",
        )
        val RECORD_TWO: GloBytes = GloBytes.fromHex(
            "00 00 00 2b 65 53 f2 00 00 f0 00 c9 00 00 64 00 c8 01 2c 01",
        )
    }
}
