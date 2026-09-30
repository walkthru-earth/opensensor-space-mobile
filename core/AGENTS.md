# Pure JVM modules

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
