# Shared core modules

All Kotlin code lives under `space.opensensor`. No Android, Hilt, `Context` or `android.util.Log`
in any `:core:*` module. Room appears only in `:core:database`, the shared Room KMP database for
both phones. No core module names air quality. The WHO 2021 scales, the EPA correction, the moods
and the air metrics live in `:family:air` (`family/air/`), and `CoreStaysAirFreeTest` fails the
gate when a `:core` source names one. Open the doc of the module you change.

| Module | Owns | Doc |
| --- | --- | --- |
| `model/` | What a thing measures as data. `BoardTemplate`, `TemplateIndex`, `Datastream`, `AppProfile`, `Thing.join`, `H3Cell`, the bundled templates and profiles under `bundled/` | [devices and discovery](../docs/devices-and-discovery.md) |
| `protocol/` | The mirror of the firmware `docs/shared/ble-sync-protocol.md`. Frames, opcodes, `DeviceGatt`, `LanProtocol`, `LiveReading`, `ParquetVerify`, `SafeName` | [protocol quickref](../docs/protocol-quickref.md) |
| `parquet/` | The Parquet reader and writer and the codec SPI. `ByteReader`, `RangeSource`, `ParquetFile`, `ParquetReader` | [parquet](../docs/parquet.md) |
| `net/` | HTTP range reads (`HttpRangeSource`) and the disk block cache (`CachedRangeSource`, `BlockCache`) | [parquet](../docs/parquet.md) |
| `storage/` | `StorageClass`, `StorageRoots`, `AtomicFiles`, `StorageBudget`, `RetentionPolicy`, `QuotaEnforcer`, `MemoryPressure` | [data model](../docs/data-model.md) |
| `archive/` | The archive layout, the verifier, records and the `ArchiveStore` and `ArchiveIndex` ports | [data model](../docs/data-model.md) |
| `sync/` | `LinkSession`, `DeviceSession`, `SyncEngine`, `ArchiveCollector`, `CollectionController` in `collection/`, `DeviceCoordinator`, `StorageJanitor`, `SmartSyncPolicy` | [sync engine](../docs/sync-engine.md), [transports](../docs/transports.md) |
| `history/` | Metrics, indexing, tiers, daily Parquet, `HistoryOrchestrator`, `DeviceCapabilities`, `BoardDescriptors`, `TemplateHrefs`, `LegacyFileCleanup` | [history pipeline](../docs/history-pipeline.md) |
| `database/` | The Room KMP database (`AppDatabase`), its DAOs, migrations and exported schemas | [data model](../docs/data-model.md) |
| `publicfeed/` | The Discover feed port, H3 (`H3Grid`) and the viewport cover | [devices and discovery](../docs/devices-and-discovery.md) |
| `units/` | Display units | [screens](../docs/screens.md) |
| `settings/` | Shared DataStore preferences, `SettingsKeys`, units and detail level, with the iOS Application Support path | [data model](../docs/data-model.md) |
| `stac/` | The STAC File Item (`FileItems.build`) and Thing Collection (`ThingCollections.build`) of a synced file, pure functions with no upload, network or Room, and `CanonicalJson` | [devices and discovery](../docs/devices-and-discovery.md) |
| `thing/` | The `ThingKind` contract, `OpenSensorBoardKind`, the `ThingKinds` registry, `PublishGate` and `PlatformNeedsTable`. It never uploads | [devices and discovery](../docs/devices-and-discovery.md), [store compliance](../docs/store-compliance.md) |
| `thing/testkit/` | `ThingKindContract`, the checks every kind runs from its own tests | [devices and discovery](../docs/devices-and-discovery.md) |

Rules that hold across modules.

- Every file writer goes through `AtomicFiles`. `StorageJanitor` sweeps Scratch at start, enforces
  the derived quota and never deletes Durable data. Only the user's old format cleanup in Settings
  (`LegacyFileCleanup`) removes archive files, phone only, and leaves a `PrunedFiles` record.
- One `DeviceCoordinator` per archive owns thing admission, writer leases, thing gates and
  tombstones. Every archive index write takes its thing gate, lease first then gate, readers never.
- `ParquetFile` and `ByteReader` are `AutoCloseable`, open them with `use`.
- Each app builds the `ThingKinds` registry as a plain list, Android in `data/di/ThingModule` and
  iOS in `ThingKindsIos.kt`, with no self registration, and hands `ThingKinds.metricProviders` to
  `AirFamily.install`.
- An Item or Collection the phone builds must equal the board's as JSON values
  (`CanonicalJson.difference`). `PublishGate` is the only caller of `FileItems.build`, and only a
  `Cleared` check carries a `Publication`.
- `ManifestPermissionsTest` (app) and `IosBundleGateTest` (ios/shared, simulator) fail on any
  manifest or bundle entry the enabled kinds do not declare in `PlatformNeedsTable`, and on any
  declared entry that is missing.
- Change firmware's shared contract first for wire or measurement schema edits;
  preserve old archived bytes. Tests here should exercise real fixtures and
  PyArrow/DuckDB oracles where the format is involved.
- The core is sensor agnostic. Air quality is one device family, and new
  families arrive as data (a measurement dictionary and a family entry), never as
  a code branch or a schema string check in a screen. `:core:model` holds
  the data model (#183) and the bundled board templates and app profiles under
  `core/model/bundled/` (#184). `bundled/templates/` is opensensor-space-firmware `docs/shared/templates` copied
  byte for byte (marked `-text`, excluded from the whitespace hooks), and `BundledTemplateBytesTest`
  checks each file's SHA-256 against its `index.json` desc and its pinned hash, `index.json`
  included, so never reformat it and update the pins with each copy. A re-copy is
  `rsync -a --delete ../opensensor-space-firmware/docs/shared/templates/ core/model/bundled/templates/`,
  and sets the firmware checkout `ref` in `verify.yml` to the commit copied, where
  `FirmwareTemplatesTest` compares the copy byte for byte (#261). LIVE (`LiveReading`, #185), the batch estimate and
  History read them. History builds each `MetricSpec` from a template datastream
  and the profile (`Metrics.of`), plus the derived metrics of each
  `DerivedMetricProvider` (`AirFamily` for corrected PM2.5), and reads each file by the template of its
  `opensensor` footer JSON (`OpenSensorFooter`, `FileTemplates`), by
  `TemplateIndex.resolve`, the same rule LIVE uses (#259).
  LIVE shows only for a dict with a reviewed profile. The old unprefixed footer
  keys are not read. A missing column produces no value. Read `docs/devices-and-discovery.md` first. New shared types use generic
  names (for example `Reading` or `GenericMetricHero`), never `Air*`.
- Public discover data carries H3 cells, never coordinates. The publisher owns aggregation and
  the privacy rules. The phone uses H3 (`publicfeed`, `H3Grid`) for geometry, viewport cover, descendant
  ranges and explicit private device provisioning. Provisioning converts a
  one-shot foreground fix to a cell before passing it to UI/protocol state;
  neither exact coordinates nor uploading belong to that flow.
  `H3Cell` in `:core:model` is the pure Kotlin cell check (mode 1, reserved bits clear, base cell under 122,
  digits 0 to 6 down to the resolution and 7 below, no deleted K axis under a pentagon) and its
  hex parser. `H3Index.isCell` delegates to it, so `:core:protocol` and `:core:sync` check cells
  without `publicfeed`. `LocationConfig.hasCell()` (in `DeviceLocation.kt`) uses it, and `H3Grid.isValidCell` defaults to it, so a fake grid checks
  cells correctly. `H3Grid.gridDisk(cell, k)` lists a cell and its k rings on the platform binding. The JVM and iOS bindings keep their library call, and `H3IsCellTest` holds
  `isCell` to the H3 library.
- Keep protocol and sensor-domain analysis pure and reusable by Android, iOS and
  host tools. Android files/DAO integration belongs in `:app` adapters; Apple OS
  integration belongs in `ios/` adapters.
- Every `:core` module is Kotlin Multiplatform with the standard layout.
  `src/commonMain` compiles for JVM and iOS, except in `:core:database`, which targets Android and
  iOS. `src/jvmMain` and `src/iosMain` keep the platform
  edges (files, streams, SHA-256, positional `FileChannel` and `pread` reads, gzip, H3). Calendar math uses
  kotlinx-datetime (`TimeZone`, `LocalDate`), never `java.time`, in common code. Parquet bytes come
  through `ByteReader` (blocking, `ParquetFile`) or `RangeSource` (suspending, `ParquetReader`)
  (docs/parquet.md), so a new source (a cache, another transport) implements `RangeSource`
  instead of touching the decoder. The decoder reads `ByteView`s in place and never copies them.
- Module build files apply a convention plugin from `../build-logic/` (`opensensor.kmp.library`,
  `opensensor.kmp.android-library` for `:core:database`, `opensensor.kmp.compose-feature`,
  `opensensor.kmp.ios-framework`, `opensensor.jvm.library`).
  Change targets, bytecode level, test wiring or gate membership there, once, not in a module.
  See `../build-logic/AGENTS.md` and `docs/architecture.md`.
- Sync rules that both phones must share live in `protocol/commonMain`. `ParquetVerify` (size,
  CRC-32, `PAR1` head and tail) decides when a file is synced, `Crc32` is the streaming checksum,
  and `SafeName` is the firmware name rule (charset, no `..` or `//`, 399-byte cap) plus the phone's
  241-byte segment cap, so every sibling of a segment fits a 255-byte file name. Platform code
  measures and calls them, it does not re-implement them.
- The clock check and `lan.on` run at most once per link through `LinkSession.claimOnce(key)`,
  with the keys `CLOCK_CHECK` ("clock") and `LAN_ENABLE` ("lan.on") in `DeviceSession`. The token
  fetch takes no claim. Android gates it with `LanTokenPolicy.shouldFetch` and iOS with
  `tokenWanted`. `synchronizeClockOnce` and `enableLanOnce` keep the claim once the sensor decided,
  which includes a STATUS timeout and an ERROR reply other than BUSY. A cancelled try, a failure
  before STATUS answered, a `SET_TIME` or `SET_CONFIG` with no reply (a timeout or a transport
  error), or `DeviceBusyException` calls `release(key)`, so the next run or screen on that link
  tries again. `SmartSyncPolicy.decide(status, now, uncertainty, proto)` is the
  one clock rule, where the link uncertainty is half the STATUS round trip, applied in this order.
  - No `SET_TIME` when the phone clock is before `PHONE_CLOCK_FLOOR_MILLIS`, or when STATUS did not
    answer on proto 3 or newer.
  - Set it when the anchor is missing, RTC restored or unknown.
  - Keep a network anchor (`clk=3`) whose drift was not measured.
  - Past a minute of drift (plus 50 ppm aged drift and link uncertainty) keep a network anchor,
    since a hand-set phone is the likelier fault, and keep any anchor under 6 h old, so two phones
    never flip-flop it. Set any other.
  - Past 2 s plus link uncertainty keep an anchor under 6 h old and set any other. This also sets
    a network anchor that reports no `clockAgeSeconds`, so such an anchor is replaced at a drift
    over 2 s plus uncertainty and up to 60 s plus uncertainty.
  - Otherwise set only an anchor 6 h old or more.
- History derives each grouped day once with `DayPass`, producing tiers and daily Parquet
  together. Android stamps each derived day with `HistoryDeriver.DERIVE_VERSION`, tiers carry
  `TierCodec.VERSION`, and Android plans from projected thing archive identities.
  iOS persists rebuildable tiers and enforces the derived quota after building.
  Preserve these paths and immutable raw archives. See [history pipeline](../docs/history-pipeline.md)
  and the [host benchmark](../docs/perf/history-2026-10-11.md), which does not qualify phone latency.
- Shared tests live in `commonTest` with `kotlin.test` and run on JVM through `verify` and on the
  iOS simulator through `verifyIos` (macOS, and part of `verify` on a Mac). The convention plugin
  adds every module to both gates. Keep a test in `jvmTest` only when it needs a JVM API.
  The Parquet fixtures reach other modules' JVM tests as `testFixtures(project(":core:parquet"))`
  (a hand-declared variant, see `core/parquet/build.gradle.kts`). Inside `:core:parquet`, common
  tests read them with `fixtureBytes(path)` on both platforms.
  Test names in `commonTest` cannot contain commas or other characters Kotlin/Native rejects.
  See `docs/ios.md`.
- Common code must compile for every declared target and cannot import Java, Android,
  Room, UIKit, or Apple framework APIs. Put platform behavior behind narrow interfaces
  constructed at app entry points; use `expect`/`actual` only for small seams where it
  simplifies the boundary. Prefer existing source-set conventions over restructuring
  modules as part of an unrelated feature.
- Keep the Kotlin API exported to Swift small and documented with KDoc. The current
  iOS integration uses the generated framework and Objective-C interop. Do not switch
  to Swift export or a new package integration without checking the current Kotlin
  documentation and proving the change in this project's Xcode build.
- Consult the official [KMP source-set guide](https://kotlinlang.org/docs/multiplatform/multiplatform-discover-project.html),
  [project configuration guide](https://kotlinlang.org/docs/multiplatform/multiplatform-project-configuration.html),
  and [Swift interop guide](https://kotlinlang.org/docs/native-objc-interop.html) when
  changing platform boundaries. Verify recommendations against the pinned toolchain.
