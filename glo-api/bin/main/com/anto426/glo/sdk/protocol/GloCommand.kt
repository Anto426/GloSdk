package com.anto426.glo.sdk.protocol

import com.anto426.glo.sdk.ExperimentalGloApi
import com.anto426.glo.sdk.model.BatteryStatus
import com.anto426.glo.sdk.model.DeviceInfo
import com.anto426.glo.sdk.model.DisplayConfiguration
import com.anto426.glo.sdk.model.FindState
import com.anto426.glo.sdk.model.GloBytes
import com.anto426.glo.sdk.model.HeatingProfile
import com.anto426.glo.sdk.model.LockState
import com.anto426.glo.sdk.model.SessionStatus

public enum class ProtocolConfidence {
    DYNAMICALLY_VERIFIED,
    STATICALLY_VERIFIED,
    EXPERIMENTAL,
}

public enum class CommandOperation {
    READ,
    WRITE,
}

public enum class CommandResponseMode {
    /** Decode the value returned by a GATT read. */
    DIRECT,

    /** The successful GATT write acknowledgement is the complete result. */
    WRITE_ACK,

    /** Start listening first, perform a write, then decode the matching notification. */
    NOTIFICATION_AFTER_WRITE,
}

public fun interface NotificationMatcher {
    public fun matches(value: GloBytes): Boolean
}

public object AnyNotification : NotificationMatcher {
    override fun matches(value: GloBytes): Boolean = true
}

/** Typed, transport-independent description of one device operation. */
public class GloCommand<T>(
    public val name: String,
    public val characteristic: GloCharacteristic,
    public val operation: CommandOperation,
    public val payload: GloBytes,
    public val responseMode: CommandResponseMode,
    public val decoder: PacketDecoder<T>,
    public val confidence: ProtocolConfidence,
    public val notificationMatcher: NotificationMatcher = AnyNotification,
) {
    init {
        require(
            (operation == CommandOperation.READ && responseMode == CommandResponseMode.DIRECT) ||
                (operation == CommandOperation.WRITE && responseMode != CommandResponseMode.DIRECT),
        ) { "Command operation and response mode are inconsistent" }
    }
}

private object UnitDecoder : PacketDecoder<Unit> {
    override fun decode(bytes: GloBytes) = Unit
}

public object GloCommands {
    @JvmStatic
    public fun readDeviceInfo(): GloCommand<DeviceInfo> = read(
        name = "readDeviceInfo",
        characteristic = GloCharacteristic.DEVICE_INFO,
        decoder = BoreasDeviceInfoCodec,
    )

    @JvmStatic
    public fun readBattery(): GloCommand<BatteryStatus> = read(
        name = "readBattery",
        characteristic = GloCharacteristic.BATTERY,
        decoder = BoreasBatteryCodec,
    )

    @JvmStatic
    public fun readLock(): GloCommand<LockState> = read(
        name = "readLock",
        characteristic = GloCharacteristic.LOCK,
        decoder = LockStateCodec,
    )

    @JvmStatic
    public fun setLock(state: LockState): GloCommand<LockState> = writeWithNotification(
        name = "setLock",
        characteristic = GloCharacteristic.LOCK,
        payload = LockStateCodec.encode(state),
        decoder = LockStateCodec,
        matcher = decodedMatcher(LockStateCodec) { it == state },
    )

    @JvmStatic
    public fun readSessionStatus(): GloCommand<SessionStatus> = read(
        name = "readSessionStatus",
        characteristic = GloCharacteristic.SESSION_STATUS,
        decoder = BoreasSessionStatusCodec,
    )

    @JvmStatic
    public fun setEndOfSessionWarning(enabled: Boolean): GloCommand<SessionStatus> =
        writeWithNotification(
            name = "setEndOfSessionWarning",
            characteristic = GloCharacteristic.SESSION_STATUS,
            payload = GloBytes.of(0x01, booleanByte(enabled)),
            decoder = BoreasSessionStatusCodec,
            confidence = ProtocolConfidence.STATICALLY_VERIFIED,
            matcher = decodedMatcher(BoreasSessionStatusCodec) { true },
        )

    @JvmStatic
    public fun setAutoStart(enabled: Boolean): GloCommand<SessionStatus> = writeWithNotification(
        name = "setAutoStart",
        characteristic = GloCharacteristic.SESSION_STATUS,
        payload = GloBytes.of(0x02, booleanByte(enabled)),
        decoder = BoreasSessionStatusCodec,
        matcher = decodedMatcher(BoreasSessionStatusCodec) { it.autoStart == enabled },
    )

    @JvmStatic
    public fun setAutoStop(enabled: Boolean): GloCommand<SessionStatus> = writeWithNotification(
        name = "setAutoStop",
        characteristic = GloCharacteristic.SESSION_STATUS,
        payload = GloBytes.of(0x03, booleanByte(enabled)),
        decoder = BoreasSessionStatusCodec,
        matcher = decodedMatcher(BoreasSessionStatusCodec) { it.autoStop == enabled },
    )

    @JvmStatic
    public fun readFindState(): GloCommand<FindState> = read(
        name = "readFindState",
        characteristic = GloCharacteristic.FIND_GLO,
        decoder = FindStateCodec,
    )

    @JvmStatic
    @JvmOverloads
    public fun startFind(durationSeconds: Int = 60): GloCommand<FindState> =
        writeWithNotification(
            name = "startFind",
            characteristic = GloCharacteristic.FIND_GLO,
            payload = FindStateCodec.encode(FindState(true, durationSeconds)),
            decoder = FindStateCodec,
            matcher = decodedMatcher(FindStateCodec) { it.enabled },
        )

    @JvmStatic
    public fun stopFind(): GloCommand<FindState> = writeWithNotification(
        name = "stopFind",
        characteristic = GloCharacteristic.FIND_GLO,
        payload = FindStateCodec.encode(FindState(false, 0)),
        decoder = FindStateCodec,
        matcher = decodedMatcher(FindStateCodec) { !it.enabled },
    )

    @JvmStatic
    public fun readHeatingProfile(): GloCommand<HeatingProfile> = read(
        name = "readHeatingProfile",
        characteristic = GloCharacteristic.HEATING_PROFILE,
        decoder = HeatingProfileCodec,
    )

    @JvmStatic
    public fun setHeatingProfile(profile: HeatingProfile): GloCommand<HeatingProfile> =
        writeWithNotification(
            name = "setHeatingProfile",
            characteristic = GloCharacteristic.HEATING_PROFILE,
            payload = HeatingProfileCodec.encode(profile),
            decoder = HeatingProfileCodec,
            matcher = decodedMatcher(HeatingProfileCodec) { it == profile },
        )

    @JvmStatic
    public fun readBrightness(): GloCommand<Int> = read(
        name = "readBrightness",
        characteristic = GloCharacteristic.LED,
        decoder = BrightnessCodec,
    )

    @JvmStatic
    public fun setBrightness(percent: Int): GloCommand<Unit> = writeAck(
        name = "setBrightness",
        characteristic = GloCharacteristic.LED,
        payload = BrightnessCodec.encode(percent),
    )

    @JvmStatic
    public fun readDeviceTime(): GloCommand<Long> = read(
        name = "readDeviceTime",
        characteristic = GloCharacteristic.TIME,
        decoder = EpochSecondsCodec,
    )

    @JvmStatic
    public fun synchronizeTime(epochSeconds: Long): GloCommand<Unit> = writeAck(
        name = "synchronizeTime",
        characteristic = GloCharacteristic.TIME,
        payload = EpochSecondsCodec.encode(epochSeconds),
    )

    @JvmStatic
    public fun readDisplayConfiguration(): GloCommand<DisplayConfiguration> =
        writeWithNotification(
            name = "readDisplayConfiguration",
            characteristic = GloCharacteristic.DEVICE_INFO,
            payload = GloBytes.of(0x00, 0x03),
            decoder = DisplayConfigurationCodec,
            matcher = decodedMatcher(DisplayConfigurationCodec) { true },
        )

    private fun <T> read(
        name: String,
        characteristic: GloCharacteristic,
        decoder: PacketDecoder<T>,
        confidence: ProtocolConfidence = ProtocolConfidence.DYNAMICALLY_VERIFIED,
    ): GloCommand<T> = GloCommand(
        name = name,
        characteristic = characteristic,
        operation = CommandOperation.READ,
        payload = GloBytes.EMPTY,
        responseMode = CommandResponseMode.DIRECT,
        decoder = decoder,
        confidence = confidence,
    )

    private fun writeAck(
        name: String,
        characteristic: GloCharacteristic,
        payload: GloBytes,
        confidence: ProtocolConfidence = ProtocolConfidence.DYNAMICALLY_VERIFIED,
    ): GloCommand<Unit> = GloCommand(
        name = name,
        characteristic = characteristic,
        operation = CommandOperation.WRITE,
        payload = payload,
        responseMode = CommandResponseMode.WRITE_ACK,
        decoder = UnitDecoder,
        confidence = confidence,
    )

    private fun <T> writeWithNotification(
        name: String,
        characteristic: GloCharacteristic,
        payload: GloBytes,
        decoder: PacketDecoder<T>,
        confidence: ProtocolConfidence = ProtocolConfidence.DYNAMICALLY_VERIFIED,
        matcher: NotificationMatcher = AnyNotification,
    ): GloCommand<T> = GloCommand(
        name = name,
        characteristic = characteristic,
        operation = CommandOperation.WRITE,
        payload = payload,
        responseMode = CommandResponseMode.NOTIFICATION_AFTER_WRITE,
        decoder = decoder,
        confidence = confidence,
        notificationMatcher = matcher,
    )

    private fun <T> decodedMatcher(
        decoder: PacketDecoder<T>,
        predicate: (T) -> Boolean,
    ): NotificationMatcher = NotificationMatcher { value ->
        try {
            predicate(decoder.decode(value))
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: IndexOutOfBoundsException) {
            false
        }
    }
}

@ExperimentalGloApi
public object ExperimentalGloCommands {
    @JvmStatic
    public fun resetDevice(): GloCommand<Unit> = GloCommand(
        name = "resetDevice",
        characteristic = GloCharacteristic.RESET,
        operation = CommandOperation.WRITE,
        payload = GloBytes.of(0x01),
        responseMode = CommandResponseMode.WRITE_ACK,
        decoder = UnitDecoder,
        confidence = ProtocolConfidence.EXPERIMENTAL,
    )

    @JvmStatic
    public fun resetDisplayName(): GloCommand<Unit> = GloCommand(
        name = "resetDisplayName",
        characteristic = GloCharacteristic.RESET,
        operation = CommandOperation.WRITE,
        payload = GloBytes.of(0x02),
        responseMode = CommandResponseMode.WRITE_ACK,
        decoder = UnitDecoder,
        confidence = ProtocolConfidence.EXPERIMENTAL,
    )

    @JvmStatic
    public fun setHaptic(level: Int): GloCommand<Unit> = oneByteWrite("setHaptic", GloCharacteristic.HAPTIC, level)

    @JvmStatic
    public fun setBuzzer(pattern: Int): GloCommand<Unit> = oneByteWrite("setBuzzer", GloCharacteristic.BUZZER, pattern)

    private fun oneByteWrite(name: String, characteristic: GloCharacteristic, value: Int): GloCommand<Unit> {
        require(value in 0..0xff) { "Value must fit in one byte" }
        return GloCommand(
            name = name,
            characteristic = characteristic,
            operation = CommandOperation.WRITE,
            payload = GloBytes.of(value),
            responseMode = CommandResponseMode.WRITE_ACK,
            decoder = UnitDecoder,
            confidence = ProtocolConfidence.STATICALLY_VERIFIED,
        )
    }
}
