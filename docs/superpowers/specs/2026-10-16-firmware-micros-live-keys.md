# Firmware MICROS columns, field ids and LIVE keys

Date 2026-10-16. Source of truth is aq-parquet `integration/waveshare-bench` (PRs 20, 21 and 22,
draft), `docs/shared/mobile-migration.md` and `docs/shared/ble-sync-protocol.md` there.

## Firmware

| Board | Firmware | Schema | Dictionary | Digest |
| --- | --- | --- | --- | --- |
| CoreS3 | `idf-cores3-parquet-v6.12` | `cores3-telemetry-v5` | `cores3-telemetry-v4` | `22291d3aab63365404394f30c1549fb18bde0699df9b2de863de70bb3f4edf65` |
| Waveshare SIM7670G | `idf-waveshare-parquet-v2.6` | `waveshare-sim7670g-telemetry-v4` | same | `0a024b85c632f621c0e1223f1ecafe3a9c4524a1ec3c0d03d910d191aaee2f82` |

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
   missing keys are null. LIVE `utc` stays nanoseconds.
6. Watchdog. `LOG_TAIL` watchdog lines are informational. A storage stall reset ends the link,
   and the existing `RetryPolicy` and a fresh LIST resume the run. No code change.
7. Old files. Both layouts stay readable. Cached old files are never deleted from the phone. A
   file the device no longer lists is not data loss, and the app never uploads to the device.

## Tests

- `ParquetWriterTest`, field id and unit round trip.
- `UtcColumnsTest`, MICROS and NANOS copies of one file index and summarise the same, the unit
  comes from the logical type, unannotated old names read as ns, bad layouts are refused,
  overflow is null, range rounding, and a mixed old and new day written as MICROS with ids.
- `LiveProfileTest`, `imu_t`, missing and unknown keys, newer schema, older dictionaries.
- `DeviceCapabilitiesTest`, `TodayCardsTest`, `DailyParquetBuilderTest`, `HistoryDeriverTest`.

## Not done here

No phone run against v6.12 or v2.6. No new fixtures from real v6.12 or v2.6 files yet.
