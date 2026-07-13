package com.anto426.glo.sdk.android.internal

import com.anto426.glo.sdk.api.GloDeviceSession
import com.anto426.glo.sdk.event.GloDeviceEvent
import com.anto426.glo.sdk.model.BatteryStatus
import com.anto426.glo.sdk.model.ConnectionOptions
import com.anto426.glo.sdk.model.ConnectionState
import com.anto426.glo.sdk.model.DeviceInfo
import com.anto426.glo.sdk.model.DeviceSnapshot
import com.anto426.glo.sdk.model.DisplayConfiguration
import com.anto426.glo.sdk.model.DisplayUploadProgress
import com.anto426.glo.sdk.model.FindState
import com.anto426.glo.sdk.model.GloBytes
import com.anto426.glo.sdk.model.GloDevice
import com.anto426.glo.sdk.model.GloError
import com.anto426.glo.sdk.model.GloResult
import com.anto426.glo.sdk.model.GloSessionRecord
import com.anto426.glo.sdk.model.HeatingProfile
import com.anto426.glo.sdk.model.LockState
import com.anto426.glo.sdk.model.SessionHistorySyncState
import com.anto426.glo.sdk.model.SessionStatus
import com.anto426.glo.sdk.model.SignedDisplayPayload
import com.anto426.glo.sdk.protocol.BoreasBatteryCodec
import com.anto426.glo.sdk.protocol.BoreasDeviceInfoCodec
import com.anto426.glo.sdk.protocol.BoreasSessionRecordsCodec
import com.anto426.glo.sdk.protocol.BoreasSessionRecordsPacket
import com.anto426.glo.sdk.protocol.BoreasSessionStatusCodec
import com.anto426.glo.sdk.protocol.BrightnessCodec
import com.anto426.glo.sdk.protocol.CommandOperation
import com.anto426.glo.sdk.protocol.CommandResponseMode
import com.anto426.glo.sdk.protocol.DisplayConfigurationCodec
import com.anto426.glo.sdk.protocol.FindStateCodec
import com.anto426.glo.sdk.protocol.GattProfile
import com.anto426.glo.sdk.protocol.GloCharacteristic
import com.anto426.glo.sdk.protocol.GloCommand
import com.anto426.glo.sdk.protocol.GloCommands
import com.anto426.glo.sdk.protocol.GloService
import com.anto426.glo.sdk.protocol.GloUuid
import com.anto426.glo.sdk.protocol.HeatingProfileCodec
import com.anto426.glo.sdk.protocol.LockStateCodec
import com.anto426.glo.sdk.protocol.ProtocolException
import com.anto426.glo.sdk.protocol.SignedDisplayPayloadCodec
import com.anto426.glo.sdk.transport.GattDatabase
import com.anto426.glo.sdk.transport.GattNotification
import com.anto426.glo.sdk.transport.GattProperty
import com.anto426.glo.sdk.transport.GattWriteType
import com.anto426.glo.sdk.transport.GloGattConnection
import com.anto426.glo.sdk.transport.GloTransportException
import com.anto426.glo.sdk.transport.TransportConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal class AndroidGloDeviceSession(
    override val device: GloDevice,
    private val profile: GattProfile,
    private val connection: GloGattConnection,
    private val epochSecondsProvider: () -> Long = { System.currentTimeMillis() / 1_000L },
) : GloDeviceSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commandMutex = Mutex()

    private val mutableConnectionState = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)
    override val connectionState: StateFlow<ConnectionState> = mutableConnectionState.asStateFlow()

    private val mutableSnapshot = MutableStateFlow<DeviceSnapshot?>(null)
    override val snapshot: StateFlow<DeviceSnapshot?> = mutableSnapshot.asStateFlow()

    private val mutableSessionRecords = MutableStateFlow<List<GloSessionRecord>>(emptyList())
    override val sessionRecords: StateFlow<List<GloSessionRecord>> = mutableSessionRecords.asStateFlow()

    private val mutablePendingSessionCount = MutableStateFlow<Int?>(null)
    override val pendingSessionCount: StateFlow<Int?> = mutablePendingSessionCount.asStateFlow()

    private val mutableSessionHistorySyncState =
        MutableStateFlow<SessionHistorySyncState>(SessionHistorySyncState.Idle)
    override val sessionHistorySyncState: StateFlow<SessionHistorySyncState> =
        mutableSessionHistorySyncState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<GloDeviceEvent>(extraBufferCapacity = 64)
    override val events: Flow<GloDeviceEvent> = mutableEvents.asSharedFlow()

    private var connectionOptions = ConnectionOptions()
    private var database: GattDatabase? = null
    private var negotiatedMtu: Int = 23
    private var deviceClockOffsetSeconds: Long? = null
    private var displayChallengeNotificationsEnabled = false
    private var displayControlNotificationsEnabled = false
    private var closed = false
    private val sessionSyncLock = Any()
    private var activeSessionSync: ActiveSessionSync? = null

    init {
        scope.launch {
            connection.notifications.collect(::handleNotification)
        }
        scope.launch {
            connection.state.collect { transportState ->
                if (transportState == TransportConnectionState.DISCONNECTED &&
                    mutableConnectionState.value !is ConnectionState.Failed &&
                    mutableConnectionState.value != ConnectionState.Disconnected
                ) {
                    setConnectionState(ConnectionState.Disconnected)
                }
            }
        }
    }

    internal suspend fun initialize(options: ConnectionOptions): GloResult<Unit> {
        connectionOptions = options
        return try {
            if (options.requireBond) {
                setConnectionState(ConnectionState.Bonding)
                connection.ensureBond(options.connectionTimeoutMillis)
            }

            setConnectionState(ConnectionState.NegotiatingMtu)
            negotiatedMtu = connection.requestMtu(options.requestedMtu, options.operationTimeoutMillis)

            setConnectionState(ConnectionState.DiscoveringServices)
            database = connection.discoverServices(options.operationTimeoutMillis)
            validateGatt(database ?: error("GATT database missing"))
            subscribeToCoreNotifications()

            setConnectionState(ConnectionState.Initializing)
            val deviceTime = perform(GloCommands.readDeviceTime())
            val hostTime = epochSecondsProvider()
            deviceClockOffsetSeconds = hostTime - deviceTime
            perform(GloCommands.synchronizeTime(hostTime))
            // Session Records notifications can immediately start streaming old records. Enable
            // them only after capturing the pre-sync clock offset so those dates can be reconciled.
            subscribeToSessionRecordsIfAvailable()
            refreshInternal()

            setConnectionState(ConnectionState.Ready(negotiatedMtu))
            GloResult.Success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val sdkError = error.toGloError()
            setConnectionState(ConnectionState.Failed(sdkError))
            mutableEvents.tryEmit(GloDeviceEvent.Error(sdkError))
            GloResult.Failure(sdkError)
        }
    }

    override suspend fun refresh(): GloResult<DeviceSnapshot> = readyResult { refreshInternal() }

    override suspend fun readDeviceInfo(): GloResult<DeviceInfo> = execute(GloCommands.readDeviceInfo())

    override suspend fun readBattery(): GloResult<BatteryStatus> = execute(GloCommands.readBattery())

    override suspend fun setLock(state: LockState): GloResult<LockState> = execute(GloCommands.setLock(state))

    override suspend fun setHeatingProfile(profile: HeatingProfile): GloResult<HeatingProfile> =
        execute(GloCommands.setHeatingProfile(profile))

    override suspend fun setBrightness(percent: Int): GloResult<Unit> =
        execute(GloCommands.setBrightness(percent))

    override suspend fun setAutoStart(enabled: Boolean): GloResult<SessionStatus> =
        execute(GloCommands.setAutoStart(enabled))

    override suspend fun setAutoStop(enabled: Boolean): GloResult<SessionStatus> =
        execute(GloCommands.setAutoStop(enabled))

    override suspend fun setEndOfSessionWarning(enabled: Boolean): GloResult<SessionStatus> =
        execute(GloCommands.setEndOfSessionWarning(enabled))

    override suspend fun startFind(durationSeconds: Int): GloResult<FindState> =
        execute(GloCommands.startFind(durationSeconds))

    override suspend fun stopFind(): GloResult<FindState> = execute(GloCommands.stopFind())

    override suspend fun readDisplayConfiguration(): GloResult<DisplayConfiguration> =
        execute(GloCommands.readDisplayConfiguration())

    override suspend fun requestDisplayChallenge(payloadCode: Int): GloResult<String> = readyResult {
        commandMutex.withLock { requestDisplayChallengeLocked(payloadCode) }
    }

    override suspend fun uploadSignedDisplayPayload(
        payload: SignedDisplayPayload,
        onProgress: (DisplayUploadProgress) -> Unit,
    ): GloResult<Unit> = readyResult {
        commandMutex.withLock { uploadSignedDisplayPayloadLocked(payload, onProgress) }
    }

    override suspend fun readPendingSessionCount(): GloResult<Int> = readyResult {
        commandMutex.withLock { readPendingSessionCountLocked() }
    }

    override suspend fun syncSessionRecords(): GloResult<List<GloSessionRecord>> = readyResult {
        try {
            commandMutex.withLock { syncSessionRecordsLocked() }
        } catch (error: CancellationException) {
            setSessionHistorySyncState(SessionHistorySyncState.Idle)
            throw error
        } catch (error: Throwable) {
            setSessionHistorySyncState(SessionHistorySyncState.Failed(error.toGloError()))
            throw error
        }
    }

    override suspend fun <T> execute(command: GloCommand<T>): GloResult<T> = readyResult {
        perform(command).also { result -> updateSnapshot(command.characteristic, result, command.payload) }
    }

    override suspend fun disconnect() {
        if (closed) return
        setConnectionState(ConnectionState.Disconnecting)
        try {
            connection.disconnect()
        } finally {
            failActiveSessionSync(GloError.NotConnected)
            setConnectionState(ConnectionState.Disconnected)
            closed = true
            scope.cancel()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        failActiveSessionSync(GloError.NotConnected)
        connection.close()
        mutableConnectionState.value = ConnectionState.Disconnected
        scope.cancel()
    }

    private suspend fun refreshInternal(): DeviceSnapshot {
        val info = perform(GloCommands.readDeviceInfo())
        val battery = perform(GloCommands.readBattery())
        val lock = perform(GloCommands.readLock())
        val session = perform(GloCommands.readSessionStatus())
        val find = perform(GloCommands.readFindState())
        val brightness = perform(GloCommands.readBrightness())
        val heating = perform(GloCommands.readHeatingProfile())
        return DeviceSnapshot(
            info = info,
            battery = battery,
            lockState = lock,
            sessionStatus = session,
            findState = find,
            brightnessPercent = brightness,
            heatingProfile = heating,
            updatedAtEpochMillis = System.currentTimeMillis(),
        ).also { mutableSnapshot.value = it }
    }

    private suspend fun <T> perform(command: GloCommand<T>): T = commandMutex.withLock {
        val currentDatabase = database ?: throw GloTransportException(GloError.NotConnected)
        val uuid = profile.characteristicUuid(command.characteristic)
        if (currentDatabase.findCharacteristic(uuid) == null) {
            throw GloTransportException(
                GloError.Unsupported("${command.name}: characteristic $uuid is not exposed by this device"),
            )
        }

        when (command.operation) {
            CommandOperation.READ -> {
                if (command.responseMode != CommandResponseMode.DIRECT) {
                    throw ProtocolException("Read command ${command.name} has an invalid response mode")
                }
                command.decoder.decode(connection.read(uuid, connectionOptions.operationTimeoutMillis))
            }

            CommandOperation.WRITE -> when (command.responseMode) {
                CommandResponseMode.WRITE_ACK -> {
                    connection.write(
                        uuid,
                        command.payload,
                        GattWriteType.WITH_RESPONSE,
                        connectionOptions.operationTimeoutMillis,
                    )
                    command.decoder.decode(GloBytes.EMPTY)
                }

                CommandResponseMode.NOTIFICATION_AFTER_WRITE -> awaitNotificationAfterWrite(command, uuid)
                CommandResponseMode.DIRECT -> throw ProtocolException(
                    "Write command ${command.name} cannot use a direct response",
                )
            }
        }
    }

    private suspend fun <T> awaitNotificationAfterWrite(command: GloCommand<T>, uuid: GloUuid): T =
        coroutineScope {
            val minimumSequence = connection.notificationSequence.value
            val notification = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(connectionOptions.operationTimeoutMillis) {
                    connection.notifications.first {
                        it.sequence > minimumSequence &&
                            it.characteristic == uuid &&
                            command.notificationMatcher.matches(it.value)
                    }
                } ?: throw GloTransportException(GloError.Timeout("notification for ${command.name}"))
            }
            try {
                connection.write(
                    uuid,
                    command.payload,
                    GattWriteType.WITH_RESPONSE,
                    connectionOptions.operationTimeoutMillis,
                )
                command.decoder.decode(notification.await().value)
            } catch (error: Throwable) {
                notification.cancel()
                throw error
            }
        }

    private suspend fun requestDisplayChallengeLocked(payloadCode: Int): String {
        val challengeUuid = requirePayloadCharacteristic(
            GloCharacteristic.PAYLOAD_CHALLENGE,
            requireWrite = true,
            requireNotifications = true,
        )
        if (!displayChallengeNotificationsEnabled) {
            connection.setNotifications(
                challengeUuid,
                true,
                connectionOptions.operationTimeoutMillis,
            )
            displayChallengeNotificationsEnabled = true
        }
        val response = awaitNotificationAfterRawWrite(
            writeCharacteristic = challengeUuid,
            notificationCharacteristic = challengeUuid,
            value = SignedDisplayPayloadCodec.challengeRequest(payloadCode),
            writeType = GattWriteType.WITH_RESPONSE,
            timeoutMillis = connectionOptions.operationTimeoutMillis,
        ) { bytes ->
            bytes.size >= 2 &&
                (((bytes[0].toInt() and 0xff) shl 8) or (bytes[1].toInt() and 0xff)) == payloadCode
        }
        return SignedDisplayPayloadCodec.decodeChallenge(response, payloadCode)
    }

    private suspend fun uploadSignedDisplayPayloadLocked(
        payload: SignedDisplayPayload,
        onProgress: (DisplayUploadProgress) -> Unit,
    ) {
        val versionUuid = requirePayloadCharacteristic(
            GloCharacteristic.PAYLOAD_VERSION,
            requireWrite = true,
        )
        val controlUuid = requirePayloadCharacteristic(
            GloCharacteristic.PAYLOAD_CONTROL,
            requireWrite = true,
            requireNotifications = true,
        )
        val dataUuid = requirePayloadCharacteristic(GloCharacteristic.PAYLOAD_DATA)
        val dataProperties = database?.findCharacteristic(dataUuid)?.properties.orEmpty()
        val dataWriteType = when {
            GattProperty.WRITE_WITHOUT_RESPONSE in dataProperties -> GattWriteType.WITHOUT_RESPONSE
            GattProperty.WRITE in dataProperties -> GattWriteType.WITH_RESPONSE
            else -> throw GloTransportException(
                GloError.Unsupported("Signed display payload data cannot be written"),
            )
        }

        if (!displayControlNotificationsEnabled) {
            connection.setNotifications(controlUuid, true, connectionOptions.operationTimeoutMillis)
            displayControlNotificationsEnabled = true
        }

        onProgress(DisplayUploadProgress.Preparing)
        val currentChallenge = requestDisplayChallengeLocked(
            SignedDisplayPayloadCodec.CUSTOM_DISPLAY_PAYLOAD_CODE,
        )
        if (!currentChallenge.equals(payload.challenge, ignoreCase = true)) {
            throw ProtocolException(
                "The device challenge changed before upload; request a new signed payload",
            )
        }

        val combinedPayload = SignedDisplayPayloadCodec.combine(payload)
        val chunks = SignedDisplayPayloadCodec.chunks(combinedPayload, negotiatedMtu)
        val start = awaitNotificationAfterRawWrite(
            writeCharacteristic = versionUuid,
            notificationCharacteristic = controlUuid,
            value = SignedDisplayPayloadCodec.versionHeader(combinedPayload),
            writeType = GattWriteType.WITH_RESPONSE,
            timeoutMillis = displayControlTimeoutMillis(),
        ) { bytes -> bytes.isControlCode(CONTROL_START) || bytes.isControlCode(CONTROL_ERROR) }
        requireSuccessfulControl(start, CONTROL_START, "start")

        onProgress(DisplayUploadProgress.Sending(sentChunks = 0, totalChunks = chunks.size))
        chunks.forEachIndexed { index, chunk ->
            connection.write(
                dataUuid,
                chunk,
                dataWriteType,
                connectionOptions.operationTimeoutMillis,
            )
            onProgress(
                DisplayUploadProgress.Sending(
                    sentChunks = index + 1,
                    totalChunks = chunks.size,
                ),
            )
        }

        onProgress(DisplayUploadProgress.Verifying)
        val done = awaitNotificationAfterRawWrite(
            writeCharacteristic = controlUuid,
            notificationCharacteristic = controlUuid,
            value = GloBytes.of(CONTROL_COMPLETE),
            writeType = GattWriteType.WITH_RESPONSE,
            timeoutMillis = displayControlTimeoutMillis(),
        ) { bytes -> bytes.isControlCode(CONTROL_DONE) || bytes.isControlCode(CONTROL_ERROR) }
        requireSuccessfulControl(done, CONTROL_DONE, "verification")
    }

    private suspend fun awaitNotificationAfterRawWrite(
        writeCharacteristic: GloUuid,
        notificationCharacteristic: GloUuid,
        value: GloBytes,
        writeType: GattWriteType,
        timeoutMillis: Long,
        matcher: (GloBytes) -> Boolean,
    ): GloBytes = coroutineScope {
        val minimumSequence = connection.notificationSequence.value
        val notification = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(timeoutMillis) {
                connection.notifications.first {
                    it.sequence > minimumSequence &&
                        it.characteristic == notificationCharacteristic &&
                        matcher(it.value)
                }
            } ?: throw GloTransportException(
                GloError.Timeout("signed display response"),
            )
        }
        try {
            connection.write(
                writeCharacteristic,
                value,
                writeType,
                connectionOptions.operationTimeoutMillis,
            )
            notification.await().value
        } catch (error: Throwable) {
            notification.cancel()
            throw error
        }
    }

    private fun requirePayloadCharacteristic(
        characteristic: GloCharacteristic,
        requireWrite: Boolean = false,
        requireNotifications: Boolean = false,
    ): GloUuid {
        val uuid = profile.characteristicUuid(characteristic)
        val description = database?.findCharacteristic(uuid) ?: throw GloTransportException(
            GloError.Unsupported("Signed display characteristic $characteristic is not exposed"),
        )
        if (requireWrite && GattProperty.WRITE !in description.properties) {
            throw GloTransportException(
                GloError.Unsupported("Signed display characteristic $characteristic cannot be written"),
            )
        }
        if (requireNotifications &&
            GattProperty.NOTIFY !in description.properties &&
            GattProperty.INDICATE !in description.properties
        ) {
            throw GloTransportException(
                GloError.Unsupported("Signed display characteristic $characteristic cannot notify"),
            )
        }
        return uuid
    }

    private fun requireSuccessfulControl(response: GloBytes, expectedCode: Int, stage: String) {
        if (response.isControlCode(CONTROL_ERROR)) {
            val deviceCode = if (response.size > 1) response[1].toInt() and 0xff else null
            val suffix = deviceCode?.let { " (device code $it)" }.orEmpty()
            throw ProtocolException("Signed display $stage failed$suffix")
        }
        if (!response.isControlCode(expectedCode)) {
            throw ProtocolException("Unexpected signed display response during $stage")
        }
    }

    private fun displayControlTimeoutMillis(): Long =
        maxOf(connectionOptions.operationTimeoutMillis, DISPLAY_CONTROL_TIMEOUT_MILLIS)

    private suspend fun readPendingSessionCountLocked(): Int {
        val uuid = requireSessionRecordsCharacteristic(requireRead = true, requireNotifications = false)
        val packet = BoreasSessionRecordsCodec.decode(
            connection.read(uuid, connectionOptions.operationTimeoutMillis),
        )
        val count = when (packet) {
            is BoreasSessionRecordsPacket.RecordsCount -> packet.count.toSessionCount("pending count")
            else -> throw ProtocolException(
                "Session Records read returned ${packet::class.java.simpleName}, expected a count",
            )
        }
        setPendingSessionCount(count)
        return count
    }

    private suspend fun syncSessionRecordsLocked(): List<GloSessionRecord> {
        val uuid = requireSessionRecordsCharacteristic(requireRead = true, requireNotifications = true)
        val sync = ActiveSessionSync(
            minimumSequence = connection.notificationSequence.value,
            completion = CompletableDeferred(),
        )
        synchronized(sessionSyncLock) { activeSessionSync = sync }
        setSessionHistorySyncState(SessionHistorySyncState.ReadingPendingCount)
        try {
            // Rewriting the CCCD is the observed device-side trigger for the pending-record stream.
            connection.setNotifications(uuid, true, connectionOptions.operationTimeoutMillis)
            val pending = readPendingSessionCountLocked()
            updateActiveSessionExpected(sync, pending)

            if (pending == 0) sync.completion.complete(Unit)
            val completed = withTimeoutOrNull(
                sessionHistoryTimeoutMillis(connectionOptions.operationTimeoutMillis, pending),
            ) {
                sync.completion.await()
                true
            } ?: false
            if (!completed) {
                throw GloTransportException(GloError.Timeout("session history stream"))
            }

            val received = synchronized(sessionSyncLock) { sync.receivedCounts.size }
            setSessionHistorySyncState(SessionHistorySyncState.Complete(received))
            return mutableSessionRecords.value
        } finally {
            synchronized(sessionSyncLock) {
                if (activeSessionSync === sync) activeSessionSync = null
            }
        }
    }

    private fun requireSessionRecordsCharacteristic(
        requireRead: Boolean,
        requireNotifications: Boolean,
    ): GloUuid {
        val uuid = profile.characteristicUuid(GloCharacteristic.SESSION_RECORDS)
        val description = database?.findCharacteristic(uuid) ?: throw GloTransportException(
            GloError.Unsupported("Session history characteristic $uuid is not exposed by this device"),
        )
        if (requireRead && GattProperty.READ !in description.properties) {
            throw GloTransportException(GloError.Unsupported("Session history cannot be read"))
        }
        if (requireNotifications &&
            GattProperty.NOTIFY !in description.properties &&
            GattProperty.INDICATE !in description.properties
        ) {
            throw GloTransportException(GloError.Unsupported("Session history cannot notify or indicate"))
        }
        return uuid
    }

    private suspend fun subscribeToCoreNotifications() {
        val currentDatabase = database ?: throw GloTransportException(GloError.NotConnected)
        CORE_NOTIFICATIONS.forEach { characteristic ->
            val uuid = profile.characteristicUuid(characteristic)
            val properties = currentDatabase.findCharacteristic(uuid)?.properties.orEmpty()
            if (GattProperty.NOTIFY in properties || GattProperty.INDICATE in properties) {
                connection.setNotifications(uuid, true, connectionOptions.operationTimeoutMillis)
            } else {
                throw GloTransportException(
                    GloError.Unsupported("Required notification characteristic $characteristic cannot notify"),
                )
            }
        }
    }

    private suspend fun subscribeToSessionRecordsIfAvailable() {
        val uuid = profile.characteristicUuid(GloCharacteristic.SESSION_RECORDS)
        val properties = database?.findCharacteristic(uuid)?.properties ?: return
        if (GattProperty.NOTIFY !in properties && GattProperty.INDICATE !in properties) return
        try {
            connection.setNotifications(uuid, true, connectionOptions.operationTimeoutMillis)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // History is an optional capability; a missing/broken CCCD must not reject the session.
            mutableEvents.tryEmit(GloDeviceEvent.Error(error.toGloError()))
        }
    }

    private fun validateGatt(database: GattDatabase) {
        val sessionService = profile.serviceUuid(GloService.SESSION)
        if (!database.containsService(sessionService)) {
            throw GloTransportException(
                GloError.Unsupported("Session service $sessionService is missing"),
            )
        }
        REQUIRED_CHARACTERISTICS.forEach { characteristic ->
            val uuid = profile.characteristicUuid(characteristic)
            if (database.findCharacteristic(uuid) == null) {
                throw GloTransportException(
                    GloError.Unsupported("Required characteristic $characteristic ($uuid) is missing"),
                )
            }
        }
    }

    private fun handleNotification(notification: GattNotification) {
        mutableEvents.tryEmit(GloDeviceEvent.RawNotification(notification.characteristic, notification.value))
        try {
            when (notification.characteristic) {
                profile.characteristicUuid(GloCharacteristic.BATTERY) -> {
                    val value = BoreasBatteryCodec.decode(notification.value)
                    touchSnapshot { it.copy(battery = value) }
                    mutableEvents.tryEmit(GloDeviceEvent.BatteryChanged(value))
                }

                profile.characteristicUuid(GloCharacteristic.LOCK) -> {
                    val value = LockStateCodec.decode(notification.value)
                    touchSnapshot { it.copy(lockState = value) }
                    mutableEvents.tryEmit(GloDeviceEvent.LockChanged(value))
                }

                profile.characteristicUuid(GloCharacteristic.SESSION_STATUS) -> {
                    val value = BoreasSessionStatusCodec.decode(notification.value)
                    touchSnapshot { it.copy(sessionStatus = value) }
                    mutableEvents.tryEmit(GloDeviceEvent.SessionStatusChanged(value))
                }

                profile.characteristicUuid(GloCharacteristic.FIND_GLO) -> {
                    val value = FindStateCodec.decode(notification.value)
                    touchSnapshot { it.copy(findState = value) }
                    mutableEvents.tryEmit(GloDeviceEvent.FindStateChanged(value))
                }

                profile.characteristicUuid(GloCharacteristic.HEATING_PROFILE) -> {
                    val value = HeatingProfileCodec.decode(notification.value)
                    touchSnapshot { it.copy(heatingProfile = value) }
                    mutableEvents.tryEmit(GloDeviceEvent.HeatingProfileChanged(value))
                }

                profile.characteristicUuid(GloCharacteristic.SESSION_RECORDS) -> {
                    val packet = BoreasSessionRecordsCodec.decode(notification.value)
                    handleSessionRecordsPacket(packet, notification.sequence)
                }

                profile.characteristicUuid(GloCharacteristic.DEVICE_INFO) -> {
                    when {
                        notification.value.size == 28 -> {
                            val value = BoreasDeviceInfoCodec.decode(notification.value)
                            touchSnapshot { it.copy(info = value) }
                            mutableEvents.tryEmit(GloDeviceEvent.DeviceInfoChanged(value))
                        }

                        !notification.value.isEmpty &&
                            (notification.value[0].toInt() and 0xff) == 0x0a -> {
                            val value = DisplayConfigurationCodec.decode(notification.value)
                            mutableEvents.tryEmit(GloDeviceEvent.DisplayConfigurationReceived(value))
                        }
                    }
                }
            }
        } catch (error: IllegalArgumentException) {
            val detail = (error as? ProtocolException)?.detail ?: error.message.orEmpty()
            val protocolError = GloError.ProtocolViolation(detail)
            if (notification.characteristic == profile.characteristicUuid(GloCharacteristic.SESSION_RECORDS)) {
                failActiveSessionSync(protocolError)
            }
            mutableEvents.tryEmit(GloDeviceEvent.Error(protocolError))
        } catch (error: IndexOutOfBoundsException) {
            val protocolError = GloError.ProtocolViolation(error.message ?: "Truncated notification")
            if (notification.characteristic == profile.characteristicUuid(GloCharacteristic.SESSION_RECORDS)) {
                failActiveSessionSync(protocolError)
            }
            mutableEvents.tryEmit(GloDeviceEvent.Error(protocolError))
        }
    }

    private fun handleSessionRecordsPacket(packet: BoreasSessionRecordsPacket, sequence: Long) {
        when (packet) {
            is BoreasSessionRecordsPacket.RecordsCount -> {
                val count = packet.count.toSessionCount("pending count")
                setPendingSessionCount(count)
                updateActiveSessionExpected(sequence, count)
            }

            is BoreasSessionRecordsPacket.StartOfFile -> {
                updateActiveSessionExpected(
                    sequence,
                    packet.expectedRecords.toSessionCount("stream record count"),
                )
            }

            is BoreasSessionRecordsPacket.Record -> {
                val records = listOf(reconcileSessionTimestamp(packet.value))
                mergeSessionRecords(records)
                updateActiveSessionRecords(sequence, records)
            }

            is BoreasSessionRecordsPacket.RecordBatch -> {
                val records = packet.values.map(::reconcileSessionTimestamp)
                mergeSessionRecords(records)
                updateActiveSessionRecords(sequence, records)
            }

            BoreasSessionRecordsPacket.EndOfFile -> completeActiveSessionSync(sequence)
        }
    }

    private fun mergeSessionRecords(records: List<GloSessionRecord>) {
        if (records.isEmpty()) return
        mutableSessionRecords.update { current ->
            buildMap<Long, GloSessionRecord> {
                current.forEach { put(it.count, it) }
                records.forEach { put(it.count, it) }
            }.values.sortedWith(
                compareByDescending<GloSessionRecord> { it.count }
                    .thenByDescending { it.startedAtEpochSeconds },
            )
        }
        mutableEvents.tryEmit(GloDeviceEvent.SessionRecordsReceived(records))
    }

    private fun reconcileSessionTimestamp(record: GloSessionRecord): GloSessionRecord {
        if (record.startedAtEpochSeconds >= FIRST_PLAUSIBLE_BOREAS_TIMESTAMP) return record
        val offset = deviceClockOffsetSeconds ?: return record
        val corrected = record.startedAtEpochSeconds + offset
        if (corrected !in 0L..0xffff_ffffL) return record
        return record.copy(startedAtEpochSeconds = corrected)
    }

    private fun updateActiveSessionExpected(sync: ActiveSessionSync, expected: Int) {
        synchronized(sessionSyncLock) {
            if (activeSessionSync !== sync) return
            sync.expected = expected
            publishReceivingState(sync)
            if (sync.receivedCounts.size >= expected) sync.completion.complete(Unit)
        }
    }

    private fun updateActiveSessionExpected(sequence: Long, expected: Int) {
        synchronized(sessionSyncLock) {
            val sync = activeSessionSync?.takeIf { sequence > it.minimumSequence } ?: return
            sync.expected = expected
            publishReceivingState(sync)
            if (sync.receivedCounts.size >= expected) sync.completion.complete(Unit)
        }
    }

    private fun updateActiveSessionRecords(sequence: Long, records: List<GloSessionRecord>) {
        synchronized(sessionSyncLock) {
            val sync = activeSessionSync?.takeIf { sequence > it.minimumSequence } ?: return
            records.forEach { sync.receivedCounts += it.count }
            publishReceivingState(sync)
            if (sync.expected?.let { sync.receivedCounts.size >= it } == true) {
                sync.completion.complete(Unit)
            }
        }
    }

    private fun completeActiveSessionSync(sequence: Long) {
        synchronized(sessionSyncLock) {
            val sync = activeSessionSync?.takeIf { sequence > it.minimumSequence } ?: return
            sync.completion.complete(Unit)
        }
    }

    private fun failActiveSessionSync(error: GloError) {
        synchronized(sessionSyncLock) {
            activeSessionSync?.completion?.completeExceptionally(GloTransportException(error))
        }
    }

    private fun publishReceivingState(sync: ActiveSessionSync) {
        setSessionHistorySyncState(
            SessionHistorySyncState.Receiving(sync.expected, sync.receivedCounts.size),
        )
    }

    private fun setPendingSessionCount(count: Int) {
        mutablePendingSessionCount.value = count
        mutableEvents.tryEmit(GloDeviceEvent.PendingSessionCountChanged(count))
    }

    private fun setSessionHistorySyncState(state: SessionHistorySyncState) {
        mutableSessionHistorySyncState.value = state
        mutableEvents.tryEmit(GloDeviceEvent.SessionHistorySyncChanged(state))
    }

    private fun updateSnapshot(characteristic: GloCharacteristic, value: Any?, payload: GloBytes) {
        when (value) {
            is DeviceInfo -> touchSnapshot { it.copy(info = value) }
            is BatteryStatus -> touchSnapshot { it.copy(battery = value) }
            is LockState -> touchSnapshot { it.copy(lockState = value) }
            is SessionStatus -> touchSnapshot { it.copy(sessionStatus = value) }
            is FindState -> touchSnapshot { it.copy(findState = value) }
            is HeatingProfile -> touchSnapshot { it.copy(heatingProfile = value) }
            is Int -> if (characteristic == GloCharacteristic.LED) {
                touchSnapshot { it.copy(brightnessPercent = value) }
            }
            is Unit -> if (characteristic == GloCharacteristic.LED && !payload.isEmpty) {
                touchSnapshot { it.copy(brightnessPercent = BrightnessCodec.decode(payload)) }
            }
        }
    }

    private fun touchSnapshot(transform: (DeviceSnapshot) -> DeviceSnapshot) {
        mutableSnapshot.update { current ->
            transform(current ?: DeviceSnapshot()).copy(updatedAtEpochMillis = System.currentTimeMillis())
        }
    }

    private fun setConnectionState(state: ConnectionState) {
        mutableConnectionState.value = state
        mutableEvents.tryEmit(GloDeviceEvent.ConnectionChanged(state))
    }

    private suspend inline fun <T> readyResult(crossinline block: suspend () -> T): GloResult<T> {
        if (closed || mutableConnectionState.value !is ConnectionState.Ready) {
            return GloResult.Failure(GloError.NotConnected)
        }
        return try {
            GloResult.Success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val sdkError = error.toGloError()
            mutableEvents.tryEmit(GloDeviceEvent.Error(sdkError))
            GloResult.Failure(sdkError)
        }
    }

    private companion object {
        const val FIRST_PLAUSIBLE_BOREAS_TIMESTAMP = 1_704_067_200L // 2024-01-01T00:00:00Z
        const val DISPLAY_CONTROL_TIMEOUT_MILLIS = 30_000L
        const val CONTROL_START = 1
        const val CONTROL_COMPLETE = 3
        const val CONTROL_DONE = 4
        const val CONTROL_ERROR = 5

        val CORE_NOTIFICATIONS = listOf(
            GloCharacteristic.DEVICE_INFO,
            GloCharacteristic.BATTERY,
            GloCharacteristic.LOCK,
            GloCharacteristic.SESSION_STATUS,
            GloCharacteristic.FIND_GLO,
            GloCharacteristic.HEATING_PROFILE,
        )

        val REQUIRED_CHARACTERISTICS = listOf(
            GloCharacteristic.DEVICE_INFO,
            GloCharacteristic.TIME,
            GloCharacteristic.BATTERY,
            GloCharacteristic.LOCK,
            GloCharacteristic.SESSION_STATUS,
            GloCharacteristic.FIND_GLO,
            GloCharacteristic.LED,
            GloCharacteristic.HEATING_PROFILE,
        )
    }

    private class ActiveSessionSync(
        val minimumSequence: Long,
        val completion: CompletableDeferred<Unit>,
        var expected: Int? = null,
        val receivedCounts: MutableSet<Long> = linkedSetOf(),
    )
}

private fun GloBytes.isControlCode(code: Int): Boolean =
    !isEmpty && (this[0].toInt() and 0xff) == code

internal fun sessionHistoryTimeoutMillis(operationTimeoutMillis: Long, pendingRecords: Int): Long =
    maxOf(
        operationTimeoutMillis,
        SESSION_HISTORY_BASE_TIMEOUT_MILLIS + pendingRecords.toLong() * SESSION_HISTORY_PER_RECORD_MILLIS,
    ).coerceAtMost(SESSION_HISTORY_MAX_TIMEOUT_MILLIS)

private const val SESSION_HISTORY_BASE_TIMEOUT_MILLIS = 5_000L
private const val SESSION_HISTORY_PER_RECORD_MILLIS = 300L
private const val SESSION_HISTORY_MAX_TIMEOUT_MILLIS = 120_000L

private fun Long.toSessionCount(label: String): Int {
    if (this > Int.MAX_VALUE.toLong()) {
        throw ProtocolException("Session $label exceeds the supported range: $this")
    }
    return toInt()
}

private fun Throwable.toGloError(): GloError = when (this) {
    is GloTransportException -> error
    is ProtocolException -> GloError.ProtocolViolation(detail)
    else -> GloError.Unexpected(message ?: this::class.java.simpleName)
}
