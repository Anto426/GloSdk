package com.anto426.glo.sdk.model

public sealed interface GloError {
    public data object BluetoothUnavailable : GloError
    public data object BluetoothDisabled : GloError
    public data class MissingPermission(public val permissions: Set<String>) : GloError
    public data class ScanFailed(public val platformCode: Int) : GloError
    public data class DeviceNotFound(public val deviceId: DeviceId) : GloError
    public data class ConnectionFailed(public val platformStatus: Int?, public val detail: String) : GloError
    public data class BondingFailed(public val detail: String) : GloError
    public data class GattFailure(
        public val operation: String,
        public val platformStatus: Int?,
        public val detail: String,
    ) : GloError
    public data class Timeout(public val operation: String) : GloError
    public data class ProtocolViolation(public val detail: String) : GloError
    public data class Unsupported(public val feature: String) : GloError
    public data object NotConnected : GloError
    public data class Unexpected(public val detail: String) : GloError
}

public sealed interface GloResult<out T> {
    public data class Success<T>(public val value: T) : GloResult<T>
    public data class Failure(public val error: GloError) : GloResult<Nothing>

    public val isSuccess: Boolean
        get() = this is Success

    public fun getOrNull(): T? = (this as? Success<T>)?.value
}

public sealed interface ScanState {
    public data object Idle : ScanState
    public data class Scanning(public val startedAtEpochMillis: Long) : ScanState
    public data class Failed(public val error: GloError) : ScanState
}

public sealed interface ConnectionState {
    public data object Disconnected : ConnectionState
    public data object Connecting : ConnectionState
    public data object Bonding : ConnectionState
    public data object NegotiatingMtu : ConnectionState
    public data object DiscoveringServices : ConnectionState
    public data object Initializing : ConnectionState
    public data class Ready(public val negotiatedMtu: Int) : ConnectionState
    public data object Disconnecting : ConnectionState
    public data class Failed(public val error: GloError) : ConnectionState
}

public enum class BondState {
    NONE,
    BONDING,
    BONDED,
}

public data class ScanOptions(
    public val models: Set<DeviceModel> = setOf(DeviceModel.BOREAS),
    public val durationMillis: Long = 10_000L,
    public val lowLatency: Boolean = true,
) {
    init {
        require(models.isNotEmpty()) { "At least one model must be selected" }
        require(DeviceModel.UNKNOWN !in models) { "UNKNOWN cannot be used as a scan filter" }
        require(durationMillis > 0L) { "Scan duration must be positive" }
    }
}

public data class ConnectionOptions(
    public val requireBond: Boolean = true,
    public val requestedMtu: Int = 517,
    public val connectionTimeoutMillis: Long = 20_000L,
    public val operationTimeoutMillis: Long = 8_000L,
) {
    init {
        require(requestedMtu in 23..517) { "Requested MTU must be in 23..517" }
        require(connectionTimeoutMillis > 0L)
        require(operationTimeoutMillis > 0L)
    }
}
