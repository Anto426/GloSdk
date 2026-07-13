package com.anto426.glo.sdk.protocol

import com.anto426.glo.sdk.model.BatteryStatus
import com.anto426.glo.sdk.model.ChargingStateCode
import com.anto426.glo.sdk.model.DeviceAuthenticity
import com.anto426.glo.sdk.model.DeviceInfo
import com.anto426.glo.sdk.model.FindState
import com.anto426.glo.sdk.model.GloBytes
import com.anto426.glo.sdk.model.HeatingProfile
import com.anto426.glo.sdk.model.LockState
import com.anto426.glo.sdk.model.SessionStatus
import java.nio.charset.StandardCharsets

public object BoreasBatteryCodec : PacketDecoder<BatteryStatus> {
    override fun decode(bytes: GloBytes): BatteryStatus {
        requirePacketSize(bytes, 4, "Boreas battery packet")
        val level = bytes.u8(0)
        if (level !in 0..100) throw ProtocolException("Invalid battery level: $level")
        return BatteryStatus(
            levelPercent = level,
            chargingState = ChargingStateCode(bytes.u8(1)),
            remainingSessions = bytes.u8(2),
            authenticity = if (bytes.u8(3) == 0) {
                DeviceAuthenticity.AUTHENTIC
            } else {
                DeviceAuthenticity.REJECTED
            },
        )
    }
}

public object BoreasSessionStatusCodec : PacketDecoder<SessionStatus> {
    override fun decode(bytes: GloBytes): SessionStatus {
        requirePacketSize(bytes, 9, "Boreas session status packet")
        return SessionStatus(
            sessionStateCode = bytes.u8(0),
            deviceStateCode = bytes.u8(1),
            autoStart = bytes.u8(2) != 0,
            autoStop = bytes.u8(3) != 0,
            stickStatusCode = bytes.u8(4),
            cleaningThreshold = bytes.u16be(5),
            cleaningCount = bytes.u16be(7),
        )
    }
}

public object BoreasDeviceInfoCodec : PacketDecoder<DeviceInfo> {
    override fun decode(bytes: GloBytes): DeviceInfo {
        requirePacketSize(bytes, 28, "Boreas device info packet")
        val serialBytes = bytes.toByteArray().copyOfRange(6, 20)
        val serial = String(serialBytes, StandardCharsets.US_ASCII).trimEnd('\u0000')
        return DeviceInfo(
            firmwareMajor = bytes.u8(0),
            firmwareMinor = bytes.u8(1),
            softwareRevision = bytes.u16be(2),
            boardClassification = bytes.u16be(4),
            serialNumber = serial,
            bootloaderVersion = bytes.u32be(20),
            bleFirmwareMajor = bytes.u8(24),
            bleFirmwareMinor = bytes.u8(25),
            bleSoftwareRevision = bytes.u16be(26),
            raw = bytes,
        )
    }
}

public object FindStateCodec : PacketCodec<FindState> {
    override fun decode(bytes: GloBytes): FindState {
        requirePacketSize(bytes, 2, "Find device packet")
        return FindState(enabled = bytes.u8(0) != 0, remainingSeconds = bytes.u8(1))
    }

    override fun encode(value: FindState): GloBytes =
        GloBytes.of(booleanByte(value.enabled), if (value.enabled) value.remainingSeconds else 0)
}

public object LockStateCodec : PacketCodec<LockState> {
    override fun decode(bytes: GloBytes): LockState {
        requirePacketSize(bytes, 1, "Lock packet")
        return when (val value = bytes.u8(0)) {
            0 -> LockState.UNLOCKED
            1 -> LockState.LOCKED
            else -> throw ProtocolException("Unknown lock state: $value")
        }
    }

    override fun encode(value: LockState): GloBytes = GloBytes.of(value.protocolValue)
}

public object HeatingProfileCodec : PacketCodec<HeatingProfile> {
    override fun decode(bytes: GloBytes): HeatingProfile {
        requirePacketSize(bytes, 1, "Heating profile packet")
        return when (val value = bytes.u8(0)) {
            0 -> HeatingProfile.NORMAL
            1 -> HeatingProfile.BOOST
            else -> throw ProtocolException("Unknown heating profile: $value")
        }
    }

    override fun encode(value: HeatingProfile): GloBytes = GloBytes.of(value.protocolValue)
}

public object BrightnessCodec : PacketCodec<Int> {
    override fun decode(bytes: GloBytes): Int {
        requirePacketSize(bytes, 1, "Brightness packet")
        return bytes.u8(0).also { value ->
            if (value !in 0..100) throw ProtocolException("Brightness is outside 0..100: $value")
        }
    }

    override fun encode(value: Int): GloBytes {
        require(value in 0..100) { "Brightness must be in 0..100" }
        return GloBytes.of(value)
    }
}

public object EpochSecondsCodec : PacketCodec<Long> {
    override fun decode(bytes: GloBytes): Long {
        requirePacketSize(bytes, 4, "Time packet")
        return bytes.u32be(0)
    }

    override fun encode(value: Long): GloBytes {
        require(value in 0L..0xffff_ffffL) { "Epoch seconds must fit in uint32" }
        return GloBytes.of(
            (value ushr 24).toInt() and 0xff,
            (value ushr 16).toInt() and 0xff,
            (value ushr 8).toInt() and 0xff,
            value.toInt() and 0xff,
        )
    }
}
