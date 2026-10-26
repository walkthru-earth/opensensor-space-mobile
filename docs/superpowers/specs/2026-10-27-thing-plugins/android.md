# Android adapters

Part of [Thing plugins](README.md). Proposal, 2026-10-27. Paths are under `app/src/main/java/space/opensensor/` unless they start with a module.

## 1. Today

- Hilt holds only single `@Binds` (`data/di/DataBindingsModule.kt`, `data/archive/ArchiveBindingsModule.kt`). No multibindings.
- `TransportSource` (`data/TransportFactory.kt`) has `ble`, `bleUnattended`, `lan` and `fake`, each keyed by a Bluetooth address, and `requireBluetooth` gates every link.
- `DeviceConnectionManager` keeps one `DeviceFacade` per BLE address. `DeviceLinkOwner` owns pairing, the LAN token, reboot and forget.
- `KeepAliveKind { PROCESS, WIFI }` maps onto one `SyncForegroundService` that starts as `connectedDevice` and falls back to `dataSync`. `SyncWorker` is `dataSync`. Its triggers (companion presence, advert flags, Wi-Fi arrival) are board triggers.
- `AccessKind` covers Bluetooth, local network, notifications, approximate location and camera. `ManifestPermissionsTest` pins the merged permission set.
- Location is one shot, `getCurrentLocation` with `QUALITY_LOW_POWER` (`feature/device/src/androidMain/.../DeviceLocation.android.kt`).
- `bluetooth_le required=true` excludes phones without BLE.

## 2. Adapter pattern

Each plugin registers itself into the common contract through Hilt multibindings.

```kotlin
@Module @InstallIn(SingletonComponent::class)
abstract class BarometerSourceModule {
    @Binds @IntoMap @KindKey("phone.barometer")
    abstract fun bind(s: AndroidBarometerSampler): SamplerPort
}
```

The Hilt graph then builds `ThingKinds(listOf(...))` once from the map.

| Adapter | API | Notes |
| --- | --- | --- |
| `BleBoardSource` | today's `DeviceLinkOwner` | wraps, no behaviour change |
| `AndroidBarometerSampler` | `SensorManager`, `TYPE_PRESSURE` | 1 Hz, `maxReportLatencyUs` about 60 s so the sensor hub batches |
| `AudioLevelSampler` | `AudioRecord` | dBFS only, never stores audio, marked uncalibrated |
| `CellLocationSampler` | fused provider | fix becomes an H3 cell inside the adapter through `CellFix` |
| `HealthConnectReader` | Health Connect | `PrivateRead`, never on the publish path |

- Permissions. Each plugin contributes rows to the trigger table with `@IntoSet`, so `PermissionTriggers` merges them and each plugin owns its prompts.
- `KeepAliveKind` grows to `CONNECTED_DEVICE, LOCATION, MICROPHONE, DATA_SYNC, HEALTH, WIFI`.

## 3. One recording session service

- `RecordingSessionService` is declared `location|microphone|health|connectedDevice`. The user's Start tap passes the union of the selected samplers' types to `ServiceCompat.startForeground`, so the service claims only what is in use and granted. A mismatch throws `SecurityException`, so the union is computed from granted access.
- It owns one `RecordingSession` (session id, clock, writer) and leases it to every sampler.
- `SyncForegroundService` stays the file transfer host.

## 4. Platform constraints

- Service types ([types](https://developer.android.com/develop/background-work/services/fgs/service-types)). `location` needs a granted location permission and cannot start from the background without background location. `microphone` needs `RECORD_AUDIO` and cannot start from the background or boot. So recording starts from a visible tap.
- `dataSync` is capped at 6 hours per 24 on API 35 targets, the budget is shared by all services of the type, and `onTimeout` must stop within seconds ([timeout](https://developer.android.com/develop/background-work/services/fgs/timeout)). Never host recording on `dataSync`.
- Android 16 counts jobs that run while a service is up against the job quota ([16 changes](https://developer.android.com/about/versions/16/behavior-changes-all)).
- Background apps get no sensor events since Android 9 ([sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)).
- Play treats aggregating location for traffic patterns as foreground work ([policy](https://support.google.com/googleplay/android-developer/answer/9799150)). Stay with while in use plus the `location` service. Coarse location is about 3 km² and cannot fill a res 8 cell, so a private res 8 track needs fine location (owner question 5).
- Health Connect needs `health.READ_*`, a privacy policy activity (`ACTION_SHOW_PERMISSIONS_RATIONALE` up to 13, an `activity-alias` with `VIEW_PERMISSION_USAGE` from 14), Limited Use and a declaration form ([get started](https://developer.android.com/health-and-fitness/guides/health-connect/develop/get-started), [policy](https://support.google.com/googleplay/android-developer/answer/12991134)). Targeting 16 replaces `BODY_SENSORS` with `health.*`.
- Doze defers periodic work. Only a foreground service keeps sampling going. OEM battery killers need a measured run on the OnePlus.

## 5. Manifest and Play forms per plugin

| Plugin | Manifest | Play |
| --- | --- | --- |
| BLE board | as today | `connectedDevice` and `dataSync` declarations |
| Barometer | `uses-feature sensor.barometer required=false` | Data safety only if published |
| Mic level | `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE` | `microphone` declaration with video, Data safety Audio if published |
| GPS to cell | `ACCESS_FINE_LOCATION`, `FOREGROUND_SERVICE_LOCATION` | `location` declaration, Data safety precise location if published |
| Health | `health.READ_*`, rationale activity and alias | Health Connect declaration, Limited Use |

## 6. Modules

- One Gradle module per plugin (`:plugin:barometer`) with its own manifest fragment, Hilt module and tests. `ManifestPermissionsTest` checks the merged result.
- Dynamic feature modules are not worth it. Their manifests merge into the base at build time, Hilt has no direct support, and iOS has no equivalent. A product flavor can leave out mic or health if a lean build is ever needed.
- Each permission lands in the same release as its feature.

## 7. First step

1. `BleBoardSource` behind `ThingKinds`, no behaviour change.
2. `:plugin:barometer` with `RecordingSessionService` started from a tap, local Parquet only, no new permission beyond the foreground service type in use.
3. Dated OnePlus run in `docs/status.md`, then decide mic and fine location.
