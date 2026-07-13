package com.anto426.glo.sdk.android.internal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.util.Base64
import com.anto426.glo.sdk.api.FindMyGloApi
import com.anto426.glo.sdk.model.DeviceId
import com.anto426.glo.sdk.model.GloDeviceLocation
import com.anto426.glo.sdk.model.GloError
import com.anto426.glo.sdk.model.GloResult
import com.anto426.glo.sdk.model.LocationSource
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal class AndroidFindMyGloApi(context: Context) : FindMyGloApi {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val mutableLocations = MutableStateFlow(loadLocations())

    override val locations: StateFlow<Map<DeviceId, GloDeviceLocation>> =
        mutableLocations.asStateFlow()

    override fun lastKnownLocation(deviceId: DeviceId): GloDeviceLocation? =
        mutableLocations.value[deviceId]

    override suspend fun captureCurrentLocation(deviceId: DeviceId): GloResult<GloDeviceLocation> {
        if (!hasLocationPermission()) {
            return GloResult.Failure(
                GloError.MissingPermission(setOf(Manifest.permission.ACCESS_FINE_LOCATION)),
            )
        }

        val previous = lastKnownLocation(deviceId)
        if (previous != null && System.currentTimeMillis() - previous.capturedAtEpochMillis < 30_000L) {
            return GloResult.Success(previous)
        }

        val cached = bestLastKnownLocation()
        val location = currentLocation() ?: cached
            ?: return GloResult.Failure(GloError.Unexpected("Phone location is unavailable"))
        val captured = location.toSdkLocation(deviceId)
        persist(captured)
        return GloResult.Success(captured)
    }

    private fun hasLocationPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun bestLastKnownLocation(): Location? = providers()
        .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
        .maxByOrNull(Location::getTime)

    private suspend fun currentLocation(): Location? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val provider = providers().firstOrNull { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return null
        return withTimeoutOrNull(5_000L) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                runCatching {
                    locationManager.getCurrentLocation(
                        provider,
                        signal,
                        Executor(Runnable::run),
                    ) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                }.onFailure {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }

    private fun providers(): List<String> = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    )

    private fun Location.toSdkLocation(deviceId: DeviceId): GloDeviceLocation = GloDeviceLocation(
        deviceId = deviceId,
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracy.takeIf { hasAccuracy() },
        capturedAtEpochMillis = time.takeIf { it > 0L } ?: System.currentTimeMillis(),
        source = when (provider) {
            LocationManager.GPS_PROVIDER -> LocationSource.GPS
            LocationManager.NETWORK_PROVIDER -> LocationSource.NETWORK
            LocationManager.PASSIVE_PROVIDER -> LocationSource.PASSIVE
            else -> LocationSource.UNKNOWN
        },
    )

    private fun persist(location: GloDeviceLocation) {
        val value = listOf(
            location.latitude,
            location.longitude,
            location.accuracyMeters ?: "",
            location.capturedAtEpochMillis,
            location.source.name,
        ).joinToString("|")
        preferences.edit().putString(storageKey(location.deviceId), value).apply()
        mutableLocations.value = mutableLocations.value + (location.deviceId to location)
    }

    private fun loadLocations(): Map<DeviceId, GloDeviceLocation> = preferences.all.mapNotNull { (key, raw) ->
        if (!key.startsWith(KEY_PREFIX) || raw !is String) return@mapNotNull null
        runCatching {
            val deviceId = DeviceId(
                String(
                    Base64.decode(key.removePrefix(KEY_PREFIX), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP),
                    StandardCharsets.UTF_8,
                ),
            )
            val parts = raw.split('|')
            GloDeviceLocation(
                deviceId = deviceId,
                latitude = parts[0].toDouble(),
                longitude = parts[1].toDouble(),
                accuracyMeters = parts[2].takeIf(String::isNotEmpty)?.toFloat(),
                capturedAtEpochMillis = parts[3].toLong(),
                source = LocationSource.valueOf(parts[4]),
            )
        }.getOrNull()
    }.associateBy(GloDeviceLocation::deviceId)

    private fun storageKey(deviceId: DeviceId): String = KEY_PREFIX + Base64.encodeToString(
        deviceId.value.toByteArray(StandardCharsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
    )

    private companion object {
        const val PREFERENCES_NAME = "find_my_glo_locations"
        const val KEY_PREFIX = "location_"
    }
}
