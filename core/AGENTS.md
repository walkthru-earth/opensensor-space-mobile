# Shared core modules

`protocol/` mirrors the sibling firmware repo's
`docs/shared/ble-sync-protocol.md`; `parquet/`
implements the reader/writer and codec SPI; `history/` owns metrics, indexing,
aggregation and presentation-ready analysis; `publicfeed/` owns the Discover feed
port, H3 and the viewport cover; `units/` owns display units; `airquality/` owns
the WHO 2021 scales and palette. `net/` owns HTTP range reads (`HttpRangeSource`, OkHttp
on Android and the JVM, `NSURLSession` on iOS) and the disk block cache (`CachedRangeSource`,
`BlockCache`, #94). `archive/` owns the archive layout, verifier,
records and the `ArchiveStore` and `ArchiveIndex` ports. `storage/` owns `StorageClass`,
`StorageRoots`, `AtomicFiles` (no-replace commit, F_FULLFSYNC on iOS, directory sync),
`StorageBudget`, `StorageTree`, `RetentionPolicy` with the derived `QuotaEnforcer`, and
`MemoryPressure` with `ByteBudgetLru` for memory caches that trim on the OS signal (#61). Every file
writer goes through `AtomicFiles`, and the `StorageJanitor` (in `sync/`, it needs the archive ports)
sweeps Scratch at start, enforces the derived quota and never deletes Durable data. Only the user's old format cleanup in Settings (`LegacyFileCleanup`, `:core:history`) removes archive files, phone only, and leaves a `PrunedFiles` record so sync skips them until Download again. Every archive index write takes the station's `ArchiveMutationGate` (`:core:sync`), lease first then gate, readers never. `ParquetFile` and
`ByteReader` are `AutoCloseable`, open them with `use`. `sync/` holds the link session, the sync
engine and, in `collection/`, the per-device `CollectionController` ([sync guide](../docs/sync-engine.md)). Android adopts the controller
through `DeviceFacade`; iOS uses `IosLink` and `SensorSync` for link and engine adoption,
and `SensorCollections` for one controller per device, which every native sync entry point
runs through ([#81](https://github.com/walkthru-earth/opensensor-space-mobile/issues/81)).
`settings/` owns shared DataStore preferences, keys, units and detail level, with the
iOS Application Support path and one-time UserDefaults migration. See `docs/README.md` in the repo
root for the relevant concern. No Android, Hilt, Room, `Context` or
`android.util.Log` in any `:core:*` module.

- Change firmware's shared contract first for wire or measurement schema edits;
  preserve old archived bytes. Tests here should exercise real fixtures and
  PyArrow/DuckDB oracles where the format is involved.
- The core is sensor agnostic. Air quality is one device family, and new
  families arrive as data (a measurement dictionary and a family entry), never as
  a code branch or a schema string check in a screen. Today add metrics through
  `history/.../Metrics.kt` (`MetricSpec`), with column, unit, validity gate and
  an optional scale. A missing column produces no value. When `:core:model`
  lands (planned), families, capabilities, properties and scales move
  there. Read `docs/devices-and-discovery.md` first. New shared types use generic
  names (for example `Reading`, or a future `MetricCard`), never `Air*`.
- Public discover data carries H3 cells, never coordinates. The publisher owns aggregation and
  the privacy rules. The phone uses H3 (`publicfeed`, `H3Grid`) for geometry, viewport cover, descendant
  ranges and explicit private device provisioning. Provisioning converts a
  one-shot foreground fix to a cell before passing it to UI/protocol state;
  neither exact coordinates nor uploading belong to that flow.
- Keep protocol and sensor-domain analysis pure and reusable by Android, iOS and
  host tools. Android files/DAO integration belongs in `:app` adapters; Apple OS
  integration belongs in `ios/` adapters.
- Every `:core` module is Kotlin Multiplatform with the standard layout.
  `src/commonMain` compiles for JVM and iOS, and `src/jvmMain` / `src/iosMain` keep the platform
  edges (files, streams, SHA-256, positional `FileChannel` and `pread` reads, gzip, H3). Calendar math uses
  kotlinx-datetime (`TimeZone`, `LocalDate`), never `java.time`, in common code. Parquet bytes come
  through `ByteReader` (blocking, `ParquetFile`) or `RangeSource` (suspending, `ParquetReader`)
  (docs/parquet.md), so a new source (a cache, another transport) implements `RangeSource`
  instead of touching the decoder. The decoder reads `ByteView`s in place and never copies them.
- Module build files apply a convention plugin from `../build-logic/` (`opensensor.kmp.library`,
  `opensensor.kmp.compose-feature`, `opensensor.kmp.ios-framework`, `opensensor.jvm.library`).
  Change targets, bytecode level, test wiring or gate membership there, once, not in a module.
  See `docs/architecture.md`.
- Sync rules that both phones must share live in `protocol/commonMain`. `ParquetVerify` (size,
  CRC-32, `PAR1` head and tail) decides when a file is synced, `Crc32` is the streaming checksum,
  and `SafeName` is the firmware name rule (charset, no `..` or `//`, 399-byte cap) plus the phone's
  241-byte segment cap, so `<segment>.verified.json` fits a 255-byte file name. Platform code
  measures and calls them, it does not re-implement them.
- History derives each grouped day once with `DayPass`, producing tiers and daily Parquet
  together. Android uses derive version 5 and plans from projected station archive identities.
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
