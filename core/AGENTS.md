# Shared core modules

`protocol/` mirrors the sibling firmware repo's
`docs/shared/ble-sync-protocol.md`; `parquet/`
implements the reader/writer and codec SPI; `history/` owns metrics, indexing,
aggregation and presentation-ready analysis. See `docs/README.md` in the repo
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
  lands (W11), families, capabilities, properties and scales move there. Read
  `docs/devices-and-discovery.md` first. New shared types use generic names
  (`Reading`, `MetricCard`), never `Air*`.
- Public discover data carries H3 cells, never coordinates, and the phone never
  computes a location for it. The feed publisher owns the H3 math.
- Keep protocol and sensor-domain analysis pure and reusable by Android, iOS and
  host tools. Android files/DAO integration belongs in `:app` adapters; Apple OS
  integration belongs in `ios/` adapters.
- `protocol`, `parquet` and `history` are Kotlin Multiplatform with the standard layout.
  `src/commonMain` compiles for JVM and iOS, and `src/jvmMain` / `src/iosMain` keep the platform
  edges (files, streams, SHA-256, `FileChannel` and `NSData` mapping, gzip). Calendar math uses
  kotlinx-datetime (`TimeZone`, `LocalDate`), never `java.time`, in common code. Parquet bytes come
  through `ByteReader` / `SuspendByteReader` (docs/parquet.md), so a new source (an HTTP range
  reader, a cache) implements that interface instead of touching the decoder.
- Module build files apply a convention plugin from `../build-logic/` (`opensensor.kmp.library`,
  `opensensor.jvm.library`, `opensensor.kmp.ios-framework`). Change targets, bytecode level or test
  wiring there, once, not in a module. See `docs/architecture.md`.
- Sync rules that both phones must share live in `protocol/commonMain`. `ParquetVerify` (size,
  CRC-32, `PAR1` head and tail) decides when a file is synced, `Crc32` is the streaming checksum,
  and `SafeName` is the firmware name rule (charset, no `..` or `//`, 399-byte cap). Platform code
  measures and calls them, it does not re-implement them.
- Shared tests live in `commonTest` with `kotlin.test` and run on JVM through `verify` and on the
  iOS simulator through `verifyIos` (macOS). Keep a test in `jvmTest` only when it needs a JVM API.
  The Parquet fixtures reach other modules' JVM tests as `testFixtures(project(":core:parquet"))`
  (a hand-declared variant, see `core/parquet/build.gradle.kts`). Inside `:core:parquet`, common
  tests read them with `fixtureBytes(path)` on both platforms.
  Test names in `commonTest` cannot contain commas or other characters Kotlin/Native rejects.
  See `docs/ios-migration.md`.
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
