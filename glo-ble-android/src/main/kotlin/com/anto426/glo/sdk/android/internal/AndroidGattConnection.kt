package com.anto426.glo.sdk.android.internal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.anto426.glo.sdk.android.AndroidBlePermissions
import com.anto426.glo.sdk.model.BondState
import com.anto426.glo.sdk.model.ConnectionOptions
import com.anto426.glo.sdk.model.DeviceId
import com.anto426.glo.sdk.model.GloBytes
import com.anto426.glo.sdk.model.GloError
import com.anto426.glo.sdk.protocol.GloUuid
import com.anto426.glo.sdk.protocol.StandardUuids
import com.anto426.glo.sdk.transport.GattCharacteristicDescription
import com.anto426.glo.sdk.transport.GattDatabase
import com.anto426.glo.sdk.transport.GattNotification
import com.anto426.glo.sdk.transport.GattProperty
import com.anto426.glo.sdk.transport.GattServiceDescription
import com.anto426.glo.sdk.transport.GattWriteType
import com.anto426.glo.sdk.transport.GloGattConnection
import com.anto426.glo.sdk.transport.GloTransportException
import com.anto426.glo.sdk.transport.TransportConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal class AndroidGattConnection(
    private val context: Context,
    private val device: BluetoothDevice,
) : GloGattConnection {
    override val deviceId: DeviceId = DeviceId(device.address)

    private val mutableState = MutableStateFlow(TransportConnectionState.DISCONNECTED)
    override val state: StateFlow<TransportConnectionState> = mutableState.asStateFlow()

    private val mutableBondState = MutableStateFlow(device.bondState.toSdkBondState())
    override val bondState: StateFlow<BondState> = mutableBondState.asStateFlow()

    private val mutableNotifications = MutableSharedFlow<GattNotification>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val notifications: Flow<GattNotification> = mutableNotifications.asSharedFlow()
    private val notificationCounter = AtomicLong(0L)
    private val mutableNotificationSequence = MutableStateFlow(0L)
    override val notificationSequence: StateFlow<Long> = mutableNotificationSequence.asStateFlow()

    private val operationMutex = Mutex()
    private val pendingLock = Any()
    private val connected = CompletableDeferred<Unit>()
    private val disconnected = CompletableDeferred<Unit>()

    private var gatt: BluetoothGatt? = null
    private var closed = false
    private var pendingMtu: PendingMtu? = null
    private var pendingServices: PendingServices? = null
    private var pendingRead: PendingRead? = null
    private var pendingWrite: PendingWrite? = null
    private var pendingDescriptor: PendingDescriptor? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        mutableState.value = TransportConnectionState.CONNECTED
                        connected.complete(Unit)
                    } else {
                        failConnection(status, "Connected callback returned an error")
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    mutableState.value = TransportConnectionState.DISCONNECTED
                    disconnected.complete(Unit)
                    if (!connected.isCompleted) {
                        failConnection(status, "Disconnected before the connection became ready")
                    }
                    failPending(GloTransportException(GloError.NotConnected))
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            synchronized(pendingLock) {
                pendingMtu?.deferred?.completeGatt(status, "requestMtu") { mtu }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            synchronized(pendingLock) {
                pendingServices?.deferred?.completeGatt(status, "discoverServices") {
                    gatt.services.toGattDatabase()
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            completeRead(characteristic.uuid, characteristic.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            completeRead(characteristic.uuid, value, status)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            synchronized(pendingLock) {
                val pending = pendingWrite
                if (pending?.uuid == characteristic.uuid) {
                    pending.deferred.completeGatt(status, "write ${characteristic.uuid}") {}
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            synchronized(pendingLock) {
                val pending = pendingDescriptor
                if (pending?.uuid == descriptor.uuid) {
                    pending.deferred.completeGatt(status, "write descriptor ${descriptor.uuid}") {}
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            emitNotification(characteristic.uuid, characteristic.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            emitNotification(characteristic.uuid, value)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun open(options: ConnectionOptions) {
        check(!closed) { "Connection is closed" }
        mutableState.value = TransportConnectionState.CONNECTING
        try {
            gatt = device.connectGatt(
                context,
                false,
                callback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_2M_MASK,
            ) ?: throw GloTransportException(
                GloError.ConnectionFailed(null, "connectGatt returned null"),
            )
            await(connected, options.connectionTimeoutMillis, "connect")
        } catch (error: SecurityException) {
            throw permissionFailure(error)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun ensureBond(timeoutMillis: Long) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            mutableBondState.value = BondState.BONDED
            return
        }

        val deferred = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val changedDevice = intent.bluetoothDeviceExtra() ?: return
                if (changedDevice.address != device.address) return
                val newState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                mutableBondState.value = newState.toSdkBondState()
                when {
                    newState == BluetoothDevice.BOND_BONDED -> deferred.complete(Unit)
                    newState == BluetoothDevice.BOND_NONE && previous == BluetoothDevice.BOND_BONDING -> {
                        deferred.completeExceptionally(
                            GloTransportException(GloError.BondingFailed("Android rejected the bond")),
                        )
                    }
                }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
            when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> {
                    mutableBondState.value = BondState.BONDED
                    deferred.complete(Unit)
                }

                BluetoothDevice.BOND_NONE -> if (!device.createBond()) {
                    throw GloTransportException(GloError.BondingFailed("createBond returned false"))
                }
            }
            if (!deferred.isCompleted) {
                mutableBondState.value = BondState.BONDING
                await(deferred, timeoutMillis, "bond")
            }
        } catch (error: SecurityException) {
            throw permissionFailure(error)
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
                // Receiver was not registered or was already removed.
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun requestMtu(mtu: Int, timeoutMillis: Long): Int = operationMutex.withLock {
        val currentGatt = requireConnectedGatt()
        val pending = PendingMtu(CompletableDeferred())
        synchronized(pendingLock) { pendingMtu = pending }
        try {
            if (!currentGatt.requestMtu(mtu)) reject("requestMtu")
            await(pending.deferred, timeoutMillis, "requestMtu")
        } finally {
            synchronized(pendingLock) { if (pendingMtu === pending) pendingMtu = null }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun discoverServices(timeoutMillis: Long): GattDatabase = operationMutex.withLock {
        val currentGatt = requireConnectedGatt()
        val pending = PendingServices(CompletableDeferred())
        synchronized(pendingLock) { pendingServices = pending }
        try {
            if (!currentGatt.discoverServices()) reject("discoverServices")
            await(pending.deferred, timeoutMillis, "discoverServices")
        } finally {
            synchronized(pendingLock) { if (pendingServices === pending) pendingServices = null }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun read(characteristic: GloUuid, timeoutMillis: Long): GloBytes = operationMutex.withLock {
        val currentGatt = requireConnectedGatt()
        val nativeCharacteristic = currentGatt.findCharacteristic(characteristic.value)
            ?: throw GloTransportException(
                GloError.GattFailure("read", null, "Characteristic $characteristic was not discovered"),
            )
        val pending = PendingRead(characteristic.value, CompletableDeferred())
        synchronized(pendingLock) { pendingRead = pending }
        try {
            if (!currentGatt.readCharacteristic(nativeCharacteristic)) reject("read $characteristic")
            await(pending.deferred, timeoutMillis, "read $characteristic")
        } finally {
            synchronized(pendingLock) { if (pendingRead === pending) pendingRead = null }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun write(
        characteristic: GloUuid,
        value: GloBytes,
        writeType: GattWriteType,
        timeoutMillis: Long,
    ): Unit = operationMutex.withLock {
        val currentGatt = requireConnectedGatt()
        val nativeCharacteristic = currentGatt.findCharacteristic(characteristic.value)
            ?: throw GloTransportException(
                GloError.GattFailure("write", null, "Characteristic $characteristic was not discovered"),
            )
        val nativeWriteType = when (writeType) {
            GattWriteType.WITH_RESPONSE -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            GattWriteType.WITHOUT_RESPONSE -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val pending = PendingWrite(characteristic.value, CompletableDeferred())
        synchronized(pendingLock) { pendingWrite = pending }
        try {
            val accepted = if (Build.VERSION.SDK_INT >= 33) {
                currentGatt.writeCharacteristic(
                    nativeCharacteristic,
                    value.toByteArray(),
                    nativeWriteType,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                nativeCharacteristic.writeType = nativeWriteType
                @Suppress("DEPRECATION")
                nativeCharacteristic.value = value.toByteArray()
                @Suppress("DEPRECATION")
                currentGatt.writeCharacteristic(nativeCharacteristic)
            }
            if (!accepted) reject("write $characteristic")
            await(pending.deferred, timeoutMillis, "write $characteristic")
        } finally {
            synchronized(pendingLock) { if (pendingWrite === pending) pendingWrite = null }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun setNotifications(
        characteristic: GloUuid,
        enabled: Boolean,
        timeoutMillis: Long,
    ): Unit = operationMutex.withLock {
        val currentGatt = requireConnectedGatt()
        val nativeCharacteristic = currentGatt.findCharacteristic(characteristic.value)
            ?: throw GloTransportException(
                GloError.GattFailure("notifications", null, "Characteristic $characteristic was not discovered"),
            )
        if (!currentGatt.setCharacteristicNotification(nativeCharacteristic, enabled)) {
            reject("setCharacteristicNotification $characteristic")
        }
        val descriptor = nativeCharacteristic.getDescriptor(StandardUuids.CLIENT_CHARACTERISTIC_CONFIGURATION.value)
            ?: throw GloTransportException(
                GloError.GattFailure("notifications", null, "CCCD missing for $characteristic"),
            )
        @Suppress("DEPRECATION")
        val descriptorValue = when {
            !enabled -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            (nativeCharacteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ->
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            (nativeCharacteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 ->
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> throw GloTransportException(
                GloError.GattFailure("notifications", null, "$characteristic cannot notify or indicate"),
            )
        }
        val pending = PendingDescriptor(descriptor.uuid, CompletableDeferred())
        synchronized(pendingLock) { pendingDescriptor = pending }
        try {
            val accepted = if (Build.VERSION.SDK_INT >= 33) {
                currentGatt.writeDescriptor(descriptor, descriptorValue) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = descriptorValue
                @Suppress("DEPRECATION")
                currentGatt.writeDescriptor(descriptor)
            }
            if (!accepted) reject("write CCCD for $characteristic")
            await(pending.deferred, timeoutMillis, "write CCCD for $characteristic")
        } finally {
            synchronized(pendingLock) { if (pendingDescriptor === pending) pendingDescriptor = null }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun disconnect() {
        if (closed || mutableState.value == TransportConnectionState.DISCONNECTED) {
            close()
            return
        }
        mutableState.value = TransportConnectionState.DISCONNECTING
        try {
            gatt?.disconnect()
            withTimeoutOrNull(DISCONNECT_TIMEOUT_MILLIS) { disconnected.await() }
        } catch (error: SecurityException) {
            throw permissionFailure(error)
        } finally {
            close()
        }
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        if (closed) return
        closed = true
        try {
            gatt?.close()
        } catch (_: SecurityException) {
            // No useful recovery is possible while closing.
        }
        gatt = null
        mutableState.value = TransportConnectionState.DISCONNECTED
        failPending(GloTransportException(GloError.NotConnected))
    }

    private fun completeRead(uuid: UUID, value: ByteArray, status: Int) {
        synchronized(pendingLock) {
            val pending = pendingRead
            if (pending?.uuid == uuid) {
                pending.deferred.completeGatt(status, "read $uuid") { GloBytes.copyOf(value) }
            }
        }
    }

    private fun emitNotification(uuid: UUID, value: ByteArray) {
        val sequence = notificationCounter.incrementAndGet()
        mutableNotificationSequence.value = sequence
        mutableNotifications.tryEmit(
            GattNotification(sequence, GloUuid(uuid), GloBytes.copyOf(value)),
        )
    }

    private fun failConnection(status: Int, detail: String) {
        val error = GloTransportException(GloError.ConnectionFailed(status, detail))
        connected.completeExceptionally(error)
        failPending(error)
    }

    private fun failPending(error: Throwable) {
        synchronized(pendingLock) {
            pendingMtu?.deferred?.completeExceptionally(error)
            pendingServices?.deferred?.completeExceptionally(error)
            pendingRead?.deferred?.completeExceptionally(error)
            pendingWrite?.deferred?.completeExceptionally(error)
            pendingDescriptor?.deferred?.completeExceptionally(error)
        }
    }

    private fun requireConnectedGatt(): BluetoothGatt = gatt?.takeIf {
        !closed && mutableState.value == TransportConnectionState.CONNECTED
    } ?: throw GloTransportException(GloError.NotConnected)

    private fun reject(operation: String): Nothing = throw GloTransportException(
        GloError.GattFailure(operation, null, "Android rejected the operation before it was queued"),
    )

    private suspend fun <T> await(
        deferred: CompletableDeferred<T>,
        timeoutMillis: Long,
        operation: String,
    ): T {
        val result = withTimeoutOrNull(timeoutMillis) { Awaited(deferred.await()) }
        if (result == null) {
        deferred.cancel()
        throw GloTransportException(GloError.Timeout(operation))
        }
        return result.value
    }

    private fun permissionFailure(cause: SecurityException): GloTransportException =
        GloTransportException(
            GloError.MissingPermission(
                AndroidBlePermissions.missing(context).ifEmpty { setOf(cause.message ?: "Bluetooth permission") },
            ),
        )

    private data class PendingMtu(val deferred: CompletableDeferred<Int>)
    private data class PendingServices(val deferred: CompletableDeferred<GattDatabase>)
    private data class PendingRead(val uuid: UUID, val deferred: CompletableDeferred<GloBytes>)
    private data class PendingWrite(val uuid: UUID, val deferred: CompletableDeferred<Unit>)
    private data class PendingDescriptor(val uuid: UUID, val deferred: CompletableDeferred<Unit>)
    private data class Awaited<T>(val value: T)

    private companion object {
        const val DISCONNECT_TIMEOUT_MILLIS = 3_000L
    }
}

private fun Int.toSdkBondState(): BondState = when (this) {
    BluetoothDevice.BOND_BONDING -> BondState.BONDING
    BluetoothDevice.BOND_BONDED -> BondState.BONDED
    else -> BondState.NONE
}

private fun Intent.bluetoothDeviceExtra(): BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
    getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
} else {
    @Suppress("DEPRECATION")
    getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
}

private fun List<BluetoothGattService>.toGattDatabase(): GattDatabase = GattDatabase(
    map { service ->
        GattServiceDescription(
            uuid = GloUuid(service.uuid),
            characteristics = service.characteristics.map { characteristic ->
                GattCharacteristicDescription(
                    uuid = GloUuid(characteristic.uuid),
                    properties = characteristic.properties.toGattProperties(),
                )
            },
        )
    },
)

private fun BluetoothGatt.findCharacteristic(uuid: UUID): BluetoothGattCharacteristic? =
    services.asSequence()
        .flatMap { service -> service.characteristics.asSequence() }
        .firstOrNull { characteristic -> characteristic.uuid == uuid }

private fun Int.toGattProperties(): Set<GattProperty> = buildSet {
    if (this@toGattProperties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add(GattProperty.READ)
    if (this@toGattProperties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add(GattProperty.WRITE)
    if (this@toGattProperties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
        add(GattProperty.WRITE_WITHOUT_RESPONSE)
    }
    if (this@toGattProperties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add(GattProperty.NOTIFY)
    if (this@toGattProperties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add(GattProperty.INDICATE)
}

private inline fun <T> CompletableDeferred<T>.completeGatt(
    status: Int,
    operation: String,
    value: () -> T,
) {
    if (isCompleted) return
    if (status == BluetoothGatt.GATT_SUCCESS) {
        complete(value())
    } else {
        completeExceptionally(
            GloTransportException(
                GloError.GattFailure(operation, status, "Android GATT callback returned status $status"),
            ),
        )
    }
}
