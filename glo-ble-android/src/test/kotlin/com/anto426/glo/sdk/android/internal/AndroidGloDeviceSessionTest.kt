package com.anto426.glo.sdk.android.internal

import com.anto426.glo.sdk.model.BondState
import com.anto426.glo.sdk.model.ConnectionOptions
import com.anto426.glo.sdk.model.ConnectionState
import com.anto426.glo.sdk.model.DeviceId
import com.anto426.glo.sdk.model.DeviceModel
import com.anto426.glo.sdk.model.GloBytes
import com.anto426.glo.sdk.model.GloDevice
import com.anto426.glo.sdk.model.GloResult
import com.anto426.glo.sdk.model.LockState
import com.anto426.glo.sdk.model.SessionHistorySyncState
import com.anto426.glo.sdk.model.SignedDisplayPayload
import com.anto426.glo.sdk.model.DisplayUploadProgress
import com.anto426.glo.sdk.protocol.GattProfile
import com.anto426.glo.sdk.protocol.GloCharacteristic
import com.anto426.glo.sdk.protocol.GloService
import com.anto426.glo.sdk.protocol.GloUuid
import com.anto426.glo.sdk.transport.GattCharacteristicDescription
import com.anto426.glo.sdk.transport.GattDatabase
import com.anto426.glo.sdk.transport.GattNotification
import com.anto426.glo.sdk.transport.GattProperty
import com.anto426.glo.sdk.transport.GattServiceDescription
import com.anto426.glo.sdk.transport.GattWriteType
import com.anto426.glo.sdk.transport.GloGattConnection
import com.anto426.glo.sdk.transport.TransportConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidGloDeviceSessionTest {
    private val profile = GattProfile.forModel(DeviceModel.BOREAS)

    @Test
    fun `initializes snapshot and catches immediate notification after write ack`() = runBlocking {
        val connection = FakeGattConnection(profile)
        val session = AndroidGloDeviceSession(
            device = GloDevice(
                id = connection.deviceId,
                name = "glo",
                model = DeviceModel.BOREAS,
                rssi = -42,
                connectable = true,
                lastSeenEpochMillis = 1L,
            ),
            profile = profile,
            connection = connection,
        )

        val initialized = session.initialize(ConnectionOptions(requireBond = false))
        assertTrue(initialized is GloResult.Success)
        assertEquals(ConnectionState.Ready(259), session.connectionState.value)
        assertEquals(99, session.snapshot.value?.battery?.levelPercent)
        assertEquals("7.3", session.snapshot.value?.info?.firmwareVersion)

        val locked = session.setLock(LockState.LOCKED)
        assertEquals(LockState.LOCKED, (locked as GloResult.Success).value)
        assertEquals(LockState.LOCKED, session.snapshot.value?.lockState)
        assertEquals("01", connection.lastWrite?.second?.toHex())
        assertTrue(
            profile.characteristicUuid(GloCharacteristic.LOCK) in connection.notificationSubscriptions,
        )
    }

    @Test
    fun `syncs optional session history without clearing the device`() = runBlocking {
        val connection = FakeGattConnection(profile, includeSessionHistory = true)
        val session = AndroidGloDeviceSession(
            device = GloDevice(
                id = connection.deviceId,
                name = "glo",
                model = DeviceModel.BOREAS,
                rssi = -42,
                connectable = true,
                lastSeenEpochMillis = 1L,
            ),
            profile = profile,
            connection = connection,
            epochSecondsProvider = { 1_800_000_000L },
        )

        assertTrue(session.initialize(ConnectionOptions(requireBond = false)) is GloResult.Success)
        val result = session.syncSessionRecords()

        assertTrue(result is GloResult.Success)
        assertEquals(2, session.pendingSessionCount.value)
        assertEquals(listOf(43L, 42L), session.sessionRecords.value.map { it.count })
        assertEquals(1_800_001_256L, session.sessionRecords.value.first().startedAtEpochSeconds)
        assertEquals(1_800_001_000L, session.sessionRecords.value.last().startedAtEpochSeconds)
        assertEquals(SessionHistorySyncState.Complete(2), session.sessionHistorySyncState.value)
        assertTrue(
            connection.writes.none {
                it.first == profile.characteristicUuid(GloCharacteristic.SESSION_RECORDS)
            },
        )
    }

    @Test
    fun `history timeout scales with count and is capped`() {
        assertEquals(8_000L, sessionHistoryTimeoutMillis(8_000L, 2))
        assertEquals(35_000L, sessionHistoryTimeoutMillis(8_000L, 100))
        assertEquals(120_000L, sessionHistoryTimeoutMillis(8_000L, 1_000))
    }

    @Test
    fun `uploads signed display only after matching fresh challenge and device done`() = runBlocking {
        val connection = FakeGattConnection(profile, includeSignedDisplay = true)
        val session = AndroidGloDeviceSession(
            device = GloDevice(
                id = connection.deviceId,
                name = "glo",
                model = DeviceModel.BOREAS,
                rssi = -42,
                connectable = true,
                lastSeenEpochMillis = 1L,
            ),
            profile = profile,
            connection = connection,
        )
        assertTrue(session.initialize(ConnectionOptions(requireBond = false)) is GloResult.Success)
        val challenge = session.requestDisplayChallenge()
        assertEquals("0001aabbccdd", (challenge as GloResult.Success).value)

        val progress = mutableListOf<DisplayUploadProgress>()
        val result = session.uploadSignedDisplayPayload(
            SignedDisplayPayload(
                protobufPayload = "0102",
                challenge = challenge.value,
                challengeSignature = "0304",
                protobufPayloadHashSignature = "0506",
            ),
            progress::add,
        )

        assertTrue(result is GloResult.Success)
        assertTrue(progress.first() == DisplayUploadProgress.Preparing)
        assertTrue(progress.last() == DisplayUploadProgress.Verifying)
        assertEquals(
            listOf(
                GloCharacteristic.PAYLOAD_CHALLENGE,
                GloCharacteristic.PAYLOAD_CHALLENGE,
                GloCharacteristic.PAYLOAD_VERSION,
                GloCharacteristic.PAYLOAD_DATA,
                GloCharacteristic.PAYLOAD_CONTROL,
            ),
            connection.signedDisplayWrites.map { it.first },
        )
        assertEquals(GattWriteType.WITHOUT_RESPONSE, connection.signedDisplayWrites[3].third)
        assertEquals(14, connection.signedDisplayWrites[2].second.size)
        assertEquals("03", connection.signedDisplayWrites.last().second.toHex(""))
    }
}

private class FakeGattConnection(
    private val profile: GattProfile,
    includeSessionHistory: Boolean = false,
    private val includeSignedDisplay: Boolean = false,
) : GloGattConnection {
    override val deviceId = DeviceId("00:11:22:33:44:55")
    override val state: StateFlow<TransportConnectionState> =
        MutableStateFlow(TransportConnectionState.CONNECTED)
    override val bondState: StateFlow<BondState> = MutableStateFlow(BondState.BONDED)
    private val mutableNotificationSequence = MutableStateFlow(0L)
    override val notificationSequence: StateFlow<Long> = mutableNotificationSequence
    private val notificationFlow = MutableSharedFlow<GattNotification>(extraBufferCapacity = 16)
    override val notifications: Flow<GattNotification> = notificationFlow

    val notificationSubscriptions = linkedSetOf<GloUuid>()
    val writes = mutableListOf<Pair<GloUuid, GloBytes>>()
    val signedDisplayWrites = mutableListOf<Triple<GloCharacteristic, GloBytes, GattWriteType>>()
    var lastWrite: Pair<GloUuid, GloBytes>? = null
    private var sessionNotificationEnables = 0

    private val values = mutableMapOf(
        GloCharacteristic.DEVICE_INFO to GloBytes.fromHex(
            "07 03 f3 bb 03 00 4d 42 32 33 42 41 54 34 33 32 31 37 39 39 00 00 00 05 05 00 ee 6c",
        ),
        GloCharacteristic.TIME to GloBytes.fromHex("65 53 ed 18"),
        GloCharacteristic.BATTERY to GloBytes.fromHex("63 00 13 00"),
        GloCharacteristic.LOCK to GloBytes.fromHex("00"),
        GloCharacteristic.SESSION_STATUS to GloBytes.fromHex("00 00 01 01 00 01 f4 00 03"),
        GloCharacteristic.FIND_GLO to GloBytes.fromHex("00 00"),
        GloCharacteristic.LED to GloBytes.fromHex("64"),
        GloCharacteristic.HEATING_PROFILE to GloBytes.fromHex("00"),
    )

    init {
        if (includeSessionHistory) {
            values[GloCharacteristic.SESSION_RECORDS] = GloBytes.fromHex("00 00 00 02")
        }
    }

    override suspend fun ensureBond(timeoutMillis: Long) = Unit

    override suspend fun requestMtu(mtu: Int, timeoutMillis: Long): Int = 259

    override suspend fun discoverServices(timeoutMillis: Long): GattDatabase = GattDatabase(
        buildList {
            add(
            GattServiceDescription(
                uuid = profile.serviceUuid(GloService.SESSION),
                characteristics = values.keys.map { characteristic ->
                    GattCharacteristicDescription(
                        uuid = profile.characteristicUuid(characteristic),
                        properties = buildSet {
                            add(GattProperty.READ)
                            add(GattProperty.WRITE)
                            if (characteristic in NOTIFYING) add(GattProperty.NOTIFY)
                        },
                    )
                },
            ),
            )
            if (includeSignedDisplay) {
                add(
                    GattServiceDescription(
                        uuid = profile.serviceUuid(GloService.DEVICE_MANAGEMENT),
                        characteristics = listOf(
                            GattCharacteristicDescription(
                                profile.characteristicUuid(GloCharacteristic.PAYLOAD_VERSION),
                                setOf(GattProperty.WRITE),
                            ),
                            GattCharacteristicDescription(
                                profile.characteristicUuid(GloCharacteristic.PAYLOAD_CONTROL),
                                setOf(GattProperty.WRITE, GattProperty.NOTIFY),
                            ),
                            GattCharacteristicDescription(
                                profile.characteristicUuid(GloCharacteristic.PAYLOAD_DATA),
                                setOf(GattProperty.WRITE_WITHOUT_RESPONSE),
                            ),
                            GattCharacteristicDescription(
                                profile.characteristicUuid(GloCharacteristic.PAYLOAD_CHALLENGE),
                                setOf(GattProperty.WRITE, GattProperty.NOTIFY),
                            ),
                        ),
                    ),
                )
            }
        },
    )

    override suspend fun read(characteristic: GloUuid, timeoutMillis: Long): GloBytes =
        values.entries.first { profile.characteristicUuid(it.key) == characteristic }.value

    override suspend fun write(
        characteristic: GloUuid,
        value: GloBytes,
        writeType: GattWriteType,
        timeoutMillis: Long,
    ) {
        lastWrite = characteristic to value
        writes += characteristic to value
        if (includeSignedDisplay) {
            val signedType = SIGNED_DISPLAY_CHARACTERISTICS.firstOrNull {
                profile.characteristicUuid(it) == characteristic
            }
            if (signedType != null) {
                signedDisplayWrites += Triple(signedType, value, writeType)
                when (signedType) {
                    GloCharacteristic.PAYLOAD_CHALLENGE -> emitNotification(
                        characteristic,
                        GloBytes.fromHex("00 01 aa bb cc dd"),
                    )
                    GloCharacteristic.PAYLOAD_VERSION -> emitNotification(
                        profile.characteristicUuid(GloCharacteristic.PAYLOAD_CONTROL),
                        GloBytes.fromHex("01 04"),
                    )
                    GloCharacteristic.PAYLOAD_CONTROL -> if (value.toHex("") == "03") {
                        emitNotification(characteristic, GloBytes.fromHex("04"))
                    }
                    GloCharacteristic.PAYLOAD_DATA -> Unit
                    else -> Unit
                }
                return
            }
        }
        val type = values.keys.firstOrNull { profile.characteristicUuid(it) == characteristic } ?: return
        when (type) {
            GloCharacteristic.LOCK,
            GloCharacteristic.HEATING_PROFILE,
            GloCharacteristic.FIND_GLO,
            -> {
                values[type] = value
                emitNotification(characteristic, value)
            }

            GloCharacteristic.SESSION_STATUS -> {
                val old = values.getValue(type).toByteArray()
                val request = value.toByteArray()
                if (request.size == 2) {
                    when (request[0].toInt() and 0xff) {
                        2 -> old[2] = request[1]
                        3 -> old[3] = request[1]
                    }
                }
                values[type] = GloBytes.copyOf(old)
                emitNotification(characteristic, values.getValue(type))
            }

            else -> values[type] = value
        }
    }

    override suspend fun setNotifications(
        characteristic: GloUuid,
        enabled: Boolean,
        timeoutMillis: Long,
    ) {
        if (enabled) notificationSubscriptions += characteristic else notificationSubscriptions -= characteristic
        if (enabled && characteristic == profile.characteristicUuid(GloCharacteristic.SESSION_RECORDS)) {
            sessionNotificationEnables += 1
            if (sessionNotificationEnables >= 2) {
                emitNotification(characteristic, GloBytes.fromHex("ff ff ff fe 00 00 00 02"))
                emitNotification(
                    characteristic,
                    GloBytes.fromHex(
                        "04 " +
                            "00 00 00 2a 65 53 f1 00 01 2c 00 00 01 ff f6 00 fa 80 00 00 " +
                            "00 00 00 2b 65 53 f2 00 00 f0 00 01 00 00 64 00 c8 01 2c 01",
                    ),
                )
                emitNotification(characteristic, GloBytes.fromHex("ff ff ff fd"))
            }
        }
    }

    override suspend fun disconnect() = Unit

    override fun close() = Unit

    private suspend fun emitNotification(characteristic: GloUuid, value: GloBytes) {
        val sequence = mutableNotificationSequence.value + 1L
        mutableNotificationSequence.value = sequence
        notificationFlow.emit(GattNotification(sequence, characteristic, value))
    }

    private companion object {
        val NOTIFYING = setOf(
            GloCharacteristic.DEVICE_INFO,
            GloCharacteristic.BATTERY,
            GloCharacteristic.LOCK,
            GloCharacteristic.SESSION_STATUS,
            GloCharacteristic.FIND_GLO,
            GloCharacteristic.HEATING_PROFILE,
            GloCharacteristic.SESSION_RECORDS,
        )
        val SIGNED_DISPLAY_CHARACTERISTICS = setOf(
            GloCharacteristic.PAYLOAD_VERSION,
            GloCharacteristic.PAYLOAD_CONTROL,
            GloCharacteristic.PAYLOAD_DATA,
            GloCharacteristic.PAYLOAD_CHALLENGE,
        )
    }
}
