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
- Add sensors through `history/.../Metrics.kt` (`MetricSpec`), with column,
  unit, validity gate and display bands. A missing column produces no value.
- Keep protocol and sensor-domain analysis pure and reusable by Android, iOS and
  host tools. Android files/DAO integration belongs in `:app` adapters; Apple OS
  integration belongs in `ios/` adapters.
- `protocol` and the sensor-domain part of `history` are Kotlin Multiplatform.
  `src/commonMain` compiles for JVM and iOS; the existing `src/main` tree is
  explicitly mapped to `jvmMain` during migration. Keep Java APIs there.
  `parquet` and file-backed history remain JVM until their adapters are ported.
- Shared tests live in `commonTest` and run on JVM through `verify`; run
  `verifyIos` on macOS for simulator tests. See `docs/ios-migration.md`.
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
