package com.anto426.glo.sdk.model

/** A supported glo device already paired with the host operating system. */
public data class PairedGloDevice(
    public val id: DeviceId,
    public val name: String?,
    public val model: DeviceModel,
    public val bondState: BondState,
)
