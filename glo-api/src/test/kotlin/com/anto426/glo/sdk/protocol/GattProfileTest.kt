package com.anto426.glo.sdk.protocol

import com.anto426.glo.sdk.model.DeviceModel
import org.junit.Assert.assertEquals
import org.junit.Test

class GattProfileTest {
    private val boreas = GattProfile.forModel(DeviceModel.BOREAS)

    @Test
    fun `builds verified Boreas UUIDs`() {
        assertEquals(
            "6cd6c8b5-e378-0204-000a-1b9740683449",
            boreas.serviceUuid(GloService.SESSION).toString(),
        )
        assertEquals(
            "6cd6c8b5-e378-0204-030a-1b9740683449",
            boreas.characteristicUuid(GloCharacteristic.BATTERY).toString(),
        )
        assertEquals(
            "6cd6c8b5-e378-0204-040c-1b9740683449",
            boreas.characteristicUuid(GloCharacteristic.PAYLOAD_CHALLENGE).toString(),
        )
    }

    @Test
    fun `detects model from session service`() {
        assertEquals(
            DeviceModel.BOREAS,
            GattProfile.detectModel(boreas.serviceUuid(GloService.SESSION)),
        )
        assertEquals(DeviceModel.UNKNOWN, GattProfile.detectModel(OtaUuids.SERVICE))
    }
}
