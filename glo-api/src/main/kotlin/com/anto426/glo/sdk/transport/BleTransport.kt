package com.anto426.glo.sdk.transport

import com.anto426.glo.sdk.model.BondState
import com.anto426.glo.sdk.model.ConnectionOptions
import com.anto426.glo.sdk.model.DeviceId
import com.anto426.glo.sdk.model.DeviceModel
import com.anto426.glo.sdk.model.GloBytes
import com.anto426.glo.sdk.model.GloError
import com.anto426.glo.sdk.protocol.GloUuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

public class GloTransportException(public val error: GloError) : Exception(error.toString())

public data class BleScanRequest(
    public val serviceUuids: Set<GloUuid>,
    public val lowLatency: Boolean,
)

/** A device reported as paired by the platform Bluetooth stack. */
public data class BleBondedDevice(
    public val deviceId: DeviceId,
    public val name: String?,
    public val bondState: BondState,
)

public sealed interface BleScanEvent {
    public data class Result(
        public val deviceId: DeviceId,
        public val name: String?,
        public val model: DeviceModel,
        public val rssi: Int,
        public val connectable: Boolean,
        public val observedAtEpochMillis: Long,
    ) : BleScanEvent

    public data class Failed(public val platformCode: Int) : BleScanEvent
}

public enum class TransportConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
}

public enum class GattWriteType {
    WITH_RESPONSE,
    WITHOUT_RESPONSE,
}

public enum class GattProperty {
    READ,
    WRITE,
    WRITE_WITHOUT_RESPONSE,
    NOTIFY,
    INDICATE,
}

public data class GattCharacteristicDescription(
    public val uuid: GloUuid,
    public val properties: Set<GattProperty>,
)

public data class GattServiceDescription(
    public val uuid: GloUuid,
    public val characteristics: List<GattCharacteristicDescription>,
)

public data class GattDatabase(public val services: List<GattServiceDescription>) {
    public fun containsService(uuid: GloUuid): Boolean = services.any { it.uuid == uuid }

    public fun findCharacteristic(uuid: GloUuid): GattCharacteristicDescription? =
        services.asSequence().flatMap { it.characteristics.asSequence() }.firstOrNull { it.uuid == uuid }
}

public data class GattNotification(
    public val sequence: Long,
    public val characteristic: GloUuid,
    public val value: GloBytes,
)

/** Platform adapter implemented by `glo-ble-android`; useful for tests or other BLE stacks. */
public interface GloBleTransport : AutoCloseable {
    public val scanEvents: Flow<BleScanEvent>

    public suspend fun bondedDevices(): List<BleBondedDevice>

    public suspend fun startScan(request: BleScanRequest)

    public suspend fun stopScan()

    public suspend fun connect(deviceId: DeviceId, options: ConnectionOptions): GloGattConnection
}

public interface GloGattConnection : AutoCloseable {
    public val deviceId: DeviceId
    public val state: StateFlow<TransportConnectionState>
    public val bondState: StateFlow<BondState>
    public val notificationSequence: StateFlow<Long>
    public val notifications: Flow<GattNotification>

    public suspend fun ensureBond(timeoutMillis: Long)

    public suspend fun requestMtu(mtu: Int, timeoutMillis: Long): Int

    public suspend fun discoverServices(timeoutMillis: Long): GattDatabase

    public suspend fun read(characteristic: GloUuid, timeoutMillis: Long): GloBytes

    public suspend fun write(
        characteristic: GloUuid,
        value: GloBytes,
        writeType: GattWriteType,
        timeoutMillis: Long,
    )

    public suspend fun setNotifications(
        characteristic: GloUuid,
        enabled: Boolean,
        timeoutMillis: Long,
    )

    public suspend fun disconnect()
}
