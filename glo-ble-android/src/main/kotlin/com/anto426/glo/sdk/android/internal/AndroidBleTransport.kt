package com.anto426.glo.sdk.android.internal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.anto426.glo.sdk.android.AndroidBlePermissions
import com.anto426.glo.sdk.model.BondState
import com.anto426.glo.sdk.model.ConnectionOptions
import com.anto426.glo.sdk.model.DeviceId
import com.anto426.glo.sdk.model.DeviceModel
import com.anto426.glo.sdk.model.GloError
import com.anto426.glo.sdk.protocol.GattProfile
import com.anto426.glo.sdk.protocol.GloUuid
import com.anto426.glo.sdk.transport.BleBondedDevice
import com.anto426.glo.sdk.transport.BleScanEvent
import com.anto426.glo.sdk.transport.BleScanRequest
import com.anto426.glo.sdk.transport.GloBleTransport
import com.anto426.glo.sdk.transport.GloGattConnection
import com.anto426.glo.sdk.transport.GloTransportException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal class AndroidBleTransport(private val context: Context) : GloBleTransport {
    private val bluetoothManager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val mutableScanEvents = MutableSharedFlow<BleScanEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val scanEvents: Flow<BleScanEvent> = mutableScanEvents.asSharedFlow()

    private var scanning = false
    private var scanModelsByService: Map<GloUuid, DeviceModel> = emptyMap()

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val advertisedServices = result.scanRecord?.serviceUuids.orEmpty().map { GloUuid(it.uuid) }
            val model = advertisedServices.firstNotNullOfOrNull { scanModelsByService[it] }
                ?: advertisedServices.asSequence()
                    .map(GattProfile::detectModel)
                    .firstOrNull { it != DeviceModel.UNKNOWN }
                ?: DeviceModel.UNKNOWN
            mutableScanEvents.tryEmit(
                BleScanEvent.Result(
                    deviceId = DeviceId(result.device.address),
                    name = result.scanRecord?.deviceName,
                    model = model,
                    rssi = result.rssi,
                    connectable = result.isConnectable,
                    observedAtEpochMillis = System.currentTimeMillis(),
                ),
            )
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            mutableScanEvents.tryEmit(BleScanEvent.Failed(errorCode))
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun bondedDevices(): List<BleBondedDevice> {
        ensureAvailable()
        val currentAdapter = adapter ?: throw GloTransportException(GloError.BluetoothUnavailable)
        return try {
            currentAdapter.bondedDevices
                .map { device ->
                    BleBondedDevice(
                        deviceId = DeviceId(device.address),
                        name = device.name,
                        bondState = device.bondState.toSdkBondState(),
                    )
                }
                .sortedWith(compareBy({ it.name?.lowercase().orEmpty() }, { it.deviceId.value }))
        } catch (error: SecurityException) {
            throw missingPermission(error)
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun startScan(request: BleScanRequest) {
        ensureAvailable()
        if (scanning) stopScan()
        val scanner = adapter?.bluetoothLeScanner
            ?: throw GloTransportException(GloError.BluetoothUnavailable)
        scanModelsByService = request.serviceUuids.associateWith(GattProfile::detectModel)
        val filters = request.serviceUuids.map { uuid ->
            ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid.value)).build()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(
                if (request.lowLatency) ScanSettings.SCAN_MODE_LOW_LATENCY
                else ScanSettings.SCAN_MODE_BALANCED,
            )
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        try {
            scanner.startScan(filters, settings, scanCallback)
            scanning = true
        } catch (error: SecurityException) {
            throw missingPermission(error)
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopScan() {
        if (!scanning) return
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (error: SecurityException) {
            throw missingPermission(error)
        } finally {
            scanning = false
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(deviceId: DeviceId, options: ConnectionOptions): GloGattConnection {
        ensureAvailable()
        val device = try {
            adapter?.getRemoteDevice(deviceId.value)
        } catch (error: IllegalArgumentException) {
            null
        } ?: throw GloTransportException(GloError.DeviceNotFound(deviceId))
        return AndroidGattConnection(context, device).also { it.open(options) }
    }

    override fun close() {
        if (scanning) {
            try {
                @SuppressLint("MissingPermission")
                adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (_: SecurityException) {
                // Closing is best effort; callers already receive permission errors from start/stop.
            }
        }
        scanning = false
    }

    private fun ensureAvailable() {
        val missing = AndroidBlePermissions.missing(context)
        if (missing.isNotEmpty()) throw GloTransportException(GloError.MissingPermission(missing))
        val currentAdapter = adapter ?: throw GloTransportException(GloError.BluetoothUnavailable)
        if (!currentAdapter.isEnabled) throw GloTransportException(GloError.BluetoothDisabled)
    }

    private fun missingPermission(cause: SecurityException): GloTransportException =
        GloTransportException(
            GloError.MissingPermission(
                AndroidBlePermissions.missing(context).ifEmpty { setOf(cause.message ?: "Bluetooth permission") },
            ),
        )
}

private fun Int.toSdkBondState(): BondState = when (this) {
    BluetoothDevice.BOND_BONDED -> BondState.BONDED
    BluetoothDevice.BOND_BONDING -> BondState.BONDING
    else -> BondState.NONE
}
