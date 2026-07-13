package com.anto426.glo.sdk.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Permission helper. The SDK reports missing permissions but never opens UI or requests them. */
public object AndroidBlePermissions {
    @JvmStatic
    public fun requiredRuntimePermissions(): Set<String> = if (Build.VERSION.SDK_INT >= 31) {
        setOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        setOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    @JvmStatic
    public fun missing(context: Context): Set<String> = requiredRuntimePermissions().filterTo(linkedSetOf()) {
        context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }
}
