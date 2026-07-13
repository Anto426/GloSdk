package com.anto426.glo.sdk.protocol

import com.anto426.glo.sdk.model.DeviceModel
import java.util.UUID

public data class GloUuid(public val value: UUID) {
    public constructor(value: String) : this(UUID.fromString(value))

    override fun toString(): String = value.toString()
}

public enum class GloService(public val suffix: String) {
    SESSION("000a"),
    DEBUG("000b"),
    DEVICE_MANAGEMENT("000c"),
    AGE_VERIFICATION("000d"),
}

public enum class GloCharacteristic(
    public val suffix: String,
    public val service: GloService,
) {
    DEVICE_INFO("010a", GloService.SESSION),
    TIME("020a", GloService.SESSION),
    BATTERY("030a", GloService.SESSION),
    LOCK("040a", GloService.SESSION),
    SESSION_RECORDS("060a", GloService.SESSION),
    SESSION_STATUS("070a", GloService.SESSION),
    FIND_GLO("090a", GloService.SESSION),
    LED("0a0a", GloService.SESSION),
    RESET("0b0a", GloService.SESSION),
    HEATING_PROFILE("0c0a", GloService.SESSION),
    HAPTIC("0d0a", GloService.SESSION),
    BUZZER("0e0a", GloService.SESSION),
    LAST_ERROR("010b", GloService.DEBUG),
    LOGS("020b", GloService.DEBUG),
    SESSION_LOG("030b", GloService.DEBUG),
    PAYLOAD_VERSION("010c", GloService.DEVICE_MANAGEMENT),
    PAYLOAD_CONTROL("020c", GloService.DEVICE_MANAGEMENT),
    PAYLOAD_DATA("030c", GloService.DEVICE_MANAGEMENT),
    PAYLOAD_CHALLENGE("040c", GloService.DEVICE_MANAGEMENT),
    AGE_CHALLENGE("010d", GloService.AGE_VERIFICATION),
    AGE_SIGNATURE("020d", GloService.AGE_VERIFICATION),
}

/** UUID registry for one device family. Handles from captures are deliberately not exposed. */
public class GattProfile private constructor(public val model: DeviceModel) {
    init {
        require(model.protocolCode != null) { "A GATT profile requires a known model" }
    }

    public fun serviceUuid(service: GloService): GloUuid = buildUuid(service.suffix)

    public fun characteristicUuid(characteristic: GloCharacteristic): GloUuid =
        buildUuid(characteristic.suffix)

    private fun buildUuid(type: String): GloUuid =
        GloUuid("6cd6c8b5-e378-${model.protocolCode}-$type-1b9740683449")

    public companion object {
        @JvmStatic
        public fun forModel(model: DeviceModel): GattProfile = GattProfile(model)

        @JvmStatic
        public fun detectModel(serviceUuid: GloUuid): DeviceModel {
            val value = serviceUuid.toString().lowercase()
            if (!value.startsWith("6cd6c8b5-e378-") || !value.endsWith("-000a-1b9740683449")) {
                return DeviceModel.UNKNOWN
            }
            return DeviceModel.fromProtocolCode(value.substring(14, 18))
        }
    }
}

public object StandardUuids {
    @JvmField
    public val CLIENT_CHARACTERISTIC_CONFIGURATION: GloUuid =
        GloUuid("00002902-0000-1000-8000-00805f9b34fb")

    @JvmField
    public val GENERIC_ACCESS_SERVICE: GloUuid =
        GloUuid("00001800-0000-1000-8000-00805f9b34fb")

    @JvmField
    public val GENERIC_ATTRIBUTE_SERVICE: GloUuid =
        GloUuid("00001801-0000-1000-8000-00805f9b34fb")
}

public object OtaUuids {
    @JvmField
    public val SERVICE: GloUuid = GloUuid("ae5d1e47-5c13-43a0-8635-82ad38a1381f")

    @JvmField
    public val CONTROL_POINT: GloUuid = GloUuid("a3dd50bf-f7a7-4e99-838e-570a086c661b")

    @JvmField
    public val DATA: GloUuid = GloUuid("a2e86c7a-d961-4091-b74f-2409e72efe26")
}

public object ObservedUuids {
    /** Proprietary service observed on Boreas but not yet assigned a semantic role. */
    @JvmField
    public val UNKNOWN_BOREAS_SERVICE: GloUuid =
        GloUuid("00010203-0405-0607-0809-0a0b0c0d1910")
}
