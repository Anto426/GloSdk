# GloSdk

Independent Android/Kotlin SDK to communicate via BLE with user-owned glo devices.
The project does not depend on Glow, Compose, or their ViewModels.

The first interoperable implementation targets the **Boreas / glo Hyper Pro+** model.
Stable UUIDs, packets, and commands are derived from static analysis of the Android SDK and an HCI capture on the physical device. Uncaptured functions are separated and marked `@ExperimentalGloApi`.

## Modules

- `glo-api`: Kotlin/JVM JAR with public APIs, models, UUIDs, commands, codecs, and transport abstraction. Does not import `android.*` classes.
- `glo-ble-android`: AAR with Android BLE scanner, bonding, GATT connection, MTU negotiation, service discovery, serial operation queue, CCCD, and high-level client.

Coordinates:

```text
com.anto426.glo:glo-api:0.1.0-SNAPSHOT
com.anto426.glo:glo-ble-android:0.1.0-SNAPSHOT
```

## Importing during development

In the app's `settings.gradle.kts`:

```kotlin
includeBuild("../GloSdk")
```

In the app's Android module:

```kotlin
dependencies {
    implementation("com.anto426.glo:glo-ble-android:0.1.0-SNAPSHOT")
}
```

Gradle automatically replaces coordinates with the modules of the separate project.
Alternatively:

```powershell
.\gradlew.bat publishToMavenLocal
```

and the app can resolve the same coordinates from `mavenLocal()`.

## Minimal usage

The host must request at runtime the permissions returned by `AndroidBlePermissions.requiredRuntimePermissions()`; the SDK does not display a UI and does not request permissions autonomously.

```kotlin
val manager = AndroidGloSdk.create(applicationContext)

val paired = manager.refreshPairedDevices().getOrNull().orEmpty()
val deviceId = paired.firstOrNull()?.id ?: run {
    manager.startScan()
    manager.discoveredDevices.first { it.isNotEmpty() }.first().id
}

when (val connected = manager.connect(deviceId)) {
    is GloResult.Success -> {
        val session = connected.value
        session.readBattery()
        session.setLock(LockState.LOCKED)
        session.setHeatingProfile(HeatingProfile.BOOST)
        session.startFind(durationSeconds = 60)
    }
    is GloResult.Failure -> handleError(connected.error)
}
```

Reactive state is available via `StateFlow`:

- `GloDeviceManager.scanState`
- `GloDeviceManager.discoveredDevices`
- `GloDeviceManager.pairedDevices`
- `GloDeviceManager.activeSession`
- `GloDeviceSession.connectionState`
- `GloDeviceSession.snapshot`
- `GloDeviceSession.events`

## Session Initialization

The Android client executes in order:

1. LE connection;
2. creation or reuse of the bond;
3. MTU 517 request and storage of the negotiated value;
4. service discovery for UUIDs;
5. write `01 00` to the CCCDs of the status characteristics;
6. read and synchronization of the big-endian `uint32` time;
7. initial read of device info, battery, lock, session status, FindGlo, LED, and profile.

Every GATT operation is serialized. For commands confirmed by notifications, the listener is registered before the write, and then both the write acknowledgement and the semantic notification are awaited.

## Security and Limitations

- Ordinary commands use bonding and BLE link-layer encryption; no nonce, token, MAC, or additional application-layer encryption appeared in the captures.
- HCI captures and bonding keys are not included in this repository.
- Reset, Payload/Greetings upload, age verification, and OTA are not part of the stable path: complete dynamic captures are still missing.
- Observed GATT handles are not hardcoded: services and characteristics are always resolved via UUIDs.
- The list of paired devices only exposes names recognized as Boreas / Hyper Pro+; other Bluetooth bonds are excluded and unverified profiles cannot be connected.

Details: [docs/PROTOCOL_SUPPORT.md](docs/PROTOCOL_SUPPORT.md).

## Verification

```powershell
.\gradlew.bat :glo-api:test :glo-ble-android:testDebugUnitTest :glo-ble-android:assembleRelease
```
