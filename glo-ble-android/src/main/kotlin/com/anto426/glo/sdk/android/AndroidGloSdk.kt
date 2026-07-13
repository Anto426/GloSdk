package com.anto426.glo.sdk.android

import android.content.Context
import com.anto426.glo.sdk.api.GloDeviceManager
import com.anto426.glo.sdk.android.internal.AndroidBleTransport
import com.anto426.glo.sdk.android.internal.AndroidFindMyGloApi
import com.anto426.glo.sdk.android.internal.AndroidGloDeviceManager

/** Entry point for the Android BLE implementation. Keep one manager per application process. */
public object AndroidGloSdk {
    @JvmStatic
    public fun create(context: Context): GloDeviceManager {
        val applicationContext = context.applicationContext
        return AndroidGloDeviceManager(
            transport = AndroidBleTransport(applicationContext),
            findMyGlo = AndroidFindMyGloApi(applicationContext),
        )
    }
}
