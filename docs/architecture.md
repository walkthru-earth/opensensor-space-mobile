# Architecture of Android, iOS, shared modules, and what a change touches

**Scope:** module boundaries, dependency direction, package layout, and platform ownership.
**Code:** `settings.gradle.kts`, `build-logic/`, `core/*/build.gradle.kts`, `app/build.gradle.kts`, `build.gradle.kts` (`verify` task); `data/DeviceConnectionManager.kt`, `DeviceConnectionRepository.kt`, `DeviceIdentityClaims.kt`.
**Related:** [data-model.md](data-model.md) (storage), [transports.md](transports.md) · [sync-engine.md](sync-engine.md) · [history-pipeline.md](history-pipeline.md) (the layers in detail).

## Module graph

```mermaid
flowchart TB
  subgraph core["Kotlin core, JVM and iOS, no Android, Hilt, Room or Log"]
    PR[":core:protocol<br/>common wire rules + JVM adapters"]
    PQ[":core:parquet<br/>reader + writer, ByteReader, Thrift compact, LZ4/Snappy/GZIP, codec SPI, WKB"]
    AQ[":core:airquality<br/>WHO 2021 scales and palette"]
    HI[":core:history<br/>sensor rules, file index, aggregation, calendar windows"]
    PF[":core:publicfeed<br/>Discover feed port, H3, viewport cover, sample"]
    UN[":core:units<br/>display units, axis ticks"]
    AR1[":core:archive<br/>layout, verifier, records, store ports"]
    SE[":core:settings<br/>app preferences on DataStore, detail level"]
    SY1[":core:sync<br/>device session, policies, planner, progress, LAN framing"]
  end
  subgraph ui["Compose Multiplatform, Android and iOS"]
    DS[":ui:designsystem<br/>tokens, theme, fonts, floating controls"]
    PM[":ui:permissions<br/>trigger table, permission policy, settings link"]
    DI[":feature:discover<br/>map, cell sheet, chart"]
    FS[":feature:settings<br/>Settings screen and controller"]
    SH[":feature:shell<br/>AppKey, TabNavigator, AppShell, bottom bar"]
  end
  APP[":app (Android)<br/>Hilt, Room v10, DataStore, WorkManager, Compose, zstd-jni"]
  IOS["ios/App (Xcode + Swift)<br/>Core Bluetooth, Bonjour, own sync engine and archive"]
  IS[":ios:shared (iOS only)<br/>CompanionScreen, AppRoot, static OpenSensorShared framework"]
  HI --> PQ & PR & AQ
  PF --> PQ & AQ
  DI --> PF & UN & DS & PM
  SE --> UN
  FS --> SE & DS
  SH --> DS
  APP --> HI & PF & UN & DI & DS & SE & FS & PM & SH
  IS --> HI & PR & UN & DI & DS & SE & FS & PM & SH
  IOS --> IS
```

`:core:history` exposes `:core:parquet`, `:core:protocol` and `:core:airquality` as `api`, and
`:core:publicfeed` exposes `:core:parquet` and `:core:airquality`. A `:core` class cannot import
`android.*`, and the build enforces it. Every `:core` module compiles for JVM and iOS from
`src/commonMain`. Platform edges live in `src/jvmMain` (Java files, streams, `FileChannel` mapping,
`java.util.zip`, `com.uber:h3`) and `src/iosMain` (`NSData` mapping, system zlib, H3 C cinterop).
Tests live in `src/commonTest` (JVM and iOS simulator) or `src/jvmTest` / `src/iosTest`. Calendar
windows use kotlinx-datetime. Android passes its `ZoneId` through `toKotlinTimeZone()`.

`:ui:designsystem`, `:ui:permissions`, `:feature:discover`, `:feature:settings` and `:feature:shell` target Android (the AGP KMP library target, not `jvm`)
and iOS. `:ios:shared` targets iOS only, so its `commonMain` consumes them directly. It renders the
current Compose iOS screen. `ios/App` supplies native app integration, Core Bluetooth, Bonjour
discovery, and today a second sync engine and archive in Swift. The Android app remains a separate
platform entry point. See [ios-migration.md](ios-migration.md) for current scope and build commands.

Navigation is shared ([ADR 0001](adr/0001-shared-navigation.md)). `:feature:shell` holds the
sealed `AppKey : NavKey` hierarchy, `AppNavConfig` (a `SavedStateConfiguration` built with
`subclassesOfSealed`, so no reflection on iOS), the Compose free `TabNavigator` and `AppShell`, which
draws the one bottom bar around one Navigation 3 `NavDisplay` with a back stack per tab. It uses the
JetBrains `navigation3-ui` and `lifecycle-viewmodel-navigation3`, which resolve on Android to the
same androidx binaries. Both apps host `AppShell` and pass their screens in as entries. Android
entries keep `hiltViewModel()`, iOS entries hold plain controllers in a `viewModel { }` holder.
Screens that take Back first use `NavigationBackHandler`, never `BackHandler`.

### Target module graph

The graph above is today's code. [platform-parity-plan.md](platform-parity-plan.md) moves toward
the graph below, where every rule that must give the same answer on both phones is shared and
platform code only adapts OS services. `:core:sync`, `:core:archive`, `:core:settings` and
`:feature:settings` exist. The sync engine itself and the store ports' Room KMP backing are wave 3. `:core:model` (device families and metrics
as data) is planned in [production-readiness-plan.md](production-readiness-plan.md) W11 and
[devices-and-discovery.md](devices-and-discovery.md).

```mermaid
flowchart TB
  subgraph shared["Kotlin commonMain, Android and iOS"]
    MO[":core:model (planned)<br/>devices, families, capabilities, properties, scales"]
    PR2[":core:protocol"]
    SY[":core:sync (planned)<br/>engine behind FrameLink, ArchiveStore, ArchiveIndex, CredentialStore"]
    AR[":core:archive<br/>layout, verifier, records, store ports"]
    ST[":core:settings<br/>DataStore preferences"]
    PQ2[":core:parquet"]
    HI2[":core:history"]
    PF[":core:publicfeed"]
    DS[":ui:designsystem<br/>tokens, theme, components"]
    FE[":feature:* (planned)<br/>settings, devices, history, files, device, debug"]
    DI[":feature:discover"]
  end
  APP2[":app, Android adapters and Hilt edge"]
  IOS2["ios/App, Swift OS adapters and :ios:shared"]
  SY --> PR2 & AR & MO
  AR --> PQ2
  HI2 --> PQ2 & MO
  FE --> SY & ST & HI2 & DS
  DI --> PF & DS
  APP2 --> FE & DI
  IOS2 --> FE & DI
```

## Build conventions (`build-logic/`)

`settings.gradle.kts` includes `build-logic/` through `pluginManagement { includeBuild(...) }`.
It holds four convention plugins, so a new shared module is one `plugins {}` line.

| Plugin | Applied by | Sets |
| --- | --- | --- |
| `opensensor.kmp.library` | every `:core` module | Kotlin Multiplatform with `jvm` (bytecode 17), `iosArm64`, `iosSimulatorArm64`, `kotlin-test` in `commonTest`, a `test` task that runs `jvmTest`. Adds `jvmTest` to `verify` and `iosSimulatorArm64Test` to `verifyIos` |
| `opensensor.kmp.compose-feature` | `:ui:designsystem`, `:ui:permissions`, `:feature:discover` | the AGP KMP Android library target (namespace `earth.walkthru.opensensor.<module>`, SDK 37, min 31, Android resources, host tests), `iosArm64`, `iosSimulatorArm64`, Compose runtime, foundation, Material 3, UI and resources, internal resources class in `<namespace>.resources`, `kotlin-test` in `commonTest` (runs in the Android host test and on the simulator), a `test` task that runs `testAndroidHostTest`. Adds `testAndroidHostTest` to `verify` and `iosSimulatorArm64Test` to `verifyIos` |
| `opensensor.kmp.ios-framework` | `:ios:shared` | iOS only Kotlin Multiplatform with the static `OpenSensorShared` framework for each iOS target. Adds `iosSimulatorArm64Test` and `linkDebugFrameworkIosSimulatorArm64` to `verifyIos` |
| `opensensor.jvm.library` | none today (kept for a future JVM-only module) | Kotlin/JVM with Java and Kotlin bytecode 17 |

The conventions compile against the Kotlin, Android and Compose Gradle plugins as `compileOnly`.
The root `build.gradle.kts` declares every plugin once with `apply false`, so all modules share one
plugin classloader. Bytecode 17 is a compiler target, not `jvmToolchain(17)`, because the bundled
Android Studio JBR and CI provide JDK 21 only and a toolchain would need a network download.
Warnings are not errors yet, since existing code still has a few.

The real Parquet fixtures live once in `core/parquet/src/test/resources` and are published as
`:core:parquet` test fixtures (a hand-declared variant with the `java-test-fixtures` capability,
because a multiplatform module cannot apply that plugin). `:core:history` and `:core:protocol` tests
declare `testFixtures(project(":core:parquet"))` instead of pointing at another module's
directory. `:app` still adds that directory to its unit-test resources.

### Gates

The root build registers `verify` and `verifyIos` and lists only the `:app` tasks (`lintDebug`,
`assembleDebug`, `testDebugUnitTest`). Each convention plugin adds its own module's test tasks
(`build-logic/src/main/kotlin/Gates.kt`), so a new module is gated as soon as it applies a
convention. On a Mac host `verify` also runs `verifyIos`. Set `-Popensensor.skipIos=true` to skip
it. `verifyIos` includes `:core:parquet:iosSimulatorArm64Test`, which decodes the real fixtures on
the simulator. `app/src/androidTest` is in neither gate.

## Layers inside `:app`

```mermaid
flowchart LR
  UI["ui/* Compose screens + address-bound ViewModels"] --> MANAGER["DeviceConnectionManager<br/>process-scoped controller registry + snapshots"]
  MANAGER --> REPO["DeviceConnectionRepository per address<br/>session, sync, reconnect, activeTransport"]
  REPO --> SESS["data/ble/DeviceSession<br/>one request at a time"]
  SESS --> TR{{"AqTransport"}}
  TR --> BLE["data/ble/BleTransport"]
  TR --> LAN["data/lan/TcpTransport"]
  TR --> FAKE["data/fake/FakeTransport (debug)"]
  REPO --> SYNC["data/sync/SyncEngine → FileArchive"]
  SYNC --> ROOM[("Room + filesDir")]
  ROOM --> HIST["data/history/* (Indexer, Deriver, Repository)"]
  HIST --> UI
  WORK["data/background/SyncWorker<br/>run-owned foreground progress"] --> MANAGER
  REPO --> CLAIM["DeviceIdentityClaims<br/>one owner per full hardware ID"]
  REPO --> BUDGET["CollectionBudget<br/>two active transfers process-wide"]
```

## Package map

| Path (under `app/src/main/java/earth/walkthru/aqsync/`) | Holds | Android-only? |
| --- | --- | --- |
| `data/DeviceConnectionManager.kt`, `data/DeviceConnectionRepository.kt`, `data/TransportFactory.kt` | Process-owned registry; one bound controller per public BLE address owns its transport, session, state and reconnect; factory creates the pipes | yes |
| `data/DeviceIdentityClaims.kt`, `data/DeviceContinuityPolicy.kt` | Shared two-transfer budget; full hardware identity claims and authenticated continuity policy | no (JVM logic with DI bindings) |
| `data/ble/` | `BleScanner`, `BleTransport` (GATT, serialized ops), `DeviceSession` (every request), `FrameRecorder` (last 200 frames, redacts) | yes |
| `data/lan/` | `TcpTransport`, `LanDiscovery` (NsdManager, shared legacy resolver), `LanDiscoveryState` (foreground browse), `LanWifiLock` (transfer leases), `LanTokenPolicy` (pure) | yes (policy/lease accounting: no) |
| `data/sync/` | `SyncPlanner`, `SyncEngine`, `FileArchive`, `ReadWindow`, `SyncResumePolicy` | files + Room |
| `data/background/` | `SyncForegroundService`, `SyncNotifications`, `SyncWorker`, `AutoSyncScheduler`, `AutoSyncRunner` (pure), `AppAutoSyncGateway`; triggers `CompanionPresence` + `CompanionPresenceService`, `AdvertFlagScan` + `AdvertScanReceiver`, `SyncTriggers` + `WifiArrivalReceiver` + `BootReceiver` | yes (runner: no) |
| `data/history/` | `HistoryIndexer`, `HistoryDeriver`, `DailyDerived*`, `HistoryRepository`, `parquet/AppParquetCodecs` (ZSTD) | Room + files |
| `data/local/database/` | `AppDatabase` (v10), `KnownDevice`, `StationEpoch`, `ArchivedFile`, `History.kt` (`MetricBucket`, `DerivedDay`) | yes |
| `data/settings/AppSettings.kt` | Android view of `:core:settings` (`DetailLevel` Simple · Detailed · Developer, clock consent, units, public sensors) | yes |
| `data/export/ExportManager.kt` | share / zip / SAF | yes |
| `data/fake/FakeTransport.kt` | in-process protocol-v2 device with fault hooks | no (JVM) |
| `ui/scan · device (Today, Device, NowCard) · history · files · debug · settings · permissions · theme` | Compose; `Navigation.kt` supplies the Android entries to the shared `AppShell` (`:feature:shell`) | yes |

The iOS entry point is `ios/OpenSensor.xcodeproj`. Its Swift adapters own Apple services;
the current screen and presentation state live in `ios/shared`. Follow [`../ios/AGENTS.md`](../ios/AGENTS.md)
for iOS-specific guidance. SwiftUI review rules apply only if native SwiftUI views are introduced.

| `:core` module (`core/<m>/src/<set>/kotlin/earth/walkthru/aqsync/`) | Files |
| --- | --- |
| `protocol` → `data/protocol/` | `commonMain`: `AqGatt`, `Frames`, `WireBuffer`, `DeviceConfig`, `ConfigChunkAccumulator`, `JsonModels`, `LanProtocol`, `Advertisement`, `LiveProfile`, `PlantowerBatchCandidate`, `Crc32` (`expect`), `ParquetVerify`, `SafeName`. `jvmMain`: `AqUuids`, `LanFraming`, `Crc32` (`java.util.zip`), `ParquetVerifyJvm` (file, stream and SHA-256 extensions). `iosMain`: `Crc32` (portable table) |
| `parquet` → `data/history/parquet/` | `commonMain`: `ParquetFile`, `SuspendParquetFile`, `ByteReader`, `SuspendByteReader`, `LocalFile` (`expect`), `ParquetFooterParser`, `ParquetMetadata`, `ColumnValues`, `ParquetWriter`, `ParquetSink`, `ThriftCompact(+Writer)`, `Lz4Block`, `ParquetCodecs`, `Geometry`, `GeoParquetMetadata`. `jvmMain`: `MappedFileReader`, gzip, `ParquetWriter(OutputStream, …)`, `OutputStreamSink`. `iosMain`: `MappedDataReader`, gzip (zlib) |
| `history` → `data/history/` | `commonMain`: `Metrics`, `AirMood`, `TimeEstimate`, `Aggregation`, `ChartAxis`, `Columns`, `HistoryModel`, `HistoryWindow`, `FileSummary`, `ParquetIndexer` |
| `publicfeed` → `publicfeed/` | `commonMain`: `Feed`, `ParquetFeedReader`, `H3`, `CellMap`, `CellScale`, `ViewportCover`, `TimeZoneRegions`. `jvmMain`: `UberH3Grid`. `iosMain`: `IosH3Grid` (vendored H3 C through cinterop) |
| `units` → `units/` | `commonMain`: `Units`, `AxisTicks`. `jvmMain` and `iosMain`: `SystemUnits` (locale default) |
| `airquality` → `airquality/` | `commonMain`: `HealthScale` (WHO 2021 guideline, interim targets, palette) |
| `archive` → `data/archive/` | `commonMain`: `ArchiveLayout` (Hive paths, partials, derived days), `ArchiveVerifier` (size, CRC-32, `PAR1`), `ArchiveRecord`, `ArchivePorts` (`ArchiveStore`, `ArchiveIndex`). Android adapts the ports in `app/.../data/archive/`, iOS in wave 3 |
| `sync` → `data/`, `data/ble/`, `data/lan/`, `data/sync/` (packages kept) | `commonMain`: `DeviceSession`, `AqTransport`, `FrameRecorder`, `DeviceContinuityPolicy`, `ManifestState`, `ConnectionIntent`, `SyncProgress`, `TransportSelector`, `LanTokenPolicy`, `LanFailure`, `SyncResumePolicy`, `ReadWindow`, `WindowRetryPolicy`, `SyncPlanner`, `FileProgress`, `FileProgressReporter`, `RateMeter`, `SyncLock` (`expect`). The engine (`SyncEngine`, `FileArchive`) stays in `app` until wave 3 |
| `settings` → `settings/` | `commonMain`: `SettingsRepository` on DataStore preferences, `SettingsKeys`, `AppPrefs`, `DetailLevel`, `ClockSyncMode`. File `datastore/settings.preferences_pb` with Android's keys. `iosMain`: Application Support path and one-time UserDefaults import |

### Shared sync rules in `:core:protocol`

- `ParquetVerify` is the one completion rule. A file is synced only when its size matches, its
  CRC-32 equals `OPENED.crc32`, and it starts and ends with `PAR1`. The size is checked first, so a
  short file is never hashed. Platform adapters measure size, CRC-32 and the head and tail bytes
  and pass them to the common `verify`. The JVM `File` and `InputStream` overloads and
  `sha256Hex` are extensions in `jvmMain`, so callers outside the package import them by name.
- `Crc32` is streaming (`update(bytes, offset, length)`, `value`, `reset`). The JVM uses
  `java.util.zip.CRC32`, iOS uses the table-driven `PortableCrc32`, and `commonTest` proves both
  agree with the IEEE check value and with each other.
- `SafeName.isValid` mirrors the firmware `safe_parquet_name()` rule. It allows only
  `[0-9a-z._=/-]`, rejects `..`, `//` and a leading `/`, needs a name before `.parquet`, and caps the
  name at 399 bytes. Android `data/sync/SyncPlanner.kt` calls it. The Swift archive on iOS still
  has its own copy.

## Rules that keep the modules honest

- **Pure modules receive plain records, never entities.** `ArchivedFile.info()` → `IndexedFileInfo`; `HistoryIndexer` wraps `ParquetIndexer`. Room's KSP also refuses an entity implementing an interface from another module ([lessons](lessons-learned.md#build--tooling)).
- **A new sensor is a registry entry** in `Metrics` (`:core:history`), not code. See [history-pipeline.md](history-pipeline.md#metric-registry).
- **A new analysis is a pure object in `:core:history`** with a test over the real fixtures, fed by `:app` from Room/files. Never a query inside a composable.
- **A new transport is an `AqTransport`** implementation behind `TransportFactory`; the session, engine and archive do not change ([transports.md](transports.md)).
- **Transport work never lives in a composable or Activity**; manager-owned controllers keep session/sync state through rotation and navigation. Opening another device selects another controller without closing existing connections.
- **The one native Android library** is `zstd-jni` (`@aar`), registered from `:app` into `:core:parquet`'s codec SPI (`AppParquetCodecs`). Every `:core` module targets JVM and iOS. iOS inflates GZIP with the system zlib and has no ZSTD codec yet.

## Concurrent collection and ownership

Each controller serializes connection changes and owns its own transfer/reconnect jobs, live readings,
manifest, controls and events. Address-bound ViewModels observe only that controller. The manager
publishes device snapshots for Home and notifications. `AutoSyncRunner` visits up to two devices
concurrently; a shared `CollectionBudget` also caps active interactive and worker transfers together
at two. This is a conservative software policy, **not a measured Android BLE connection limit**.

A worker claims its controller with its WorkManager run ID. An existing session or reconnect for
that device prevents a background claim; a connection to another device does not. Interactive
reconnect, sync and device commands cannot hijack a worker-owned controller; UI configuration
controls treat worker ownership as busy. Once `info` is verified, `DeviceIdentityClaims`
prevents two endpoint aliases from owning the same full hardware ID. Session close cancels and
joins the transfer before closing the pipe and releasing its identity claim. Reconnect, cancellation
and progress remain per device. Worker cancellation closes only that run's sessions.

The firmware still has **one open file per physical device across BLE and LAN**. Separate devices
can transfer together; concurrent transports to the same board cannot independently open files.
The owner contract is [`../aq-parquet/docs/shared/ble-sync-protocol.md`](../../aq-parquet/docs/shared/ble-sync-protocol.md).

`FileArchive.withWriterLease` serializes the entire OPEN/read/verify/commit/index/CLOSE sequence for
one `(station, name)`, with cancellable waiters; different identities transfer independently.
An existing destination must verify against OPENED size/CRC/PAR1 and its recorded SHA-256 before
reuse. A conflict preserves existing bytes and provenance; commit refuses to replace a destination.
Reuse returns `alreadyArchived` so background summaries count only newly collected files. Hive
paths remain verbatim. `LanWifiLock` uses owner-specific, idempotently closed leases: the first
active LAN transfer acquires the radio lock and the last releases it. BLE and queued transfers hold
no Wi-Fi lease. API 31–33 mDNS resolution uses one shared resolver queue across foreground and
worker browses.

These changes are covered by host tests (`AutoSyncRunnerTest`, `DeviceOwnershipTest`,
`ConcurrentArchiveTest`, `LanTransferLeasesTest`). Simultaneous physical-device collection and the
new Android plumbing have **not been verified on hardware**. Historical phone measurements remain
in the [CoreS3 bench record](../../aq-parquet/docs/boards/m5stack-cores3/bench-verified.md).

## Uses → affects (edit here, check there)

| If you change… | It is consumed by… | So re-check… |
| --- | --- | --- |
| `core/protocol/Frames.kt` (opcodes, layouts) | `DeviceSession`, `FakeTransport`, `FrameRecorder`, `DebugScreen` raw box | the firmware contract first (owner), `FrameCodec*Test`, the fake |
| `AqTransport` interface | `BleTransport`, `TcpTransport`, `FakeTransport`, `DeviceSession`, repository | `TcpTransportTest` loopback, `WindowRecoveryTest` |
| `Metrics` registry | `Aggregator`, `HistoryDeriver`, `DailyDerivedBuilder`, History picker, charts, Now card | `AggregationTest`, `HistoryDeriverTest`, Room `metric_bucket` rows (id strings) |
| Room entities | `SyncEngine`, `HistoryIndexer/Deriver/Repository`, ViewModels | a new migration + `app/schemas/<n>.json`, `DERIVE_VERSION` if tier meaning changed |
| `ParquetWriter` output | `DailyDerivedBuilder`, `tools/validate_parquet_samples.py` | PyArrow + DuckDB oracle run |
| `SyncProgress` / `SyncEndCause` | Files screen, `SyncForegroundService`, `SyncWorker`, `SyncResumePolicy` | `SyncResumePolicyTest`, notification text |
| Manager/controller ownership, transfer budget | address-bound ViewModels, runner, worker, foreground service | `DeviceOwnershipTest`, `AutoSyncRunnerTest`, cancellation and per-device progress |
| Archive writer leases / immutable commit | `SyncEngine`, partial resume, archive index and export | `ConcurrentArchiveTest`, `WindowRecoveryTest`; preserve existing files on conflicts |
| LAN transfer leases / shared mDNS resolver | all interactive and worker LAN transfers | `LanTransferLeasesTest`; phone checks on API 31–33 and screen-off LAN |
| `DetailLevel` | every screen's `detailed`/`advancedMode` gate, Debug destination | migration from `advanced_mode` |

## Provenance

Generated from Google's `android/architecture-templates` (`base` branch) with
`customizer.sh earth.walkthru.aqsync Device "AQ Sync"`; the untouched output is commit `34f2d30`
(first commit "Generate from android/architecture-templates base"). Keep the template's structure
(Hilt, Room, Navigation 3, version catalog). Everything after it is diffable against that.
