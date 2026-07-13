package com.anto426.glo.sdk.api

import com.anto426.glo.sdk.event.GloDeviceEvent
import com.anto426.glo.sdk.model.BatteryStatus
import com.anto426.glo.sdk.model.ConnectionOptions
import com.anto426.glo.sdk.model.ConnectionState
import com.anto426.glo.sdk.model.DeviceId
import com.anto426.glo.sdk.model.DeviceInfo
import com.anto426.glo.sdk.model.DeviceSnapshot
import com.anto426.glo.sdk.model.DisplayConfiguration
import com.anto426.glo.sdk.model.DisplayUploadProgress
import com.anto426.glo.sdk.model.FindState
import com.anto426.glo.sdk.model.GloDevice
import com.anto426.glo.sdk.model.GloDeviceLocation
import com.anto426.glo.sdk.model.GloResult
import com.anto426.glo.sdk.model.HeatingProfile
import com.anto426.glo.sdk.model.LockState
import com.anto426.glo.sdk.model.PairedGloDevice
import com.anto426.glo.sdk.model.ScanOptions
import com.anto426.glo.sdk.model.ScanState
import com.anto426.glo.sdk.model.GloSessionRecord
import com.anto426.glo.sdk.model.SessionHistorySyncState
import com.anto426.glo.sdk.model.SessionStatus
import com.anto426.glo.sdk.model.SignedDisplayPayload
import com.anto426.glo.sdk.protocol.GloCommand
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

public interface GloDeviceManager : AutoCloseable {
    public val scanState: StateFlow<ScanState>
    public val discoveredDevices: StateFlow<List<GloDevice>>
    public val pairedDevices: StateFlow<List<PairedGloDevice>>
    public val activeSession: StateFlow<GloDeviceSession?>
    public val findMyGlo: FindMyGloApi

    /** Refreshes supported glo devices already paired at operating-system level. */
    public suspend fun refreshPairedDevices(): GloResult<List<PairedGloDevice>>

    public suspend fun startScan(options: ScanOptions = ScanOptions()): GloResult<Unit>

    public suspend fun stopScan()

    public suspend fun connect(
        deviceId: DeviceId,
        options: ConnectionOptions = ConnectionOptions(),
    ): GloResult<GloDeviceSession>

    public suspend fun disconnect()
}

/** Location API kept separate from the BLE protocol: glo has no GPS, so Android records the phone. */
public interface FindMyGloApi {
    public val locations: StateFlow<Map<DeviceId, GloDeviceLocation>>

    public fun lastKnownLocation(deviceId: DeviceId): GloDeviceLocation?

    public suspend fun captureCurrentLocation(deviceId: DeviceId): GloResult<GloDeviceLocation>
}

public interface GloDeviceSession : AutoCloseable {
    public val device: GloDevice
    public val connectionState: StateFlow<ConnectionState>
    public val snapshot: StateFlow<DeviceSnapshot?>
    public val sessionRecords: StateFlow<List<GloSessionRecord>>
    public val pendingSessionCount: StateFlow<Int?>
    public val sessionHistorySyncState: StateFlow<SessionHistorySyncState>
    public val events: Flow<GloDeviceEvent>

    public suspend fun refresh(): GloResult<DeviceSnapshot>

    public suspend fun readDeviceInfo(): GloResult<DeviceInfo>

    public suspend fun readBattery(): GloResult<BatteryStatus>

    public suspend fun setLock(state: LockState): GloResult<LockState>

    public suspend fun setHeatingProfile(profile: HeatingProfile): GloResult<HeatingProfile>

    public suspend fun setBrightness(percent: Int): GloResult<Unit>

    public suspend fun setAutoStart(enabled: Boolean): GloResult<SessionStatus>

    public suspend fun setAutoStop(enabled: Boolean): GloResult<SessionStatus>

    public suspend fun setEndOfSessionWarning(enabled: Boolean): GloResult<SessionStatus>

    public suspend fun startFind(durationSeconds: Int = 60): GloResult<FindState>

    public suspend fun stopFind(): GloResult<FindState>

    public suspend fun readDisplayConfiguration(): GloResult<DisplayConfiguration>

    /** Requests a fresh device challenge for the signed custom-display payload type. */
    public suspend fun requestDisplayChallenge(payloadCode: Int = 1): GloResult<String>

    /**
     * Uploads an opaque payload returned by the official signing service.
     *
     * The implementation re-reads the device challenge immediately before transfer and rejects
     * the upload when it differs from [payload], so signed responses cannot be replayed.
     */
    public suspend fun uploadSignedDisplayPayload(
        payload: SignedDisplayPayload,
        onProgress: (DisplayUploadProgress) -> Unit = {},
    ): GloResult<Unit>

    /** Reads the number of history records pending on the device without clearing them. */
    public suspend fun readPendingSessionCount(): GloResult<Int>

    /**
     * Enables the history stream and waits for its end marker or advertised record count.
     * Records received before a timeout remain available through [sessionRecords].
     */
    public suspend fun syncSessionRecords(): GloResult<List<GloSessionRecord>>

    public suspend fun <T> execute(command: GloCommand<T>): GloResult<T>

    public suspend fun disconnect()
}
