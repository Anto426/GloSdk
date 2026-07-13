package com.anto426.glo.sdk.android.internal

import com.anto426.glo.sdk.api.GloDeviceManager
import com.anto426.glo.sdk.api.GloDeviceSession
import com.anto426.glo.sdk.api.FindMyGloApi
import com.anto426.glo.sdk.model.BondState
import com.anto426.glo.sdk.model.ConnectionOptions
import com.anto426.glo.sdk.model.ConnectionState
import com.anto426.glo.sdk.model.DeviceId
import com.anto426.glo.sdk.model.DeviceModel
import com.anto426.glo.sdk.model.GloDevice
import com.anto426.glo.sdk.model.GloError
import com.anto426.glo.sdk.model.GloResult
import com.anto426.glo.sdk.model.PairedGloDevice
import com.anto426.glo.sdk.model.ScanOptions
import com.anto426.glo.sdk.model.ScanState
import com.anto426.glo.sdk.protocol.GattProfile
import com.anto426.glo.sdk.protocol.GloService
import com.anto426.glo.sdk.transport.BleScanEvent
import com.anto426.glo.sdk.transport.BleScanRequest
import com.anto426.glo.sdk.transport.GloBleTransport
import com.anto426.glo.sdk.transport.GloTransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AndroidGloDeviceManager(
    private val transport: GloBleTransport,
    override val findMyGlo: FindMyGloApi = UnavailableFindMyGloApi,
) : GloDeviceManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycleMutex = Mutex()
    private var automaticStopJob: Job? = null
    private var sessionMonitorJob: Job? = null

    private val mutableScanState = MutableStateFlow<ScanState>(ScanState.Idle)
    override val scanState: StateFlow<ScanState> = mutableScanState.asStateFlow()

    private val mutableDiscoveredDevices = MutableStateFlow<List<GloDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<GloDevice>> = mutableDiscoveredDevices.asStateFlow()

    private val mutablePairedDevices = MutableStateFlow<List<PairedGloDevice>>(emptyList())
    override val pairedDevices: StateFlow<List<PairedGloDevice>> = mutablePairedDevices.asStateFlow()

    private val mutableActiveSession = MutableStateFlow<GloDeviceSession?>(null)
    override val activeSession: StateFlow<GloDeviceSession?> = mutableActiveSession.asStateFlow()

    init {
        scope.launch {
            refreshPairedDevices()
        }
        scope.launch {
            transport.scanEvents.collect { event ->
                when (event) {
                    is BleScanEvent.Result -> {
                        val device = GloDevice(
                            id = event.deviceId,
                            name = event.name,
                            model = event.model,
                            rssi = event.rssi,
                            connectable = event.connectable,
                            lastSeenEpochMillis = event.observedAtEpochMillis,
                        )
                        mutableDiscoveredDevices.update { current ->
                            (current.filterNot { it.id == device.id } + device)
                                .sortedByDescending(GloDevice::rssi)
                        }
                scope.launch { findMyGlo.captureCurrentLocation(device.id) }
                    }

                    is BleScanEvent.Failed -> {
                        mutableScanState.value = ScanState.Failed(GloError.ScanFailed(event.platformCode))
                    }
                }
            }
        }
    }

    override suspend fun refreshPairedDevices(): GloResult<List<PairedGloDevice>> =
        lifecycleMutex.withLock {
            sdkResult { refreshPairedDevicesLocked() }
        }

    override suspend fun startScan(options: ScanOptions): GloResult<Unit> = lifecycleMutex.withLock {
        sdkResult {
            stopScanLocked(cancelTimer = true)
            mutableDiscoveredDevices.value = emptyList()
            val services = options.models.mapTo(linkedSetOf()) { model ->
                GattProfile.forModel(model).serviceUuid(GloService.SESSION)
            }
            transport.startScan(BleScanRequest(services, options.lowLatency))
            mutableScanState.value = ScanState.Scanning(System.currentTimeMillis())
            automaticStopJob = scope.launch {
                delay(options.durationMillis)
                lifecycleMutex.withLock {
                    automaticStopJob = null
                    stopScanLocked(cancelTimer = false)
                }
            }
            Unit
        }
    }

    override suspend fun stopScan() {
        lifecycleMutex.withLock {
            try {
                stopScanLocked(cancelTimer = true)
            } catch (error: GloTransportException) {
                mutableScanState.value = ScanState.Failed(error.error)
            }
        }
    }

    override suspend fun connect(
        deviceId: DeviceId,
        options: ConnectionOptions,
    ): GloResult<GloDeviceSession> = lifecycleMutex.withLock {
        sdkResult {
            stopScanLocked(cancelTimer = true)
            disconnectLocked()
            var paired = mutablePairedDevices.value.firstOrNull { it.id == deviceId }
            var discovered = mutableDiscoveredDevices.value.firstOrNull { it.id == deviceId }
            if (paired == null && discovered == null) {
                refreshPairedDevicesLocked()
                paired = mutablePairedDevices.value.firstOrNull { it.id == deviceId }
                discovered = mutableDiscoveredDevices.value.firstOrNull { it.id == deviceId }
            }
            val device = when {
                discovered?.model == DeviceModel.BOREAS -> discovered
                paired != null -> paired.toScannedDevicePlaceholder()
                discovered != null -> discovered
                else -> throw GloTransportException(GloError.DeviceNotFound(deviceId))
            }
            if (device.model != DeviceModel.BOREAS) {
                throw GloTransportException(
                    GloError.Unsupported("Only the dynamically verified Boreas profile can connect"),
                )
            }
            val connection = transport.connect(deviceId, options)
            val session = AndroidGloDeviceSession(
                device = device,
                profile = GattProfile.forModel(device.model),
                connection = connection,
            )
            try {
                when (val initialized = session.initialize(options)) {
                    is GloResult.Success -> {
                        mutableActiveSession.value = session
                        if (connection.bondState.value == BondState.BONDED) {
                            rememberPairedDevice(device)
                        }
        scope.launch { findMyGlo.captureCurrentLocation(device.id) }
                        monitorSession(session)
                        session
                    }

                    is GloResult.Failure -> throw GloTransportException(initialized.error)
                }
            } catch (error: Throwable) {
                session.close()
                throw error
            }
        }
    }

    override suspend fun disconnect() {
        lifecycleMutex.withLock { disconnectLocked() }
    }

    private suspend fun disconnectLocked() {
        sessionMonitorJob?.cancel()
        sessionMonitorJob = null
        val session = mutableActiveSession.value
        mutableActiveSession.value = null
        session?.disconnect()
    }

    private suspend fun refreshPairedDevicesLocked(): List<PairedGloDevice> {
        val refreshed = transport.bondedDevices()
            .asSequence()
            .filter { it.bondState == BondState.BONDED }
            .mapNotNull { bonded ->
                val model = inferPairedDeviceModel(bonded.name)
                if (model != DeviceModel.BOREAS) return@mapNotNull null
                PairedGloDevice(
                    id = bonded.deviceId,
                    name = bonded.name,
                    model = model,
                    bondState = bonded.bondState,
                )
            }
            .distinctBy(PairedGloDevice::id)
            .sortedWith(compareBy({ it.name?.lowercase().orEmpty() }, { it.id.value }))
            .toList()
        mutablePairedDevices.value = refreshed
        return refreshed
    }

    private fun rememberPairedDevice(device: GloDevice) {
        if (device.model != DeviceModel.BOREAS) return
        val paired = PairedGloDevice(device.id, device.name, device.model, BondState.BONDED)
        mutablePairedDevices.update { current ->
            (current.filterNot { it.id == paired.id } + paired)
                .sortedWith(compareBy({ it.name?.lowercase().orEmpty() }, { it.id.value }))
        }
    }

    private suspend fun stopScanLocked(cancelTimer: Boolean) {
        if (cancelTimer) automaticStopJob?.cancel()
        automaticStopJob = null
        transport.stopScan()
        if (mutableScanState.value !is ScanState.Failed) mutableScanState.value = ScanState.Idle
    }

    private fun monitorSession(session: GloDeviceSession) {
        sessionMonitorJob?.cancel()
        sessionMonitorJob = scope.launch {
            session.connectionState.first {
                it == ConnectionState.Disconnected || it is ConnectionState.Failed
            }
            lifecycleMutex.withLock {
                if (mutableActiveSession.value === session) {
                    mutableActiveSession.value = null
                    session.close()
                }
            }
        }
    }

    override fun close() {
        automaticStopJob?.cancel()
        sessionMonitorJob?.cancel()
        mutableActiveSession.value?.close()
        mutableActiveSession.value = null
        transport.close()
        scope.cancel()
    }
}

private object UnavailableFindMyGloApi : FindMyGloApi {
    private val emptyLocations = MutableStateFlow<Map<DeviceId, com.anto426.glo.sdk.model.GloDeviceLocation>>(emptyMap())
    override val locations = emptyLocations.asStateFlow()

    override fun lastKnownLocation(deviceId: DeviceId) = null

    override suspend fun captureCurrentLocation(deviceId: DeviceId) =
        GloResult.Failure(GloError.Unsupported("Phone location provider is unavailable"))
}

internal fun inferPairedDeviceModel(name: String?): DeviceModel = when {
    name == null -> DeviceModel.UNKNOWN
    name.contains("hyper pro+", ignoreCase = true) -> DeviceModel.BOREAS
    name.contains("hyper pro plus", ignoreCase = true) -> DeviceModel.BOREAS
    name.contains("boreas", ignoreCase = true) -> DeviceModel.BOREAS
    else -> DeviceModel.UNKNOWN
}

private fun PairedGloDevice.toScannedDevicePlaceholder(): GloDevice = GloDevice(
    id = id,
    name = name,
    model = model,
    rssi = Int.MIN_VALUE,
    connectable = bondState == BondState.BONDED,
    lastSeenEpochMillis = 0L,
)

private suspend inline fun <T> sdkResult(crossinline block: suspend () -> T): GloResult<T> = try {
    GloResult.Success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: GloTransportException) {
    GloResult.Failure(error.error)
} catch (error: Throwable) {
    GloResult.Failure(GloError.Unexpected(error.message ?: error::class.java.simpleName))
}
