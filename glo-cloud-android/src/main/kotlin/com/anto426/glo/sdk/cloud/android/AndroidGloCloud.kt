package com.anto426.glo.sdk.cloud.android

import android.content.Context
import com.anto426.glo.sdk.api.cloud.GloCloudClient

public object AndroidGloCloud {
    @JvmStatic
    public fun create(context: Context): GloCloudClient =
        OfficialGloCloudClient(context.applicationContext)
}
