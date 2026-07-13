package com.anto426.glo.sdk.model

public enum class LocationSource {
    GPS,
    NETWORK,
    PASSIVE,
    UNKNOWN,
}

/** Phone position captured when this glo was last detected over Bluetooth. */
public data class GloDeviceLocation(
    public val deviceId: DeviceId,
    public val latitude: Double,
    public val longitude: Double,
    public val accuracyMeters: Float?,
    public val capturedAtEpochMillis: Long,
    public val source: LocationSource,
) {
    init {
        require(latitude in -90.0..90.0)
        require(longitude in -180.0..180.0)
        require(accuracyMeters == null || accuracyMeters >= 0f)
        require(capturedAtEpochMillis >= 0L)
    }
}
