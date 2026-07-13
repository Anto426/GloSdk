package com.anto426.glo.sdk.event

import com.anto426.glo.sdk.model.BatteryStatus
import com.anto426.glo.sdk.model.ConnectionState
import com.anto426.glo.sdk.model.DeviceInfo
import com.anto426.glo.sdk.model.DisplayConfiguration
import com.anto426.glo.sdk.model.FindState
import com.anto426.glo.sdk.model.GloBytes
import com.anto426.glo.sdk.model.GloError
import com.anto426.glo.sdk.model.HeatingProfile
import com.anto426.glo.sdk.model.LockState
import com.anto426.glo.sdk.model.GloSessionRecord
import com.anto426.glo.sdk.model.SessionHistorySyncState
import com.anto426.glo.sdk.model.SessionStatus
import com.anto426.glo.sdk.protocol.GloUuid

public sealed interface GloDeviceEvent {
    public data class ConnectionChanged(public val state: ConnectionState) : GloDeviceEvent
    public data class DeviceInfoChanged(public val info: DeviceInfo) : GloDeviceEvent
    public data class BatteryChanged(public val battery: BatteryStatus) : GloDeviceEvent
    public data class LockChanged(public val state: LockState) : GloDeviceEvent
    public data class SessionStatusChanged(public val status: SessionStatus) : GloDeviceEvent
    public data class FindStateChanged(public val state: FindState) : GloDeviceEvent
    public data class HeatingProfileChanged(public val profile: HeatingProfile) : GloDeviceEvent
    public data class DisplayConfigurationReceived(public val configuration: DisplayConfiguration) : GloDeviceEvent
    public data class SessionRecordsReceived(public val records: List<GloSessionRecord>) : GloDeviceEvent
    public data class PendingSessionCountChanged(public val count: Int) : GloDeviceEvent
    public data class SessionHistorySyncChanged(public val state: SessionHistorySyncState) : GloDeviceEvent
    public data class RawNotification(public val characteristic: GloUuid, public val value: GloBytes) : GloDeviceEvent
    public data class Error(public val error: GloError) : GloDeviceEvent
}
