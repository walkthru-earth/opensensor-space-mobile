# Firmware MICROS columns, field ids and LIVE keys

Date 2026-10-16. Source of truth is aq-parquet `integration/waveshare-bench` (PRs 20, 21 and 22,
draft), `docs/shared/mobile-migration.md` and `docs/shared/ble-sync-protocol.md` there.

## Firmware

| Board | Firmware | Schema | Dictionary | Digest |
| --- | --- | --- | --- | --- |
| CoreS3 | `idf-cores3-parquet-v6.12` | `cores3-telemetry-v5` | `cores3-telemetry-v4` | `22291d3aab63365404394f30c1549fb18bde0699df9b2de863de70bb3f4edf65` |
| CoreS3 | `idf-cores3-parquet-v6.13` (aq-parquet #31) | `cores3-telemetry-v5` | same | `e5060fb7fedbcabf665b1c8a159a77f5fe9283ac34bd2d89f1bc98b0f027d38b` |
| Waveshare SIM7670G | `idf-waveshare-parquet-v2.6` | `waveshare-sim7670g-telemetry-v4` | same | `0a024b85c632f621c0e1223f1ecafe3a9c4524a1ec3c0d03d910d191aaee2f82` |
| Waveshare SIM7670G | `idf-waveshare-parquet-v2.7` (aq-parquet #24) | `waveshare-sim7670g-telemetry-v5` | same | `67688d0fd2e86053c1c66b961a1446ad3c225ee71823e0fadb771b645c7183ff` |

## Plan

1. Parquet reader and writer (`:core:parquet`). `LeafColumn` exposes `timestampUtcUnit` and
   `fieldId`. `ParquetWriter.Column` writes either unit and an optional `field_id`.
2. UTC unit (`UtcColumns` in `:core:history`). Find `event_time_utc_us`, then `event_time_utc_ns`.
   Scale by the logical type unit. An unannotated INT64 under an old `*_ns` name is nanoseconds.
   Anything else under a UTC name is refused. Overflow past 2262 reads as null. Indexer, file
   summaries, tiers and raw chart reads go through it, and the app timeline stays in nanoseconds.
3. Derived days (`DailyParquetBuilder`, `derived-v3`). Canonical names, MICROS values, device field
   ids copied by name (conflicting ids dropped), estimate in `event_time_utc_estimated_us`.
   `HistorySeriesReader.readDerived` scales by the stored unit. `DERIVE_VERSION` 6 rebuilds the
   cache, and Android refuses a manifest from another builder.
4. Files inspector. Shows the stored unit (ms, us, ns) and scales row group statistics.
5. LIVE (`:core:protocol`). `LiveProfiles.resolve` by dictionary digest only. `LiveSample.imuT`
   for CoreS3 `imu_t`, shown as board temperature. Waveshare `t` stays air temperature. Profiles
   list the board's keys, and Today shows only those particle bins. Unknown keys are ignored and
   missing keys are null. LIVE `utc` stays nanoseconds. Older dictionaries keep a profile, with
   CoreS3 v6.11 and earlier reading board temperature from `t`. The current CoreS3 profile falls
   back to `t` when `imu_t` is missing. Waveshare v2.7 (`67688d0f…`, first build `d0d01294…`) adds
   `bstate`, the battery health code, which `LiveSample.bstate` parses. Showing it is #90.
6. Watchdog. `LOG_TAIL` watchdog lines are informational. A storage stall reset ends the link,
   and the existing `RetryPolicy` and a fresh LIST resume the run. No code change.
7. Index reopen. Room v11 and iOS receipt layout 2 reopen indexed rows without UTC rows or an
   error, so an older build's index of a MICROS file is read again. A derive pass that runs before
   the back-fill keeps any day a waiting row may fill (`StationDays.held` in `:core:history`, used
   by `HistoryDeriver`, `IosDailyDerivedStore` and `ArchiveHistoryTiers`). `ArchiveRecord.isIndexed`
   is the one indexed test.
8. Old files. Both layouts stay readable. Sync never deletes a cached old file from the phone. The
   user may remove old format files in Settings (#88), phone only, and sync then skips them until
   Download again. A file the device no longer lists is not data loss, and the app never uploads
   to the device.
9. Iceberg v3. Owner update, aq-parquet commit c1f07ed. Tables are created as format v3, device
   MICROS columns are `timestamptz` and `field_id` is the Iceberg field id. No `timestamptz_ns`.
   Row lineage is left to the committing library (iceberg-go 0.7.0+ `AddFiles` or iceberg-rust
   0.9.0+ with `DataFile` entries from footer metrics, not PyIceberg 0.12). Pre v6.12 and v2.6
   files stay out of the table or are rewritten on a host. The app has no Iceberg commit code, so
   this is recorded in `docs/parquet.md` and no catalog client is built here.

## Tests

- `ParquetWriterTest`, field id and unit round trip.
- `UtcColumnsTest`, MICROS and NANOS copies of one file index and summarise the same, the unit
  comes from the logical type, unannotated old names read as ns, bad layouts are refused,
  overflow is null, range rounding, and a mixed old and new day written as MICROS with ids.
- `LiveProfileTest`, `imu_t`, the CoreS3 fallback to `t`, `bstate`, missing and unknown keys, newer
  schema, older dictionaries.
- `StationDaysTest`, which days a waiting row holds.
- `HistoryDeriverTest`, a day reopened by the v11 migration survives a derive pass before its
  back-fill, on in-memory Room with the real migration SQL.
- `DeviceCapabilitiesTest`, `TodayCardsTest`, `DailyParquetBuilderTest`.

## Not done here

No phone run against v6.12 or v2.6. No new fixtures from real v6.12 or v2.6 files yet.
