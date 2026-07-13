package com.anto426.glo.sdk.protocol

import com.anto426.glo.sdk.model.DisplayConfiguration
import com.anto426.glo.sdk.model.DisplaySlot
import com.anto426.glo.sdk.model.GloBytes
import java.nio.charset.StandardCharsets

/** Decoder for the protobuf configuration returned by DeviceInfo request `00 03`. */
public object DisplayConfigurationCodec : PacketDecoder<DisplayConfiguration> {
    override fun decode(bytes: GloBytes): DisplayConfiguration {
        val reader = ProtoReader(bytes.toByteArray())
        var version = ""
        val messages = linkedMapOf<DisplaySlot, String>()
        while (!reader.exhausted) {
            val tag = reader.readVarint().toInt()
            when (tag ushr 3) {
                1 -> version = reader.readString(tag)
                2 -> {
                    val frame = parseFrame(reader.readMessage(tag))
                    val slot = DisplaySlot.fromProtocolName(frame.first)
                    if (slot != null) messages[slot] = frame.second
                }
                else -> reader.skip(tag)
            }
        }
        if (version.isBlank()) throw ProtocolException("Display configuration has no version")
        return DisplayConfiguration(version = version, messages = messages)
    }

    private fun parseFrame(reader: ProtoReader): Pair<String, String> {
        var frameValue: Pair<String, String>? = null
        while (!reader.exhausted) {
            val tag = reader.readVarint().toInt()
            when (tag ushr 3) {
                1 -> reader.readString(tag) // frame0/frame1/frame2 key
                2 -> frameValue = parseFrameValue(reader.readMessage(tag))
                else -> reader.skip(tag)
            }
        }
        return frameValue ?: throw ProtocolException("Display frame has no value")
    }

    private fun parseFrameValue(reader: ProtoReader): Pair<String, String> {
        var name = ""
        val codePoints = mutableListOf<Long>()
        while (!reader.exhausted) {
            val tag = reader.readVarint().toInt()
            when (tag ushr 3) {
                1 -> name = reader.readString(tag)
                2 -> when (tag and PROTO_WIRE_TYPE_MASK) {
                    PROTO_WIRE_VARINT -> codePoints += reader.readVarint()
                    PROTO_WIRE_LENGTH_DELIMITED -> codePoints += reader.readPackedVarints(tag)
                    else -> throw ProtocolException(
                        "Display unicodeCharacters has unsupported protobuf wire type ${tag and PROTO_WIRE_TYPE_MASK}",
                    )
                }
                else -> reader.skip(tag)
            }
        }
        if (name.isBlank()) throw ProtocolException("Display frame value has no name")
        return name to codePoints.toUnicodeString()
    }
}

private const val PROTO_WIRE_TYPE_MASK: Int = 0x07
private const val PROTO_WIRE_VARINT: Int = 0
private const val PROTO_WIRE_LENGTH_DELIMITED: Int = 2

private fun List<Long>.toUnicodeString(): String = buildString {
    for (value in this@toUnicodeString) {
        if (value > Character.MAX_CODE_POINT || value in 0xD800L..0xDFFFL) {
            throw ProtocolException("Display configuration contains invalid Unicode code point $value")
        }
        appendCodePoint(value.toInt())
    }
}

private class ProtoReader(private val bytes: ByteArray) {
    private var position: Int = 0

    val exhausted: Boolean
        get() = position >= bytes.size

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            ensureAvailable(1)
            val value = bytes[position++].toInt() and 0xff
            result = result or ((value and 0x7f).toLong() shl shift)
            if ((value and 0x80) == 0) return result
            shift += 7
        }
        throw ProtocolException("Malformed protobuf varint")
    }

    fun readString(tag: Int): String =
        String(readLengthDelimited(tag), StandardCharsets.UTF_8)

    fun readMessage(tag: Int): ProtoReader = ProtoReader(readLengthDelimited(tag))

    fun readPackedVarints(tag: Int): List<Long> {
        val packed = ProtoReader(readLengthDelimited(tag))
        return buildList {
            while (!packed.exhausted) add(packed.readVarint())
        }
    }

    fun skip(tag: Int) {
        when (tag and 0x07) {
            0 -> readVarint()
            1 -> advance(8)
            2 -> advance(readLength())
            5 -> advance(4)
            else -> throw ProtocolException("Unsupported protobuf wire type ${tag and 0x07}")
        }
    }

    private fun readLengthDelimited(tag: Int): ByteArray {
        if ((tag and 0x07) != 2) {
            throw ProtocolException("Expected length-delimited protobuf field")
        }
        val length = readLength()
        ensureAvailable(length)
        return bytes.copyOfRange(position, position + length).also { position += length }
    }

    private fun readLength(): Int {
        val length = readVarint()
        if (length > Int.MAX_VALUE) throw ProtocolException("Protobuf field is too large")
        return length.toInt()
    }

    private fun advance(length: Int) {
        ensureAvailable(length)
        position += length
    }

    private fun ensureAvailable(length: Int) {
        if (length < 0 || position + length > bytes.size) {
            throw ProtocolException("Truncated protobuf payload")
        }
    }
}
