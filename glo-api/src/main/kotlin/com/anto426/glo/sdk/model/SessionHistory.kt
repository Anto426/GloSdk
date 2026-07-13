package com.anto426.glo.sdk.model

/** How a heating session ended, as reported by the device. */
public enum class SessionExitReason(public val protocolCode: Int?) {
    COMPLETED(0),
    HEATING_STOPPED(1),
    BATTERY_HOT_BEFORE_SESSION(101),
    BATTERY_EMPTY(102),
    BATTERY_LOW(103),
    THERMOCOUPLE_SPIKE(104),
    THERMOCOUPLE_ERROR(105),
    HEATING_ZONE_HOT(106),
    HEATING_ZONE_HOT_BEFORE_SESSION(107),
    BATTERY_CURRENT_SENSOR_ERROR(108),
    HARDWARE_ERROR(109),
    BOARD_TEMPERATURE_HIGH(110),
    POWER_OVERLOAD(111),
    TARGET_TEMPERATURE_DIFFERENCE(112),
    BATTERY_DAMAGE(201),
    BATTERY_DISCHARGE_CURRENT_HIGH(301),
    BATTERY_HOT(302),
    BATTERY_COLD(303),
    END_OF_LIFE(304),
    COLD_JUNCTION_HOT(305),
    USB_HOT(306),
    UNKNOWN(null),
    ;

    public companion object {
        @JvmStatic
        public fun fromProtocolCode(code: Int): SessionExitReason =
            entries.firstOrNull { it.protocolCode == code } ?: UNKNOWN
    }
}

/** Heating mode stored in a historical record. */
public enum class SessionHeatingMode(public val protocolCode: Int?) {
    STANDARD(0),
    BOOST(1),
    UNKNOWN(null),
    ;

    public companion object {
        @JvmStatic
        public fun fromProtocolCode(code: Int): SessionHeatingMode =
            entries.firstOrNull { it.protocolCode == code } ?: UNKNOWN
    }
}

/**
 * One Boreas session record read from the device's internal history.
 *
 * The protocol codec exposes the unsigned device timestamp unchanged. A connected platform
 * session may reconcile a pre-2024 timestamp with the device-clock offset captured immediately
 * before clock synchronization. [raw] always retains the exact original 20-byte record.
 */
public data class GloSessionRecord(
    public val count: Long,
    public val startedAtEpochSeconds: Long,
    public val durationSeconds: Int,
    public val exitCode: Int,
    public val exitReason: SessionExitReason,
    public val modeCode: Int,
    public val heatingMode: SessionHeatingMode,
    public val zone1MaxTemperatureRaw: Int,
    public val zone2MaxTemperatureRaw: Int,
    public val batteryMaxTemperatureRaw: Int,
    public val trusted: Boolean,
    public val raw: GloBytes,
) {
    init {
        require(count in 0L..0xffff_ffffL) { "Session count must fit in uint32" }
        require(startedAtEpochSeconds in 0L..0xffff_ffffL) { "Session timestamp must fit in uint32" }
        require(durationSeconds in 0..0xffff) { "Session duration must fit in uint16" }
        require(exitCode in 0..0xffff) { "Session exit code must fit in uint16" }
        require(modeCode in 0..0xff) { "Session mode must fit in uint8" }
    }
}

/** State of a history synchronization attempt. */
public sealed interface SessionHistorySyncState {
    public data object Idle : SessionHistorySyncState
    public data object ReadingPendingCount : SessionHistorySyncState
    public data class Receiving(public val expected: Int?, public val received: Int) : SessionHistorySyncState
    public data class Complete(public val received: Int) : SessionHistorySyncState
    public data class Failed(public val error: GloError) : SessionHistorySyncState
}
