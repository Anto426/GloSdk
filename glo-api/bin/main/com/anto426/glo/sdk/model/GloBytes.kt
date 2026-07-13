package com.anto426.glo.sdk.model

/** Immutable, content-comparable binary value used at the protocol boundary. */
public class GloBytes private constructor(private val bytes: ByteArray) : Iterable<Byte> {
    public val size: Int
        get() = bytes.size

    public val isEmpty: Boolean
        get() = bytes.isEmpty()

    public operator fun get(index: Int): Byte = bytes[index]

    /** Returns a defensive copy suitable for an Android Bluetooth API call. */
    public fun toByteArray(): ByteArray = bytes.copyOf()

    public fun toHex(separator: String = " "): String =
        bytes.joinToString(separator) { byte -> "%02x".format(byte.toInt() and 0xff) }

    override fun iterator(): Iterator<Byte> = bytes.iterator()

    override fun equals(other: Any?): Boolean =
        this === other || (other is GloBytes && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = toHex()

    public companion object {
        @JvmField
        public val EMPTY: GloBytes = GloBytes(ByteArray(0))

        @JvmStatic
        public fun copyOf(bytes: ByteArray): GloBytes =
            if (bytes.isEmpty()) EMPTY else GloBytes(bytes.copyOf())

        @JvmStatic
        public fun of(vararg values: Int): GloBytes {
            require(values.all { it in 0..0xff }) { "Every byte value must be in 0..255" }
            return copyOf(ByteArray(values.size) { index -> values[index].toByte() })
        }

        @JvmStatic
        public fun fromHex(hex: String): GloBytes {
            val normalized = hex.filterNot(Char::isWhitespace).replace(":", "")
            require(normalized.length % 2 == 0) { "Hex input must contain an even number of digits" }
            require(normalized.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                "Hex input contains an invalid character"
            }
            return copyOf(
                ByteArray(normalized.length / 2) { index ->
                    normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
                },
            )
        }
    }
}
