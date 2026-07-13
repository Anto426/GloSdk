package com.anto426.glo.sdk.protocol

import com.anto426.glo.sdk.model.GloBytes

public class ProtocolException(public val detail: String) : IllegalArgumentException(detail)

public fun interface PacketDecoder<T> {
    public fun decode(bytes: GloBytes): T
}

public fun interface PacketEncoder<T> {
    public fun encode(value: T): GloBytes
}

public interface PacketCodec<T> : PacketDecoder<T>, PacketEncoder<T>

internal fun GloBytes.u8(index: Int): Int = this[index].toInt() and 0xff

internal fun GloBytes.u16be(index: Int): Int = (u8(index) shl 8) or u8(index + 1)

internal fun GloBytes.u32be(index: Int): Long =
    (u8(index).toLong() shl 24) or
        (u8(index + 1).toLong() shl 16) or
        (u8(index + 2).toLong() shl 8) or
        u8(index + 3).toLong()

internal fun requirePacketSize(bytes: GloBytes, expected: Int, name: String) {
    if (bytes.size != expected) {
        throw ProtocolException("$name requires $expected bytes, received ${bytes.size}")
    }
}

internal fun booleanByte(value: Boolean): Int = if (value) 1 else 0
