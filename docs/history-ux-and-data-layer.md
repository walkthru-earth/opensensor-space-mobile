# History, friendly UX, many loggers, location — options and a recommendation

Proposal, 2026-09-17. Status: **for decision, nothing implemented.** Companion to `README.md`
(status, decisions) and `AGENTS.md` (rules). Where this proposal contradicts a rule in `AGENTS.md`
it says so explicitly and proposes the amended rule; nothing here overrides a rule until the rule is
changed.

Scope asked for: (1) many loggers at once (owner, maintainer, data collector), (2) MapLibre + a
location the phone gives the station "like the UTC idea", (3) a UI for people who do not know what
an air-quality number means (friendly characters, capybaras), with hidden detailed screens for
advanced users, (4) history graphs from the archived Parquet, including whether to merge files per
day, how to lay out storage, and how to read Parquet efficiently on the phone, (5) a native Swift
iOS app later with the same features.

## 0. What the archive actually looks like (measured, not assumed)

Measured on `../m5stack-aq-parquet/exports/sd-20260916T173205Z` (the full SD export of `AQ-6b40`,
764 files) with PyArrow via `pixi run python` in the firmware repo:

| Fact | Value | Consequence |
| --- | --- | --- |
| Files under `station=…` | 756 (16 dated, **740 `unsynced/boot=…`**) | Most rows of this device have **no UTC**; a timeline needs the back-fill in §4.6 or these rows are invisible |
| Row groups per file | exactly 1 | Nothing to prune inside a device file; the Hive path is the only index |
| Encodings / pages | PLAIN + RLE definition levels, Data Page V1, no dictionaries | A tiny decoder covers 100 % of device files |
| Column statistics | `null_count` only, **no min/max** | Time-range pruning by footer is impossible on device files; only the path (`day=`, `HHMM`) and the phone's own index can prune |
| Columns | 73 (`cores3-telemetry-v1`) and 77 (`v2`) in the same archive | Any merge must union schemas (v2 ⊇ v1, appended columns nullable) |
| `event_time_utc_ns` | INT64, **no TIMESTAMP logical type**; null when unsynced | Readers do not know it is a time; our derived layer should annotate it |
| Rows per file / footer | avg 88 rows, **avg 7,115 B footer** (17 % of all bytes) | 96 files/day ≈ 0.7 MB/day of footers alone |
| Bytes per row on card | 467 (734 files UNCOMPRESSED, 10 LZ4) | 4 MB/day, 1.5 GB/year per station as raw files |
| Same rows re-written as one file | LZ4_RAW 82 B/row · ZSTD 51 B/row (one row group); 115 / 62 B/row with 900-row row groups | A merged day is **0.5–1 MB** instead of 4 MB; row groups cost 20–40 % |
| Key metadata per file | `station_id`, `boot_id`, `device_id`, `schema_version`, `firmware`, `compression`, v2 adds `dictionary_*`, `acquisition_config*`, `deployment_id`=`unknown`, `calibration_id`, `time_semantics`, `rotation_interval_s` | `deployment_id` is the natural hook for location (§3) |

Sizes below use 8,640 rows/day (one row every 10 s).

## 1. Reading Parquet on the phone — the engine decision

The user's pointers: [Kotlin DataFrame / Parquet](https://kotlin.github.io/dataframe/parquet.html),
[Kotlin DataFrame / Arrow](https://kotlin.github.io/dataframe/apachearrow.html) and the
`Kotlin/dataframe` docs tree.

**Finding that decides the question:** the Parquet page states *"Apache Arrow is not supported on
Android, so reading Parquet files on Android is not available"*, and the repo's own
`docs/StardustDocs/topics/setup/SetupAndroid.md` says to add every IO module *"except for
`dataframe-arrow`. Apache Arrow is not well supported on Android."* `readParquet` loads
`arrow_dataset_jni` (C++ libarrow) which has no Android build. Even on the JVM the reader has no
predicate pushdown, reads every column, and Kotlin DataFrame stores columns as boxed
`List<Any?>` (open issue #712 "ColumnDataHolder / primitive arrays") — the opposite of zero-copy.
Also relevant: issue #1445 "Do not support KSP2" (this app uses KSP 2.3.12 for Room/Hilt).

So Kotlin DataFrame is **not** the Parquet reader and **not** the storage of the raw series. It can
still be a fine *analysis* API on small aggregate tables (thousands of rows), if wanted.

| Option | Reads device files | Writes daily files | APK cost | iOS parity | Risk | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| **R1 — own subset reader/writer in Kotlin** (Thrift compact footer, one/many row groups, PLAIN, DataPageV1, RLE/bit-packed def levels with max level 1, INT32/INT64/FLOAT, UNCOMPRESSED + LZ4_RAW block codec) | yes, 100 % of the firmware's output | yes (adds statistics, logical types, several row groups) | ~0 (pure Kotlin, ~1.5 k lines; LZ4 block via `lz4-java` `safeInstance()` or ~250 hand-written lines) | a Swift port of the same spec, same fixtures | we own a format implementation — mitigated by the firmware repo's existing PyArrow/DuckDB conformance harness, and `ParquetVerify.kt` already exists | **Recommended now** |
| R2 — DuckDB | yes, full Parquet, Hive partitions, `time_bucket`, row-group pruning, `COPY TO` Parquet | yes | 30–60 MB native `.so`, **self-built** ("experimental support for Android… use the latest `main` branch", [duckdb.org](https://duckdb.org/docs/current/dev/building/android.html)); no Maven artifact | `duckdb-swift` is official | build/maintenance burden in a template-generated repo; pruning gains are nil on device files (no statistics) | Defer; the daily files of §2 are exactly what DuckDB would consume later (laptop, hub, or a later app version) |
| R3 — Rust `parquet`/`arrow` crate behind UniFFI | yes | yes | ~5–8 MB per ABI, Rust + NDK toolchain, XCFramework for iOS | one core for both apps | toolchain in a repo that today needs only Android Studio | Revisit if the derived layer grows (compaction, Iceberg manifests, DuckDB-free SQL) |
| R4 — parquet-java (`parquet-mr`) / parquet-floor | yes | yes | Hadoop-flavoured dependency tree, Android compatibility unproven | none | heavy, alien to Android | No |
| R5 — Apache Arrow Java (`arrow-vector`) for IPC | Arrow IPC only, not Parquet | IPC only | `arrow-java` issue #801 (allocation manager on Android) still open | Arrow Swift is immature | unsupported platform | No |

"Zero-copy" on Android therefore means, concretely (R1):

1. `FileChannel.map` the file (`MappedByteBuffer`, little-endian).
2. Parse the footer once; cache the column-chunk offsets per file in Room (`archived_file` gains
   `rows`, `schema_version`, `footer_json` or a small blob) so a chart never re-opens files it does
   not need.
3. **Column projection**: a PM2.5 chart needs `event_time_utc_ns`, `pm25_atmospheric_ug_m3`,
   `pms_status` — 3 of 77 column chunks, ~4 % of the bytes.
4. Decode PLAIN pages straight into `LongArray`/`FloatArray`/`IntArray` (one pass, no boxing, no
   per-row objects). UNCOMPRESSED pages are literally `buffer.asFloatBuffer()` slices; LZ4_RAW pages
   are decompressed once into a reusable scratch buffer.
5. Hand the primitive arrays to the chart, which bins them per pixel column (min/max/mean, LTTB for
   the line). A day of PM2.5 is 8,640 floats = 35 KB; a year at 10 s is 3.15 M floats = 12.6 MB,
   which is why anything beyond ~3 days reads the aggregate tiers of §2, never the raw rows.

Validation: the JVM unit tests write fixtures with the Kotlin writer and the firmware repo's
`pixi run python` validates them with PyArrow **and** DuckDB (same harness the firmware uses);
the reader is tested against the 764 real files (a `git submodule`-free copy of a handful goes to
`app/src/test/resources`, the rest runs as an opt-in local test pointed at `../m5stack-aq-parquet/exports`).

## 2. Merge into one file per day? Storage layout

**Yes, as a derived layer; never by touching the raw archive.** `AGENTS.md` keeps
`archive/<station>/…` verbatim (mirror of the SD card and of `tools/export_parquet.py`), and the
sync identity is `(station, name, size, crc32)` per *device* file — the device produces a file every
15 min whatever we do, so merging changes nothing about device sync. What it simplifies is
everything downstream: charts, export, the later opt-in cloud backup (one object per station-day
instead of 96), a hub, DuckDB on a laptop, and the iOS app (same file, same spec).

```
filesDir/
  archive/<station>/station=<station>/year=…/…/data_HHMM_<boot>_<a>-<b>-<n>.parquet   raw, verbatim (unchanged)
  archive/<station>/station=<station>/unsynced/boot=<boot>/…                            raw, verbatim (unchanged)
  derived/v1/<station>/day=YYYY-MM-DD.parquet          phone-written merged day (spec below)
  derived/v1/<station>/day=YYYY-MM-DD.manifest.json    sources {name,size,crc32,rows}, coverage, complete, builder version
  derived/v1/<station>/boot=<boot>.parquet             unsynced rows of one boot, on the monotonic axis (optional, §4.6)
Room (schema v4):
  archived_file       + rows, schema_version, first/last event_time_utc_ns, first/last monotonic_us, boot, footer offsets
  derived_day         station, day, complete, coverage_rows, source_count, built_at, builder_version, size, sha256
  aggregate           station, resolution ∈ {1 min, 15 min, 1 h, 1 day}, bucket_start_utc, n_valid, mean/min/max/p50 of pm1/pm25/pm10, board_t, battery…, coverage
  deployment          station, effective_from_utc, effective_to_utc?, lat, lon, alt?, indoor/outdoor, label, precision, deployment_id (§3)
```

**Daily file spec (v1), to be written as `docs/derived-daily-parquet.md` in the firmware repo so
Android and iOS implement one document:**

| Item | Choice | Why |
| --- | --- | --- |
| Rows | every row of every verified device file whose `event_time_utc_ns` falls in the UTC day, ordered by time; duplicates (same `boot_id`,`sequence`) dropped | UTC day = the Hive `day=` contract; verified files only (never present unverified data) |
| Schema | the 77-column v2 dictionary as the superset; v1 files get nulls in the four appended columns; plus `station_id` (UTF8), `source_file` (UTF8, dictionary-encoded or an INT32 index into the manifest), `deployment_id` (UTF8, nullable) | `station_id` is only in the path/metadata today, which is why tools cannot join two stations |
| Time | `event_time_utc_ns` keeps its name and INT64 value **and gains** `TIMESTAMP(NANOS, isAdjustedToUTC=true)` | PyArrow/DuckDB/Kotlin DataFrame then read an instant, not a bare integer ([Parquet timestamps](https://kotlin.github.io/dataframe/parquet.html#timestamps-and-time-zones)) |
| Row groups | **one per UTC hour** (≤ 360 rows, 24 per file) **with min/max statistics** on every column | lets any reader (DuckDB on the laptop, the iOS app) prune a 3-hour zoom to 3 row groups; measured cost ≈ +30 % over one row group, still 4–7× smaller than the device files |
| Encoding / codec | PLAIN + RLE def levels (like the device), **LZ4_RAW** | one codec on both sides; ZSTD is 35 % smaller but needs a native library on Android — revisit with R3 |
| Metadata | copy of the source files' key/value metadata that is invariant across the day (`station_id`, `schema_version`, `dictionary_*`), plus `derived_by`, `derived_version`, `source_count`, `coverage_rows`, `complete` | provenance travels with the file |
| Completeness | `complete = true` only when the manifest holds every device file the archive knows for that day *and* the day ended > 30 min ago on the device's UTC; otherwise the file exists, is usable, and is marked partial | late files (device offline, phone synced later) are normal; the day is rebuilt idempotently when its source set changes |
| Rebuild trigger | after any sync that verified a file for that day (interactive `SyncEngine` and `SyncWorker` alike), in a `DeriveWorker` chained by `AutoSyncScheduler`, never on the UI thread | WorkManager rule of `AGENTS.md` |
| Export | the share sheet / SAF export offers **Raw archive** (today's zip) and **Daily files** (small, analysis-ready) | a data uploader wants the small one; a firmware maintainer wants the raw one |

Sizing per station: raw ≈ 4 MB/day (1.5 GB/year), derived ≈ 0.7–1 MB/day (≈ 300 MB/year),
aggregate tiers in Room ≈ 8,760 hourly + 35,040 quarter-hour + 525,600 one-minute rows/year — the
one-minute tier is optional; the 15-minute tier alone answers a year view at 35 k points.

Alternatives considered and not chosen: (a) daily files **on the device** — the batch lives in a
non-power-loss-safe PSRAM buffer capped at 90 rows, so daily rotation is a firmware redesign, not a
setting; (b) **SQLite-only** history (no daily Parquet) — simplest on Android, but not portable to
iOS/hub/laptop, and it duplicates 3 M rows/year in a private format; (c) merging into **Arrow IPC** —
no Arrow runtime exists on Android (§1).

## 3. Location and the map

### 3.1 The rule this changes

`AGENTS.md` says "No location permission" and the README repeats it. This proposal **asks to amend
that rule**, not to ignore it. The privacy notes already anticipate it:
`../m5stack-aq-parquet/product/mobile-app.md` — *"Location is optional and private, with
indoor/outdoor context and effective dates. Unknown location prevents spatial sharing, not
logging."* Proposed replacement rule:

> Location is **opt-in, foreground-only, and per station**. The app requests
> `ACCESS_COARSE_LOCATION`/`ACCESS_FINE_LOCATION` only from the "Where is this logger?" step, only
> while that screen is open, never in the background (`ACCESS_BACKGROUND_LOCATION` is never
> declared). `BLUETOOTH_SCAN` keeps `neverForLocation`. The result is a **deployment record on the
> phone** (`deployment` table: station, effective dates, coordinates at the precision the user chose,
> indoor/outdoor, label); it is never written into the raw archive and leaves the phone only through
> the same explicit export/cloud opt-ins as everything else. The station itself receives at most an
> **opaque `deployment_id`**, never coordinates.

### 3.2 "Like the UTC idea": what to send to the station

`SET_TIME` works because the phone knows something the board cannot (wall-clock time) and the board
stamps it into the data. For location there are two designs:

| | A — send coordinates to the board (`SET_CONFIG deployment.lat/lon/…`) | **B — send an opaque `deployment_id`, keep coordinates on the phone (recommended)** |
| --- | --- | --- |
| Firmware change | new config keys, NVS storage, coordinates into file metadata | one config key `deployment.id` (≤ 32 chars), written into the already-existing `deployment_id` file metadata (today the sentinel `unknown`) |
| Privacy | coordinates on an **unencrypted SD card** and in every file forever (the mobile-architecture note warns exactly about this) | files carry a random id; the phone's `deployment` table (and only it) resolves id → place, dates, indoor/outdoor |
| Moves / corrections | rewrite metadata? Impossible, files are immutable; historic files carry the wrong place | new id when the logger moves; old files keep the old id; the phone table has effective dates either way |
| Multiple phones / iOS | each phone must send the same numbers | the id is set once; a second phone learns the id from the files/`info` and can attach its own record or import the owner's |
| Contract | both are `SET_CONFIG` changes owned by `../m5stack-aq-parquet/docs/ble-sync-protocol.md`; either needs a firmware PR first (AGENTS.md: contract lives there) | same; smaller diff; `info` JSON gains `deployment` so the phone can show "id set on 2026-09-17" |

B also works with **no firmware change at all** in the meantime: the phone can already key a
deployment record by `(station, effective_from)`, and back-fill the id later.

### 3.3 Map component

- Android: [MapLibre Compose 0.17.0](https://maplibre.org/maplibre-compose/getting-started/)
  (`org.maplibre.compose:maplibre-compose` + one Android runtime; it is a KMP library but usable from
  this plain Android project) or the classic `org.maplibre.gl:android-sdk` in an `AndroidView`.
  iOS: MapLibre Native iOS — the same style JSON, same PMTiles/offline files.
- **Tiles must not silently phone home.** Fetching a basemap from a tile server is a network call to
  an endpoint that is not the logger, so by the standing rule it needs its own opt-in. Options:
  (a) **no basemap by default** — the deployment step shows the pin on a neutral grid with
  coordinates, and a "Download map of this area (≈ N MB)" button is the opt-in; (b) a bundled,
  small, low-zoom world PMTiles pack (`pmtiles://file://` is supported since MapLibre Android 11.7.0
  per the [PMTiles doc](https://raw.githubusercontent.com/maplibre/maplibre-native/refs/heads/main/platform/android/docs/data/PMTiles.md);
  must be copied from assets to `filesDir` first — `asset://` lacks range reads) — a z0–5 world is a
  few MB and gives country/region context, a street-level pack is tens of MB and should be downloaded
  on request; (c) the MapLibre offline-region download for the user's area after an explicit tap.
  Recommendation: (a) + (b) now, (c) behind the same opt-in setting a later cloud step will use.
- What the map is *for* in this app: placing/confirming each logger (deployment step), the Home
  overview when several loggers are spread out (maintainer walking a site), and later the "Places"
  area of the product notes. It is not needed to read a chart, so the History screens never depend on
  it.

## 4. UI / UX

### 4.1 Information architecture — three depths, one vocabulary (Android and iOS)

The current app is one-logger-at-a-time (nav keys carry an address; Scan is home). The proposal
keeps every existing screen and re-parents them:

```
Home  (was: Scan)                                  ┐
  ├─ "My loggers" cards, one per known device       │ Simple — default for everyone
  ├─ Nearby / add a logger                          │
  └─ Collect from all nearby  (maintainer action)   ┘
Logger  (per device, bottom bar)
  ├─ Now        (was Today)  friendly reading + character + "today so far" + freshness
  ├─ History    NEW          day / week / month / year, bands, gaps, coverage, compare loggers
  ├─ Files      (existing)   sync, export raw / daily
  └─ Device     (existing)   + "Where is this logger?" (deployment, map), + Give time
Settings
  └─ Detail level:  Simple · Detailed · Developer         (replaces the binary "Advanced mode")
      Detailed adds: numbers next to the character, PM1/PM10, particle counts, board temperature,
                     battery/USB, all 77 columns in History ("Metrics" picker), file-level view,
                     Parquet metadata inspector, coverage/gap statistics
      Developer adds: the existing Debug destination (status JSON, frames, log, raw commands)
```

`AGENTS.md`'s "Advanced mode is opt-in and cosmetic" survives as-is: Detailed and Developer only
*reveal*; the Simple experience never depends on them. The DataStore key grows from a Boolean to an
enum with a migration (`advanced=true` → `DEVELOPER`).

iOS parity is achieved by sharing the **IA, states, terminology and copy** (one strings catalogue
exported to both `strings.xml` and `Localizable.xcstrings`), the **derived-data spec** (§2) and the
**character assets** (SVG source → Android `VectorDrawable`, iOS asset catalog). Components stay
native: Material 3 here, SwiftUI + Swift Charts there.

### 4.2 Friendly layer — what the number means, honestly

- **One character, five moods**, mapped to the PM2.5 bands the app already has
  (`pm25Band`: ≤ 15 · ≤ 35 · ≤ 55 · ≤ 150 · above, WHO 2021 24-h guideline = 15 µg/m³). A capybara
  works well: calm by nature, non-threatening, gender/culture-neutral; moods e.g. *soaking happily* /
  *content* / *sniffing the air* / *wearing a mask* / *indoors with the window shut*. Ship it as
  vector art, not Unicode emoji (emoji render differently on Android and iOS and cannot be tinted or
  animated consistently); optional subtle motion honours reduced-motion settings.
- **Every mood has a plain-language label and a one-line "what to do"**, so colour is never the
  only carrier (accessibility, and the product notes' "non-color status labels").
- **Say which window.** The live number is a 10-second snapshot; health guidance is about 24-hour
  averages. The Now card shows *"Right now: 12 µg/m³ — fresh air"* and *"Today so far: 18 µg/m³
  average, 92 % coverage"* (from the aggregate tiers), never a single "AQI" — the product notes are
  explicit that named AQI calculations need validated averaging rules, and the firmware's `pms_status`
  (warming/stale/error) must show as *"the sensor is still warming up"* rather than a number.
- **Comparisons instead of units** for the Simple level: "like a clean mountain day", "like a busy
  street", "like standing next to a barbecue" — a small table of reference situations with the
  source of each comparison in Detailed.
- **Gaps and staleness are first-class**: "no data 02:00–06:00 (logger was off)", "last reading 4
  min ago", "time was unknown for 3 days — estimated from a later time set" (§4.6).

### 4.3 Many loggers at once

Who: an owner with two or three loggers at home; a maintainer who visits a site with ten; a data
collector who only pulls files. What the engine allows today: **one session, one link at a time**
(`DeviceConnectionRepository` holds a single `DeviceSession`; `AutoSyncRunner` already iterates over
every known device sequentially).

| | M1 — sequential queue (recommended first) | M2 — concurrent sessions |
| --- | --- | --- |
| Engine change | none in `SyncEngine`; a queue in the repository ("collect from all nearby": scan → for each known device in range → connect → sync → disconnect → next), reusing `AutoSyncRunner`'s per-device logic interactively | repository becomes `Map<address, DeviceSession>`; `SyncEngine`, archive and Room are already per-file, so they cope; `SyncForegroundService`/notifications become multi-device |
| Live readings from several loggers | the Home cards show the **last reading and time** per logger (persisted from the last session), plus LAN `LIVE` pushes for loggers that are on the network — the LAN transport can hold several sockets cheaply | true live from several BLE loggers; Android allows ~7 GATT connections but bonded reconnects and notification storms compete |
| UX | a checklist: ✅ AQ-6b40 23 new files · ⏳ AQ-1a2f syncing 12/96 · ⚪ AQ-9c01 not in range; totals; one Stop | same list, several rows active |
| Risk | low; matches "one GATT operation at a time" | medium; new failure modes on BLE, more phone battery |

Recommendation: M1 now (Home + queue + per-logger last reading), M2 only for LAN first if a
measured need appears (a site with many loggers on one Wi-Fi is exactly where concurrency is cheap).

### 4.4 History screen (Simple and Detailed)

- Ranges: Day · Week · Month · Year · custom; the chart is a Compose `Canvas` (Swift Charts on iOS)
  drawing **band shading** behind the series so the capybara colours and the chart agree, a
  min/max envelope plus mean line for aggregated ranges, raw dots/line for ≤ 3 days.
- Data path: Day (≤ 3 days) → daily derived files (§2), column-projected; Week → 15-min tier;
  Month → 1-h tier; Year → 1-day tier. Zooming re-queries the next tier. Nothing above a few thousand
  points is ever handed to the renderer.
- Coverage bar under the chart (per bucket, from `n_valid`/expected), gaps hatched, unsynced-time
  segments dashed.
- Detailed adds a metrics picker over all 77 columns with the dictionary's unit and validity token,
  compare-two-loggers, and export of the visible range as CSV/Parquet.

### 4.5 Chart library choice

| Option | Notes | Verdict |
| --- | --- | --- |
| Compose `Canvas` (own ~400 lines: axes, bands, envelope, LTTB) | full control, no dependency, identical design spec for Swift Charts | **Recommended** |
| Vico | mature Compose charts, Android-only, styling fights the band shading | fallback if the custom one takes too long |
| Koalaplot / Kandy | KMP / notebook-oriented; Kandy is not an Android runtime library | No |

### 4.6 The unsynced-time problem (740 of 764 files)

Rows logged before the first `SET_TIME` of a boot have `event_time_utc_ns = null` and live under
`unsynced/boot=…`. But every row carries `monotonic_us`, and once the *same boot* receives a time
anchor, later rows carry `(event_time_utc_ns, monotonic_us)`. The phone can therefore **estimate**
UTC for the earlier rows of that boot: `utc ≈ anchor_utc − (anchor_mono − row_mono)`, with a drift
uncertainty proportional to the elapsed time. Proposal: compute this in the derive step into a
separate nullable column `event_time_utc_estimated_ns` plus `time_estimate_source_boot`, show those
segments dashed with the label "time estimated from a later time set", never write it into the raw
archive, and never let the estimate cross a `clock_epoch` change. Boots that never got an anchor stay
on a "relative to power-on" axis only. This is the biggest single UX win for existing data, and it
makes the "Give the logger the time" nudge on Home worth being prominent.

## 5. iOS parity — what to fix now so the Swift app is cheap later

1. Write the **derived-daily-parquet spec** and the **aggregate-tier definitions** as documents in
   the firmware repo (the contract owner), with fixture files both apps must read and write
   identically; validate both with the PyArrow/DuckDB harness.
2. Keep every formula (bands, comparisons, coverage, time estimate) in a **pure Kotlin module with
   unit tests** (`data/history/*`, no Android imports) so it can be transliterated to Swift line by
   line; the same fixtures drive `XCTest`.
3. One strings catalogue and one asset source (SVG) for the character and icons.
4. Same three detail levels, same screen names, same states (“fresh”, “stale”, “warming up”,
   “time unknown”, “estimated”), same empty-state copy.
5. Do **not** adopt Compose Multiplatform or KMP for the UI (the user wants native Swift); MapLibre
   Compose remains an Android-side choice, MapLibre Native iOS its counterpart.

## 6. Decisions requested

| # | Decision | Recommendation |
| --- | --- | --- |
| D1 | Parquet engine | **R1** own subset reader/writer in Kotlin (§1); DuckDB/Rust deferred |
| D2 | Merge per day | **Yes, derived layer** `derived/v1/<station>/day=….parquet` + manifest, hourly row groups with statistics, LZ4_RAW, TIMESTAMP annotation; raw archive untouched (§2) |
| D3 | Aggregate tiers | 15 min · 1 h · 1 day in Room now; 1 min optional later (§2) |
| D4 | Location | Amend `AGENTS.md` as in §3.1; **design B** (opaque `deployment_id` to the board via a firmware-repo contract PR; coordinates only on the phone) |
| D5 | Map | MapLibre Compose; no network tiles by default; bundled low-zoom PMTiles + explicit "download this area" opt-in (§3.3) |
| D6 | Detail levels | Simple · Detailed · Developer, replacing the Boolean Advanced switch with a migration (§4.1) |
| D7 | Character | one vector capybara, five moods, labels + actions, reduced-motion aware; no Unicode emoji as the primary carrier (§4.2) |
| D8 | Many loggers | **M1** Home cards + sequential "collect from all nearby" queue; M2 (concurrent, LAN first) only after a measured need (§4.3) |
| D9 | Unsynced time | implement the estimate of §4.6 in the derive step, clearly labelled |
| D10 | Charts | Compose `Canvas` with a written design spec that Swift Charts follows (§4.5) |

**Progress (2026-09-17):** steps 1 and 2 below are implemented and gated (`data/history/parquet`,
`data/history/ParquetIndexer` + `HistoryIndexer`, Room v4, `ui/history/FileMetricsScreen` behind
Advanced mode; 102 unit tests). The README's "Design decisions → History" table records the choices
made while implementing. Steps 3–7 are next.

Suggested order of work once decided: (1) `data/history/parquet` reader + tests on real files →
(2) footer index in Room + Detailed "Metrics" screen reading raw days (proves the reader on the
phone) → (3) writer + `DeriveWorker` + daily files + tiers → (4) History screen + Now card friendly
layer + Detail levels → (5) Home with many loggers + collect queue → (6) deployment record + map +
firmware `deployment.id` contract PR → (7) spec documents for iOS.

## Firmware follow-up (2026-09-17, `../m5stack-aq-parquet` uncommitted source)

The measurements in §0 prompted writer changes on the firmware side — built, host-tested, and **flashed hash-verified on 2026-09-17**; the first 1800 s file (`data_0930_…_2-106-2.parquet`, 2 row groups, 105 rows) was fetched over serial the same day and is now a committed fixture that the Kotlin reader decodes and cross-checks against its own footer statistics (`RealArchiveTest`); `../m5stack-aq-parquet/docs/bench-verified.md` remains the authority on what the *board* has been measured to do. Every file in the current phone archive is still as described in §0. The app already tolerates v3 devices (`ignoreUnknownKeys`, generic interval display, `ParquetVerify` checks only magic/size/CRC) and mirrors the two new `status` keys `open`/`open_rg` (`DeviceStatus`, Today storage card). Once `arduino-cores3-parquet-v6` is on the board, device files (`schema_version = cores3-telemetry-v3`, `dictionary_version` still `cores3-telemetry-v2`, same 77 leaves) will carry:

| §0 finding | v3 device file | Consequence for R1 (own Kotlin/Swift reader) |
| --- | --- | --- |
| exactly 1 row group | up to **8** row groups per file (one per ≤ 90-row batch); 1 at the default 900 s, 2 at 1800 s, 4 at 3600 s | iterate `row_groups`, not `row_groups[0]`; the "hourly row groups" of the derived layer already exist inside an hourly device file |
| `null_count` only | `min_value`/`max_value` (PLAIN LE bytes) + `is_*_exact`, `nan_count` on FLOAT, `column_orders` = TYPE_ORDER; deprecated `min`/`max` **absent** | read Statistics fields 5/6/7/8/9; the `archived_file` index (first/last `event_time_utc_ns`, `monotonic_us`, `sequence`) comes straight from the footer, no page decode |
| INT64, no TIMESTAMP | `event_time_utc_ns`, `clock_anchor_utc_ns` annotated `TIMESTAMP(NANOS, isAdjustedToUTC=true)` (SchemaElement field 10 → LogicalType field 8) | the reader must accept a `logicalType` on a leaf; physical decode is unchanged. DuckDB shows these as TIMESTAMPTZ at µs — use `epoch_ns()`; PyArrow keeps ns |
| 7 KB footer / 88 rows | +1.5 KB statistics per group; per-chunk metadata (~57 B × 77) dominates, so hourly files only take the footer share from 17 % to ~14 % | the byte win remains LZ4 + the derived daily file (§2); hourly device files mainly mean 4× fewer files to sync |
| — | `RowGroup.sorting_columns` = `sequence` ascending, `file_offset`, `total_compressed_size`, `ordinal`; `created_by = m5stack-aq-parquet version 0.2 (build arduino-cores3-parquet-v6)`; metadata `row_groups`, `row_group_rows_max` | optional to use; `created_by`/`schema_version` distinguish v3 files |
| `.partial` retained | a reset between row groups leaves a footer-less `.partial` whose earlier groups are complete on the card | still never listed by the device; a footer-rebuild tool is possible later and would belong in the firmware repo |

Nothing changes for sync: identity is still `(station, name, size, crc32)` per finalized file, and `ParquetVerify` only checks magic/size/CRC. The derived daily spec in §2 stays as written; its writer and the device writer now agree on statistics rules (parquet-format 2.13 TYPE_ORDER float rules) and on the TIMESTAMP annotation. Reference: `../m5stack-aq-parquet/docs/telemetry-pipeline.md` ("Direct Parquet experiment") and `tools/parquet_footer.py` there, a 100-line Thrift-compact footer decoder that is a direct template for the Kotlin footer parser.

## Sources checked (2026-09-17)

- [Kotlin DataFrame — Parquet](https://kotlin.github.io/dataframe/parquet.html): Arrow Dataset JNI, "not available on Android", no predicate pushdown, read-only.
- [Kotlin DataFrame — Apache Arrow](https://kotlin.github.io/dataframe/apachearrow.html): IPC read/write in pure Java, type mapping.
- `Kotlin/dataframe` repo: `docs/StardustDocs/topics/setup/SetupAndroid.md` (exclude `dataframe-arrow` on Android), `topics/FAQ.md`, issues #712, #1445, #24.
- [DuckDB — Building for Android](https://duckdb.org/docs/current/dev/building/android.html): experimental, build from `main` with the NDK.
- [MapLibre Compose — Getting started](https://maplibre.org/maplibre-compose/getting-started/): 0.17.0, Android OpenGL/Vulkan runtimes, iOS linker flags.
- [MapLibre Native Android — PMTiles](https://raw.githubusercontent.com/maplibre/maplibre-native/refs/heads/main/platform/android/docs/data/PMTiles.md): `pmtiles://file://` since 11.7.0, no `asset://`, no offline packs for PMTiles.
- `apache/arrow-java` issue #801 (Android allocation manager) — open.
- Measurements in §0: PyArrow over `../m5stack-aq-parquet/exports/sd-20260916T173205Z` (756 station files, 66,560 rows).
