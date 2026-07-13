package com.anto426.glo.sdk.protocol

import com.anto426.glo.sdk.model.GloBytes
import com.anto426.glo.sdk.model.GloSessionRecord
import com.anto426.glo.sdk.model.SessionExitReason
import com.anto426.glo.sdk.model.SessionHeatingMode

/** One message read from or notified by the Boreas Session Records characteristic. */
public sealed interface BoreasSessionRecordsPacket {
    /** Pending-record count. [encodedBytes] distinguishes the legacy uint16 and Boreas uint32 forms. */
    public data class RecordsCount(public val count: Long, public val encodedBytes: Int) :
        BoreasSessionRecordsPacket

    /** Announces the number of records that follow in a streamed history transfer. */
    public data class StartOfFile(public val expectedRecords: Long) : BoreasSessionRecordsPacket

    /** A single, unframed 20-byte session record. */
    public data class Record(public val value: GloSessionRecord) : BoreasSessionRecordsPacket

    /** Version-4 framing byte followed by one or more 20-byte session records. */
    public data class RecordBatch(public val values: List<GloSessionRecord>) : BoreasSessionRecordsPacket

    /** Terminates a streamed history transfer. */
    public data object EndOfFile : BoreasSessionRecordsPacket
}

/** Strict, side-effect-free decoder for the Boreas Session Records wire format. */
public object BoreasSessionRecordsCodec : PacketDecoder<BoreasSessionRecordsPacket> {
    override fun decode(bytes: GloBytes): BoreasSessionRecordsPacket = when {
        bytes.size == LEGACY_COUNT_SIZE -> BoreasSessionRecordsPacket.RecordsCount(
            count = bytes.u16be(0).toLong(),
            encodedBytes = LEGACY_COUNT_SIZE,
        )

        bytes.size == MARKER_SIZE && bytes.u32be(0) == END_OF_FILE_MARKER ->
            BoreasSessionRecordsPacket.EndOfFile

        bytes.size == BOREAS_COUNT_SIZE -> BoreasSessionRecordsPacket.RecordsCount(
            count = bytes.u32be(0),
            encodedBytes = BOREAS_COUNT_SIZE,
        )

        bytes.size == START_OF_FILE_SIZE && bytes.u32be(0) == START_OF_FILE_MARKER ->
            BoreasSessionRecordsPacket.StartOfFile(bytes.u32be(MARKER_SIZE))

        bytes.size == RECORD_SIZE -> BoreasSessionRecordsPacket.Record(decodeRecord(bytes, 0))

        bytes.size > VERSION_PREFIX_SIZE &&
            bytes.u8(0) == BATCH_VERSION &&
            (bytes.size - VERSION_PREFIX_SIZE) % RECORD_SIZE == 0 -> {
            val recordCount = (bytes.size - VERSION_PREFIX_SIZE) / RECORD_SIZE
            BoreasSessionRecordsPacket.RecordBatch(
                List(recordCount) { index ->
                    decodeRecord(bytes, VERSION_PREFIX_SIZE + index * RECORD_SIZE)
                },
            )
        }

        else -> throw ProtocolException(
            "Unsupported Session Records packet: ${bytes.size} bytes (${bytes.toHex()})",
        )
    }

    private fun decodeRecord(bytes: GloBytes, offset: Int): GloSessionRecord {
        if (offset < 0 || offset + RECORD_SIZE > bytes.size) {
            throw ProtocolException("Truncated 20-byte session record at offset $offset")
        }
        val exitCode = bytes.u16be(offset + 10)
        val modeCode = bytes.u8(offset + 12)
        val raw = GloBytes.copyOf(bytes.toByteArray().copyOfRange(offset, offset + RECORD_SIZE))
        return GloSessionRecord(
            count = bytes.u32be(offset),
            startedAtEpochSeconds = bytes.u32be(offset + 4),
            durationSeconds = bytes.u16be(offset + 8),
            exitCode = exitCode,
            exitReason = SessionExitReason.fromProtocolCode(exitCode),
            modeCode = modeCode,
            heatingMode = SessionHeatingMode.fromProtocolCode(modeCode),
            zone1MaxTemperatureRaw = bytes.i16be(offset + 13),
            zone2MaxTemperatureRaw = bytes.i16be(offset + 15),
            batteryMaxTemperatureRaw = bytes.i16be(offset + 17),
            trusted = bytes.u8(offset + 19) == 0,
            raw = raw,
        )
    }

    private fun GloBytes.i16be(index: Int): Int = u16be(index).let { value ->
        if (value and 0x8000 != 0) value - 0x1_0000 else value
    }

    private const val LEGACY_COUNT_SIZE = 2
    private const val BOREAS_COUNT_SIZE = 4
    private const val MARKER_SIZE = 4
    private const val START_OF_FILE_SIZE = 8
    private const val VERSION_PREFIX_SIZE = 1
    private const val RECORD_SIZE = 20
    private const val BATCH_VERSION = 0x04
    private const val START_OF_FILE_MARKER = 0xffff_fffeL
    private const val END_OF_FILE_MARKER = 0xffff_fffdL
}
