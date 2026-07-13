# GloSdk

SDK Android/Kotlin indipendente per comunicare via BLE con dispositivi glo di proprietà
dell'utente. Il progetto non dipende da Glow, Compose o dai suoi ViewModel.

La prima implementazione interoperabile è mirata al modello **Boreas / glo Hyper Pro+**.
UUID, pacchetti e comandi stabili derivano dall'analisi statica dello SDK Android e da una
cattura HCI sul dispositivo fisico. Le funzioni non catturate sono separate e marcate
`@ExperimentalGloApi`.

## Moduli

- `glo-api`: JAR Kotlin/JVM con API pubbliche, modelli, UUID, comandi, codec e astrazione
  del trasporto. Non importa classi `android.*`.
- `glo-ble-android`: AAR con scanner BLE Android, bonding, connessione GATT, negoziazione
  MTU, service discovery, coda seriale delle operazioni, CCCD e client di alto livello.

Coordinate:

```text
com.anto426.glo:glo-api:0.1.0-SNAPSHOT
com.anto426.glo:glo-ble-android:0.1.0-SNAPSHOT
```

## Importazione durante lo sviluppo

Nel `settings.gradle.kts` dell'app:

```kotlin
includeBuild("../GloSdk")
```

Nel modulo Android dell'app:

```kotlin
dependencies {
    implementation("com.anto426.glo:glo-ble-android:0.1.0-SNAPSHOT")
}
```

Gradle sostituisce automaticamente le coordinate con i moduli del progetto separato.
In alternativa:

```powershell
.\gradlew.bat publishToMavenLocal
```

e l'app può risolvere le stesse coordinate da `mavenLocal()`.

## Uso minimo

L'host deve richiedere a runtime le autorizzazioni restituite da
`AndroidBlePermissions.requiredRuntimePermissions()`; lo SDK non mostra UI e non richiede
permessi autonomamente.

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

Lo stato reattivo è disponibile tramite `StateFlow`:

- `GloDeviceManager.scanState`
- `GloDeviceManager.discoveredDevices`
- `GloDeviceManager.pairedDevices`
- `GloDeviceManager.activeSession`
- `GloDeviceSession.connectionState`
- `GloDeviceSession.snapshot`
- `GloDeviceSession.events`

## Inizializzazione della sessione

Il client Android esegue in ordine:

1. connessione LE;
2. creazione o riuso del bond;
3. richiesta MTU 517 e memorizzazione del valore negoziato;
4. service discovery per UUID;
5. scrittura `01 00` nei CCCD delle characteristic di stato;
6. lettura e sincronizzazione dell'ora `uint32` big-endian;
7. lettura iniziale di device info, batteria, lock, session status, FindGlo, LED e profilo.

Ogni operazione GATT è serializzata. Per i comandi confermati da notification, il listener
viene registrato prima della write, poi vengono attesi sia il write acknowledgement sia la
notification semantica.

## Sicurezza e limiti

- I comandi ordinari usano il bonding e la cifratura link-layer BLE; nelle catture non sono
  comparsi nonce, token, MAC o cifratura applicativa aggiuntiva.
- Le catture HCI e le chiavi di bonding non sono incluse in questo repository.
- Reset, upload Payload/Greetings, age verification e OTA non fanno parte del percorso
  stabile: mancano ancora catture dinamiche complete.
- Gli handle GATT osservati non sono codificati: servizi e characteristic vengono sempre
  risolti tramite UUID.
- La lista dei dispositivi associati espone solo nomi riconosciuti come Boreas / Hyper Pro+;
  gli altri bond Bluetooth vengono esclusi e i profili non verificati non sono collegabili.

Dettagli: [docs/PROTOCOL_SUPPORT.md](docs/PROTOCOL_SUPPORT.md).

## Verifica

```powershell
.\gradlew.bat :glo-api:test :glo-ble-android:testDebugUnitTest :glo-ble-android:assembleRelease
```
