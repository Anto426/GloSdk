package com.anto426.glo.sdk.android.internal

import com.anto426.glo.sdk.model.BondState
import com.anto426.glo.sdk.model.ConnectionOptions
import com.anto426.glo.sdk.model.DeviceId
import com.anto426.glo.sdk.model.DeviceModel
import com.anto426.glo.sdk.model.GloError
import com.anto426.glo.sdk.model.GloResult
import com.anto426.glo.sdk.transport.BleBondedDevice
import com.anto426.glo.sdk.transport.BleScanEvent
import com.anto426.glo.sdk.transport.BleScanRequest
import com.anto426.glo.sdk.transport.GloBleTransport
import com.anto426.glo.sdk.transport.GloGattConnection
import com.anto426.glo.sdk.transport.GloTransportException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidGloDeviceManagerTest {
    @Test
    fun `refresh exposes only recognized paired Boreas devices`() = runBlocking {
        val transport = FakeBleTransport(
            bonds = listOf(
                bonded("00:00:00:00:00:01", "Hyper Pro+ 1799"),
                bonded("00:00:00:00:00:02", "BOREAS test unit"),
                bonded("00:00:00:00:00:03", "glo Hyper Pro"),
                bonded("00:00:00:00:00:04", "Headphones"),
            ),
        )
        val manager = AndroidGloDeviceManager(transport)
        try {
            val refreshed = manager.refreshPairedDevices() as GloResult.Success

            assertEquals(2, refreshed.value.size)
            assertEquals(
                setOf(DeviceId("00:00:00:00:00:01"), DeviceId("00:00:00:00:00:02")),
                refreshed.value.mapTo(linkedSetOf()) { it.id },
            )
            assertTrue(refreshed.value.all { it.model == DeviceModel.BOREAS })
            assertTrue(refreshed.value.all { it.bondState == BondState.BONDED })
            assertEquals(refreshed.value, manager.pairedDevices.value)
        } finally {
            manager.close()
        }
    }

    @Test
    fun `connect resolves a paired Boreas device without a scan result`() = runBlocking {
        val id = DeviceId("00:00:00:00:00:01")
        val expectedError = GloError.ConnectionFailed(133, "test transport reached")
        val transport = FakeBleTransport(
            bonds = listOf(BleBondedDevice(id, "Hyper Pro Plus 1799", BondState.BONDED)),
            connectError = expectedError,
        )
        val manager = AndroidGloDeviceManager(transport)
        try {
            val result = manager.connect(id, ConnectionOptions(requireBond = false))

            assertEquals(expectedError, (result as GloResult.Failure).error)
            assertEquals(listOf(id), transport.connectCalls)
        } finally {
            manager.close()
        }
    }

    @Test
    fun `unknown paired devices are neither exposed nor connectable`() = runBlocking {
        val id = DeviceId("00:00:00:00:00:09")
        val transport = FakeBleTransport(bonds = listOf(bonded(id.value, "Other device")))
        val manager = AndroidGloDeviceManager(transport)
        try {
            assertEquals(emptyList<Any>(), (manager.refreshPairedDevices() as GloResult.Success).value)

            val result = manager.connect(id)

            assertEquals(GloError.DeviceNotFound(id), (result as GloResult.Failure).error)
            assertTrue(transport.connectCalls.isEmpty())
        } finally {
            manager.close()
        }
    }

    private fun bonded(address: String, name: String): BleBondedDevice =
        BleBondedDevice(DeviceId(address), name, BondState.BONDED)
}

private class FakeBleTransport(
    private val bonds: List<BleBondedDevice>,
    private val connectError: GloError = GloError.Unexpected("Unexpected fake connection"),
) : GloBleTransport {
    override val scanEvents: Flow<BleScanEvent> = emptyFlow()
    val connectCalls = mutableListOf<DeviceId>()

    override suspend fun bondedDevices(): List<BleBondedDevice> = bonds

    override suspend fun startScan(request: BleScanRequest) = Unit

    override suspend fun stopScan() = Unit

    override suspend fun connect(
        deviceId: DeviceId,
        options: ConnectionOptions,
    ): GloGattConnection {
        connectCalls += deviceId
        throw GloTransportException(connectError)
    }

    override fun close() = Unit
}
