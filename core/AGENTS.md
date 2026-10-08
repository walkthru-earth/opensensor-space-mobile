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
- Keep analysis pure and reusable by Android, a future iOS port or host tools.
  Android files/DAO integration belongs in `:app` adapters.
- `protocol` and the sensor-domain part of `history` are Kotlin Multiplatform.
  `src/commonMain` compiles for JVM and iOS; the existing `src/main` tree is
  explicitly mapped to `jvmMain` during migration. Keep Java APIs there.
  `parquet` and file-backed history remain JVM until their adapters are ported.
- Shared tests live in `commonTest` and run on JVM through `verify`; run
  `verifyIos` on macOS for simulator tests. See `docs/ios-migration.md`.
