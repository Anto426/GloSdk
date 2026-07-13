package com.anto426.glo.sdk.model

@JvmInline
public value class ChargingStateCode(public val value: Int) {
    init {
        require(value in 0..0xff) { "Charging state code must fit in one byte" }
    }
}

public enum class DeviceAuthenticity {
    AUTHENTIC,
    REJECTED,
}

public data class BatteryStatus(
    public val levelPercent: Int,
    public val chargingState: ChargingStateCode,
    public val remainingSessions: Int,
    public val authenticity: DeviceAuthenticity,
) {
    init {
        require(levelPercent in 0..100) { "Battery level must be in 0..100" }
        require(remainingSessions in 0..0xff) { "Remaining sessions must fit in one byte" }
    }
}

public enum class LockState(public val protocolValue: Int) {
    UNLOCKED(0),
    LOCKED(1),
}

public enum class HeatingProfile(public val protocolValue: Int) {
    NORMAL(0),
    BOOST(1),
}

public data class FindState(
    public val enabled: Boolean,
    public val remainingSeconds: Int,
) {
    init {
        require(remainingSeconds in 0..0xff) { "Find duration must fit in one byte" }
    }
}

/** Boreas session-state packet. Raw enum codes are retained until every value is verified. */
public data class SessionStatus(
    public val sessionStateCode: Int,
    public val deviceStateCode: Int,
    public val autoStart: Boolean,
    public val autoStop: Boolean,
    public val stickStatusCode: Int,
    public val cleaningThreshold: Int,
    public val cleaningCount: Int,
) {
    init {
        require(sessionStateCode in 0..0xff)
        require(deviceStateCode in 0..0xff)
        require(stickStatusCode in 0..0xff)
        require(cleaningThreshold in 0..0xffff)
        require(cleaningCount in 0..0xffff)
    }
}

public data class DeviceInfo(
    public val firmwareMajor: Int,
    public val firmwareMinor: Int,
    public val softwareRevision: Int,
    public val boardClassification: Int,
    public val serialNumber: String,
    public val bootloaderVersion: Long,
    public val bleFirmwareMajor: Int?,
    public val bleFirmwareMinor: Int?,
    public val bleSoftwareRevision: Int?,
    public val raw: GloBytes,
) {
    public val firmwareVersion: String
        get() = "$firmwareMajor.$firmwareMinor"

    public val bleFirmwareVersion: String?
        get() = bleFirmwareMajor?.let { major -> "$major.${bleFirmwareMinor ?: 0}" }
}

public data class DeviceSettings(
    public val autoStart: Boolean,
    public val autoStop: Boolean,
    public val endOfSessionWarning: Boolean?,
    public val brightnessPercent: Int,
) {
    init {
        require(brightnessPercent in 0..100) { "Brightness must be in 0..100" }
    }
}

public data class DeviceSnapshot(
    public val info: DeviceInfo? = null,
    public val battery: BatteryStatus? = null,
    public val lockState: LockState? = null,
    public val sessionStatus: SessionStatus? = null,
    public val findState: FindState? = null,
    public val brightnessPercent: Int? = null,
    public val heatingProfile: HeatingProfile? = null,
    public val updatedAtEpochMillis: Long = 0L,
)
