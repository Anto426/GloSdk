package com.anto426.glo.sdk.model

/** Stable identifier used by the SDK. On Android BLE this is normally the device address. */
@JvmInline
public value class DeviceId(public val value: String) {
    init {
        require(value.isNotBlank()) { "Device id cannot be blank" }
    }

    override fun toString(): String = value
}

/** Device families encoded in the proprietary service UUID. */
public enum class DeviceModel(public val protocolCode: String?, public val productName: String) {
    BOREAS("0204", "glo Hyper Pro+"),
    ADONIS_1P("0205", "glo Hyper"),
    ADONIS_2P("0206", "glo Hyper Pro"),
    ADONIS_1P_US("0207", "glo Hyper US"),
    ADONIS_2P_US("0208", "glo Hyper Pro US"),
    ADONIS_1P_15("0209", "glo Hyper 1.5"),
    ADONIS_2P_15("020a", "glo Hyper Pro 1.5"),
    UNKNOWN(null, "Unknown glo device"),
    ;

    public companion object {
        @JvmStatic
        public fun fromProtocolCode(code: String): DeviceModel =
            entries.firstOrNull { it.protocolCode.equals(code, ignoreCase = true) } ?: UNKNOWN

        @JvmField
        public val KNOWN: Set<DeviceModel> = entries.filterTo(linkedSetOf()) { it != UNKNOWN }
    }
}

/** A device found during a scan. No Android framework type crosses this boundary. */
public data class GloDevice(
    public val id: DeviceId,
    public val name: String?,
    public val model: DeviceModel,
    public val rssi: Int,
    public val connectable: Boolean,
    public val lastSeenEpochMillis: Long,
)
