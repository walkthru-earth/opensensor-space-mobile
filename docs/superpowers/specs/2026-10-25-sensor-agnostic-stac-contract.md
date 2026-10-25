# Sensor-agnostic app on a STAC contract

Research report, 2026-10-25. Status is proposal. Nothing here is implemented yet.

Goal. opensensor.space should stop being an air-quality app. Any station (air, weather, soil, water, noise, power) should sync, archive, chart and appear on Discover from a metadata contract alone, with no per-metric Kotlin. The contract is STAC JSON (Catalog, Collection, Item) plus a few standard extensions and one small custom extension. Modules and types get generic names afterwards.

Inputs.
- Read-only audit of this repo (all 26 Gradle modules and the iOS targets).
- Read-only audit of `../aq-parquet` by this session, plus an independent audit by a Claude agent in an Orca worktree (branch `yharby/stac-firmware-audit`, commit `2b49114`, output kept uncommitted in `reports/stac-firmware-audit.md` of that worktree). A second independent pass checked that audit line by line against code, git history and the STAC specs (`reports/stac-firmware-audit-review.md`), and its corrections are folded in here (shared components, the CoreS3 and Waveshare board adapters. `firmware/esp-idf-n16r8` holds only build leftovers, no source, `docs/shared/*`, `product/*`).
- Online research on STAC 1.1, STAC extensions, stac-geoparquet, OGC SensorThings, OGC API Connected Systems and CF conventions. Sources are listed at the end.

---

## 1. Verdict

1. The app is about 80 percent ready for this. Sync, archive, verification, Parquet reading, daily rollups, tiers and Discover rendering are already generic. The AQ coupling sits in five places. These are the metric registry, the LIVE model, the Today, History and Devices screens, `:core:airquality`, and the Discover WHO legend.
2. The firmware already exposes almost everything a STAC Item needs. That covers station UUID, H3 cell and centre, country, UTC range from column statistics, schema name, firmware, dictionary SHA-256 and `field_id` per column. What it does not expose is the per-column dictionary itself. Units, procedure, validity and property URI exist only in `telemetry_fields.inc` and never reach the phone. The phone recognises a digest and falls back to a hardcoded profile.
3. STAC fits the envelope well (catalog, station, file, column list, units, statistics, i18n, Parquet mirrors). STAC does not model observation semantics such as validity gates, aggregation, health scales or a LIVE key. Those need one small custom extension. The mapping to SensorThings (Thing, Datastream, ObservedProperty, Sensor) is kept in field names so a later SensorThings or Connected Systems adapter is a projection, not a redesign.
4. Recommended split. The firmware owns facts (name, type, unit, procedure, validity, live key, sensor model, cadence). A published profile owns presentation (labels in en and ar, precision, scales and colours, chart defaults, hero card). Both are keyed by the dictionary SHA-256 that devices already send.

---

## 2. What exists today

### 2.1 Mobile app

Three separate metric descriptions exist, and they do not share a model.

| Description | Where | Source | Fields |
| --- | --- | --- | --- |
| `MetricSpec` | `core/history/.../Metrics.kt:23` | Hardcoded, 12 entries at `:120` | id, column, label (en), unit, kind, gate, healthScale, chartable, envelope, axisFloor, settleSeconds, property, basis, humidityColumn |
| `LiveProfile` and `LiveSample` | `core/protocol/.../LiveProfile.kt`, `JsonModels.kt:133` | Hardcoded per known digest (10 digests), unreviewed vocabulary fallback since a139d80e | temperature source, humidity flag, key set. `LiveSample` is a fixed data class and drops unknown keys |
| `FeedProperty` | `core/publicfeed/.../Feed.kt:35` | Read at runtime from the Parquet key `dictionary` | id, label, unit, category, precision |

`FeedProperty` is the only data-driven one, and it is the seed of the new model.

Hard AQ logic.
- `Metrics.primary = PM25` (`Metrics.kt:129`) drives `validRows` for every bucket (`Aggregation.kt:46`). A soil station would report zero valid rows.
- `PMS_VALID = Gate("pms_status", 4)` and `PMS_SETTLE_S = 180` are board constants inside the registry (`Metrics.kt:87`).
- `:core:airquality` holds WHO 2021 scales (`HealthScale.kt:58-88`, looked up by the strings `pm25` and `pm10`), EPA PM2.5 correction, QC rules (not wired) and `AirMood`.
- Today picks cards from `DeviceCapabilities.showsParticles` and a `waveshare-sim7670g-telemetry-` prefix check. A device without `pms` falls back to a temperature and humidity card only (`TodayCards.kt:359-379`).
- History defaults to `PM25_CORRECTED` and snaps axes to WHO edges. Devices rows show PM2.5 and mood.
- Discover chips, labels and categories already come from the feed dictionary. Only the WHO legend and `isRawPm25` are hardcoded.

Module coupling.

| Coupling | Modules |
| --- | --- |
| Hard (AQ science or AQ-shaped logic) | `:core:airquality`, `:core:history` (registry), `:core:protocol` (LIVE), `:feature:today`, `:feature:history` |
| Semantic (AQ choices inside generic code) | `:core:publicfeed`, `:feature:discover`, `:feature:devices`, `:feature:device`, `:ui:designsystem` (mood strip, peacock, `pm25Label`) |
| Naming only (`earth.walkthru.aqsync` package) | `:core:parquet`, `:core:units`, `:core:net`, `:core:sync`, `:core:archive`, `:core:database`, `:core:storage`, `:core:settings`, `:feature:files`, `:feature:settings`, `:feature:debug`, `:app`, `:ios:shared` |
| None | `:ui:strings`, `:ui:permissions`, `:feature:shell` |

The docs already describe the target. `docs/devices-and-discovery.md:17-69` defines Device, DeviceFamily, Capability, ObservedProperty, Datastream and Scale, with rules such as "the app never invents units or bands" and "unknown columns still show with their raw name". The planned `:core:model` module does not exist yet (`docs/architecture.md:85-91`).

### 2.2 Firmware (`../aq-parquet`)

Columns are an X-macro per board.

```text
// firmware/esp-idf-cores3/bringup/telemetry_fields.inc
FIELD(name, physical_type, procedure, unit, validity, live_key)
FIELD(pm25_atmospheric_ug_m3, Int32, pmsa003, "ug/m3", pms_valid, "pm25")
FIELD(imu_temperature_c, Float, m5_imu, "degC", imu_temperature_read_finite, "imu_t")
```

- `field_id` is the 1-based line position, append-only. CoreS3 has 81 fields, Waveshare 62.
- `telemetry_contract.h` expands it to `kFields[]` with name, type, procedure, unit, validity, property URI (`urn:walkthru-earth:<board>:property:<name>`) and live key.
- `telemetry_dictionary_digest.h` is the SHA-256 of the raw `.inc` bytes. Any edit changes it, even a comment.

What reaches the phone.

| Channel | Content | Column semantics? |
| --- | --- | --- |
| BLE `info` and LAN `HELLO` | `{"proto":3,"fw":...,"schema":"cores3-telemetry-v5","cols":81,"dict":"<sha256>","station":"<uuid>",...}` | No, only the digest |
| BLE `live` and LAN push `0x49` | Flat `{live_key: value}`, 480 bytes max, floats as `%.1f` | No, keys only |
| Parquet schema | name, physical type, TIMESTAMP annotation, `field_id`, column statistics | No units or descriptions |
| Parquet footer (budget of 22 fixed, 7 location, 3 anchor and 32 board entries against a 64 entry cap, real use 37 on CoreS3 and 45 on Waveshare. The writer also hardcodes 64 and caps each value at 4096 bytes, `parquet_writer.cpp:593-600`. Going over fails the whole file, it does not truncate) | `schema_version`, `station_id`, `firmware`, `dictionary_version`, `dictionary_uri` (a GitHub `main` URL), `dictionary_sha256`, `acquisition_config`, H3 cell and resolution, country, code legends as free text | Pointer only |

Other firmware facts that shape the design.
- `info` is built in a 400 byte buffer (`ble_sync.cpp:56`) and the LAN `HELLO` frame has its own 400 byte cap (`wifi_link.cpp:1020-1031`). CoreS3 `info` is 287 bytes and Waveshare 301. If the BLE string overflows, BLE does not start at all, so any new key there must stay short.
- LIVE overflow is silent. Above 480 bytes `format_live` returns 0, nothing is published, and the last good snapshot keeps being served (`aq_logger.cpp:1614-1622`). The fixture's widest-value test is not the real worst case. A CoreS3 row with every float at full width can reach about 520 bytes, so real headroom for new CoreS3 LIVE keys is about 35 bytes.
- An absent LIVE key means invalid or unrepresentable (NaN, Inf, an out of range `utc`, text over 31 characters), not only invalid.
- `field_id` is a stable join key only from schema v5. Commit `e166978` added field ids and changed position 9 from nanoseconds to microseconds in place, so older files reuse positions with different units.
- The same LIVE key can mean different things per board. `t` is ambient on Waveshare and null on CoreS3. `pct` is an M5 power range on CoreS3 and a truncated gauge value on Waveshare. Property URIs therefore stay per board, as `table-and-observation-model.md:138` intends. A shared property would conflate them. Cross-board comparison belongs in a separate "comparable to" link.
- Code values are layout, not labels. Waveshare `pms_status` adds `5 = model-mismatch` and owner decision #39 plans `6`. Under the LIVE stability rule (`ble-sync-protocol.md:208-214`) a new code value must move the `dict` digest.
- GATT characteristics including `info` need encryption (`ble_sync.cpp:1123-1134`), and LAN needs the token before `HELLO`. A device-served descriptor is post-pairing only, so it must hold board facts only, never station or location data.
- CoreS3 `ambient_temperature_c` and `relative_humidity_percent` (`telemetry_fields.inc:79-80`) are always null because of the SHT20 address collision. The app shows the board IMU temperature (`imu_t`) instead. Waveshare gets valid `t` and `rh` from the PMS5003T.
- The only per-column vocabulary that leaves the device is free text in board footer keys (`pms_status`, `gauge_status`, `sensor_model` and similar). Waveshare also reports vendor and model on config page 0. CoreS3 reports no sensor model at all.
- `docs/shared/table-and-observation-model.md:40` is stale. It says Waveshare v3 has 56 fields, the current table has 62.

No opcode returns the dictionary (`ble-sync-protocol.md:224-241`). A full dictionary JSON can be produced on the host by `tools/test_telemetry_contract.py --dictionary-out` (it runs the fixture binary as `<exe> dictionary`). The same fields are also printed at runtime by the serial `parquet schema` console command (`aq_logger.cpp:2522-2534`), so they are in flash, just not on BLE or LAN. The JSON is shape `{schema, schema_version, dictionary, firmware, sha256, fields:[{name,type,procedure,unit,validity,property,field_id,live}]}`, about 15.4 KB minified for CoreS3, and is not committed or published.

Shared-code limits that block non-AQ boards.
- 96 columns max (`parquet_writer.h:8`). Types are Int32, Int64 and Float only.
- Footer literals `sample_interval_ms = "10000"` and `row_group_rows_max = "90"`. `deployment_id` and `calibration_id` are always `"unknown"`.
- LIVE floats at one decimal. pH 7.25, soil EC or accelerometer values under 0.05 g lose precision. Changing the format is a wire change.
- BLE name prefix `AQ-`. The `sensor.*` config keys assume one PMS-style module.

`docs/shared/table-and-observation-model.md` already decided the layering. Immutable wide Parquet stays the raw archive, a versioned dictionary carries meaning, and SensorThings and Iceberg are later host-side projections. It proposes Thing = station, Sensor = procedure, Datastream = column contract, ObservedProperty = exact quantity and processing variant, feature of interest = local air or the board itself. Nothing in either repo mentions STAC today.

---

## 3. Research findings

### 3.1 STAC core 1.1 (released Sept 2024)

- Common metadata now has `unit`, `data_type`, `nodata`, `statistics` (`minimum`, `maximum`, `mean`, `stddev`, `count`, `valid_percent`) and a generic `bands` array that "need not be spectral". `unit` should be UCUM or UDUNITS-2.
- Common metadata also has `instruments`, `platform`, `constellation`, `mission`, `keywords` and `roles`.
- Best practices for static catalogs. Use relative structural links, name files `catalog.json`, `collection.json` and `<id>.json`, keep sub-catalogs under about 1 MB, and partition when there are tens of thousands of children.
- STAC targets spatio-temporal data. Our data always has an H3 cell, so every Item has a geometry (the H3 cell polygon, never the raw fix).
- Ranges use `start_datetime` and `end_datetime`, which suits per-file or per-day Items.

### 3.2 Extensions that fit

| Extension | Version, maturity | Use here |
| --- | --- | --- |
| Table (`table:`) | v1.3.0, Pilot | `table:columns` (name, type, description, plus common `unit`, `data_type`, `statistics`), `table:primary_datetime`, `table:row_count`. The column list of a Parquet asset |
| Datacube (`cube:`) | v2.3.0, Candidate | `cube:variables` with `type: data` or `auxiliary`, `unit`, `extent`. Optional richer view of the same columns, good for clients like xarray. `cube:dimensions` becomes required, and a station dimension needs a custom type, never `spatial` |
| CF (`cf:`) | v1.0.0, Proposal | `cf:standard_name` and `cf:cell_methods` on columns. Gives a shared vocabulary, for example `mass_concentration_of_pm2p5_ambient_aerosol_particles_in_air` (canonical unit kg m-3) |
| Classification | v2.0.0, Pilot | Classes with `value`, `name`, `description`, `color_hint`. Fits code columns such as `pms_status` and `clock_status`, which are free-text legends today. It documents assets, bands and Item properties, not table columns, so this design uses the same class shape under `opensensor:codes` |
| Grid (`grid:code`) | v1.1.0 | Item scope only. `H3-<cell>` is allowed as a proprietary designation. Use only the owner's privacy-capped cell |
| Language (`language`, `languages`) | v1.0.0, Proposal | Declares en and ar variants of titles and descriptions |
| File (`file:`) | v2.1.0, Stable | `file:size`, `file:checksum` on Parquet assets. The phone already verifies size and CRC-32 |
| Version | v1.2.0, Candidate | `version`, `deprecated`, predecessor and successor links between profile revisions |
| Contacts, Scientific | Proposal, Stable | Owner and citation for CC BY 4.0 publishing |
| Time Series (`ts:`) | Archived Jan 2023 | Do not use |
| Moving Features | WIP, unreleased | Not needed, stations are fixed |

No registered STAC extension describes in-situ station observations, validity gates, aggregation rules or health scales. That gap is why a small custom extension is proposed in section 4.4.

### 3.3 stac-geoparquet

- Spec v1.1.0. One row per Item, required columns `id`, `geometry`, `bbox`, `links`, `assets`, `stac_extensions`.
- Parquet footer key `stac-geoparquet` holds `version` and `collections` (a map of id to full Collection JSON). This makes a Parquet file self-describing.
- A Collection can point to its GeoParquet mirror through an asset with role `collection-mirror` and type `application/vnd.apache.parquet`.
- Relevance. The public feed on source.coop can ship one `items.parquet` per station collection instead of thousands of small Item JSON files, and the app's existing `:core:parquet` reader can read it over HTTP range requests.

### 3.4 Observation standards for comparison

- OGC SensorThings API. Thing has Locations and Datastreams. Each Datastream links one Sensor and one ObservedProperty, carries `unitOfMeasurement` and `observationType`, and holds Observations. V2 is still a draft. The firmware doc already says not to claim compliance.
- OGC API Connected Systems Part 2 (published 2025-07) with SensorML 3.0 and SWE Common 3.0 JSON. Datastreams describe record schemas with definition URIs and UoM codes. It is complete but heavy for a phone app and a microcontroller.
- No published STAC to SensorThings crosswalk was found. The sta2stac project (KIT) exposes STA servers through STAC but documents no field mapping we could reuse.

Conclusion. Use STAC for discovery and file-level description. Borrow SensorThings names (Station, Datastream, ObservedProperty, Sensor) for our own types, so a later STA or Connected Systems endpoint is a straight projection.

### 3.5 Units and vocabularies

- STAC `unit` accepts UCUM or UDUNITS-2, while the CF extension ties `unit` to CF `units`, which are UDUNITS. This design puts the UDUNITS string in `unit` and the UCUM code in `opensensor:ucum`, and keeps the firmware label in `opensensor:unit_label`.
- UCUM is case-sensitive. `ug/m3` is valid. `degC` becomes `Cel`. `count/0.1L` becomes `{count}/dL`. `g` in the CoreS3 IMU columns means standard gravity, which is `[g]` in UCUM (plain `g` is gram). `byte` is `By`. `YYYYMMDD` and `hhmmss` are formats, not units. `us_since_unix_epoch` is a time column described by `table:primary_datetime`.
- CF names need care. `mass_concentration_of_pm2p5_ambient_aerosol_particles_in_air` fits the `atmospheric` columns only, not the CF=1 columns. `air_temperature` must not be used for `imu_temperature_c`.
- Keep the firmware label as written and add the UCUM code next to it. Never rewrite the firmware string, because it is part of the digest.
- Observed property URIs stay `urn:walkthru-earth:<board>:property:<name>`. Add `cf:standard_name` where CF has one. Leave it empty otherwise, as the CF extension requires.

---

## 4. Proposed contract

### 4.1 Mapping

| Our concept | SensorThings | STAC | Today |
| --- | --- | --- | --- |
| Network root | none | Catalog `opensensor.space` | none |
| Device profile (one dictionary digest) | Sensor plus ObservedProperty set | Collection of type profile, no Items, `table:columns` plus `opensensor:` fields | `telemetry_fields.inc`, `LiveProfile`, `MetricSpec` |
| Station | Thing | Collection per station UUID, `extent` from data, `links rel=describedby` to its profile | station UUID, footer keys |
| Column | Datastream | One entry in `table:columns` (and optionally `cube:variables`) | `FIELD(...)` line |
| Quantity | ObservedProperty | `cf:standard_name` plus `opensensor:property` URI | `property_uri` |
| Day of data | none | Item per Hive day partition, geometry = H3 cell polygon, `start_datetime` and `end_datetime` from column statistics | `station=/year=/month=/day=` |
| Parquet file | none | Asset with `file:size`, `file:checksum`, roles `data` | file in archive |
| Discover aggregate | none | Collection `discover-cells` with assets per `h3_res`, `table:columns` from the feed dictionary | `FeedProperty` and `manifest.json` |

Why Item per day and not per file. A station writes about 35,000 files per year at 15 minute rotation. Per-day Items give 365 per year, line up with the Hive layout and with the app's existing daily Parquet builder, and keep catalogs well under the 1 MB guidance. Unsynced device files also cannot be valid Items, because STAC needs `datetime` or a start and end, and those files have null UTC on every row. Only the phone's reconciled daily files have a real range. Items should carry `clock_status` and `clock_epoch`, because RTC-restored days are dated but approximate. A station UUID lives in NVS and follows the board when it moves, so the H3 cell and time range tell deployments apart.

### 4.2 Who owns what

| Fact | Owner | Delivery |
| --- | --- | --- |
| Column name, type, `field_id`, firmware unit label, procedure, validity rule, live key, sample interval | Firmware | Generated at build time from `telemetry_fields.inc` |
| Code values (for example `pms_status` 0 to 5) | Firmware `telemetry_fields.inc` side, a new value moves `dict` | Generated descriptor |
| UCUM and UDUNITS codes, `cf:standard_name`, sensor make and model, labels for code values | Firmware source, reviewed once per digest | Same generated descriptor, from a new sidecar table next to the `.inc` so the existing digest does not change |
| Labels (en, ar), description, display precision, category, chart role, default metric, hero card, scales and colours | Profile registry (repo plus source.coop) | Published JSON keyed by digest, bundled in the app as fallback |
| Station metadata (H3 cell, country, license, owner contact) | Phone at provisioning | Station Collection written by the phone, uploaded only when the owner enables publishing |
| Statistics per day | Phone | Computed from Parquet footers, already done by the daily builder |

Keeping labels and health scales out of firmware means WHO, EPA or national scale updates, new translations and new colour rules ship without a flash.

### 4.3 Delivery paths

1. Bundled profiles first. Generate profile Collections for every known digest (CoreS3 v3 to v5, Waveshare v1 to v5) from `tools/fixtures/live_keys/*.json` plus the host dictionary JSON. Commit them to `aq-parquet/docs/shared/profiles/` and bundle them in the app. No firmware change. This replaces `LiveProfiles.resolve` and `Metrics.all`.
2. Published profiles. Mirror the same files on source.coop under `opensensor-space/profiles/<sha256>.json`. Change the footer `dictionary_uri` from a GitHub `main` URL to this immutable address in the next firmware release.
3. Device-served descriptor (optional). Add read-only opcode `0x11 GET_DESCRIPTOR` (opcodes end at `0x10`) and frame type `0x4F DESCRIPTOR_CHUNK` (frame types end at `0x4e`, `ble_sync.h:43-86`). Use a request per chunk, `GET_DESCRIPTOR(offset u32, length u16)`, answered with one frame up to the negotiated size (512 on BLE, 1024 on LAN), so the app can re-request lost ranges. The `CONFIG_CHUNK` style does not fit, because it is BLE only, sends 128 byte slices with no end frame, and Android drops bursts of 16 to 18 notifications. Advertise support with `"desc":"<16 hex prefix>"` in `info` (313 and 327 bytes, under both 400 byte caps), or probe the opcode, since old firmware answers an unknown opcode with ERROR 11. `AqJson` ignores unknown keys, so released apps are safe. A new frame type is safe only because it is never sent unsolicited, since `Frames.kt:499` throws on unknown types. An app with that descriptor cached skips the download.
   - The descriptor gets its own digest (`desc`) over the generated JSON bytes. `dict` keeps covering only `telemetry_fields.inc`, so released app profiles stay valid while labels, units and scales evolve. `desc` needs the same three gates as `dict` today (`tools/build-idf-board.sh:19-23` and both contract tests), or it will drift.
   - Cost. Today's bare CoreS3 dictionary is already 15.4 KB minified (about 1.9 KB zlib). A full Collection with labels in two languages, CF names, codes and scales is likely 25 to 40 KB raw. The app slot is 6 MB on both boards, so that is still under 1 percent. No ESP-IDF image size is recorded yet, the 1.4 MB figure is from Arduino builds. RAM cost is near zero because chunks stream from `.rodata` into the existing frame buffer. Serving raw JSON avoids a gzip dependency on the phone.
   - Footer. Do not embed the descriptor in each Parquet file. A full Collection exceeds the 4096 byte value cap, and `table-and-observation-model.md:114` already rules out repeating descriptions in every small file. Add two keys instead, `stac:collection_href` (pinned URL or `urn:opensensor:collection:<board>:<desc sha>`) and `stac:descriptor_sha256`, about 260 bytes per file. Pass them as board metadata, which only counts against the 32 board slots (CoreS3 goes from 5 to 7, Waveshare from 13 to 15). No budget change is needed. Never raise `kFooterMetadata` alone, because the writer's literal 64 would then fail every file.
4. Self-describing exports. When the phone exports or publishes a daily Parquet file, write the station Collection into the footer under the `stac-geoparquet` key, `collections` map. A file then stands alone on source.coop or in DuckDB. `DailyParquetBuilder.kt:488` copies a fixed key list (`COPIED_METADATA`), so it must also copy the new `stac:*` keys or they never reach published files.

### 4.4 Custom extension `opensensor:`

The draft lives in walkthru-earth/stac-opensensor, with its JSON Schema at `https://walkthru-earth.github.io/stac-opensensor/v0.1.0/schema.json`. Field names follow SensorThings words. All fields are optional, so a profile with only `table:columns` and `unit` still renders as plain numbers with raw names.

Column-level fields (inside `table:columns` entries).

| Field | Type | Meaning | Replaces |
| --- | --- | --- | --- |
| `opensensor:property` | string URI | Observed property URI | `property_uri` |
| `opensensor:procedure` | string | Firmware procedure or sensor id | `procedure` |
| `opensensor:ucum` | string | UCUM code next to the UDUNITS `unit` | none |
| `opensensor:unit_label` | string | Firmware unit label, unchanged | `unit` in `telemetry_fields.inc` |
| `opensensor:field_id` | integer | Parquet `field_id`, join key to Iceberg, schema v5 and later | none |
| `opensensor:codes` | array of `{value, name, title, color_hint}` | Meaning of code values, Classification class shape | free-text board footer keys, `PmsStatus` |
| `opensensor:live_key` | string | LIVE JSON key, `[a-z0-9_]{1,15}` | `VOCABULARY`, `LiveSample` fields |
| `opensensor:role` | enum `measurement`, `health`, `identity`, `time`, `status` | Separates environmental data from board health and bookkeeping | `MetricKind`, `chartable` |
| `opensensor:valid_when` | `{column, equals}` or `{column, finite}` | Validity gate | `Gate`, `PMS_VALID` |
| `opensensor:settle_s` | number | Warm-up seconds after power on | `settleSeconds` |
| `opensensor:aggregate` | enum `mean`, `min`, `max`, `last`, `sum`, `count` | How tiers roll up | implicit mean |
| `opensensor:range` | `[min, max]` | Plausible range for QC and axis floor | `axisFloor`, `QcRules` |
| `opensensor:precision` | number | Display step | `FeedProperty.precision` |
| `opensensor:scale` | id | Reference to a scale object | `healthScale` |
| `opensensor:derived` | `{from: [...], method: id}` | Derived column computed on the phone, for example EPA correction | `basis`, `humidityColumn` |
| `title`, `description`, `languages` | i18n | Labels in en and ar | string resources, `label` |

Collection-level fields.

| Field | Meaning |
| --- | --- |
| `opensensor:digest` | SHA-256 of the firmware dictionary this profile describes |
| `opensensor:family` | Family id such as `air`, `weather`, `soil`, `water`, `noise` |
| `opensensor:primary` | Ordered list of default columns for Today, History and Devices |
| `opensensor:scales` | Map of scale objects. Each has `source` (citation URL), `unit`, `steps` (`upper`, `label` en and ar, `color_hint`) and an optional `guideline` line |
| `opensensor:hero` | Optional hero card id that a family plugin can render, for example the air mood face |
| `opensensor:sample_interval_s`, `opensensor:proto_min` | Cadence and minimum protocol |

### 4.5 Example profile (shortened)

```json
{
  "type": "Collection",
  "stac_version": "1.1.0",
  "stac_extensions": [
    "https://stac-extensions.github.io/table/v1.3.0/schema.json",
    "https://stac-extensions.github.io/cf/v1.0.0/schema.json",
    "https://stac-extensions.github.io/classification/v2.0.0/schema.json",
    "https://walkthru-earth.github.io/stac-opensensor/v0.1.0/schema.json"
  ],
  "id": "profile-cores3-telemetry-v5",
  "title": "CoreS3 air station",
  "description": "M5Stack CoreS3 with PMSA003",
  "license": "CC-BY-4.0",
  "extent": {"spatial": {"bbox": [[-180, -90, 180, 90]]}, "temporal": {"interval": [[null, null]]}},
  "opensensor:digest": "<sha256 of telemetry_fields.inc>",
  "opensensor:family": "air",
  "opensensor:primary": ["pm25_atmospheric_ug_m3", "imu_temperature_c"],
  "opensensor:scales": {
    "who-2021-pm25": {
      "source": "https://www.who.int/publications/i/item/9789240034228",
      "unit": "ug/m3",
      "guideline": 5,
      "steps": [
        {"upper": 5, "label": {"en": "Guideline", "ar": "..."}, "color_hint": "2E7D32"},
        {"upper": 10, "label": {"en": "Interim 4"}, "color_hint": "9E9D24"}
      ]
    }
  },
  "table:primary_datetime": "event_time_utc_us",
  "table:columns": [
    {
      "name": "pm25_atmospheric_ug_m3",
      "type": "int32",
      "unit": "ug m-3",
      "opensensor:ucum": "ug/m3",
      "opensensor:unit_label": "ug/m3",
      "opensensor:field_id": 17,
      "title": "PM2.5",
      "cf:standard_name": "mass_concentration_of_pm2p5_ambient_aerosol_particles_in_air",
      "opensensor:property": "urn:walkthru-earth:cores3:property:pm25_atmospheric_ug_m3",
      "opensensor:procedure": "pmsa003",
      "opensensor:live_key": "pm25",
      "opensensor:role": "measurement",
      "opensensor:valid_when": {"column": "pms_status", "equals": 4},
      "opensensor:settle_s": 180,
      "opensensor:aggregate": "mean",
      "opensensor:scale": "who-2021-pm25"
    },
    {
      "name": "pms_status",
      "type": "int32",
      "opensensor:role": "status",
      "opensensor:codes": [
        {"value": 0, "name": "missing"},
        {"value": 4, "name": "valid", "color_hint": "2E7D32"}
      ]
    }
  ],
  "links": []
}
```

A soil station profile uses the same shape with `opensensor:family: "soil"`, columns such as `soil_moisture_pct` (unit `%`), `soil_temperature_c` (unit `Cel`) and `soil_ec_us_cm` (unit `uS/cm`), and no scale. The app shows it with neutral colours and needs no code change.

### 4.6 Catalog layout on source.coop

```text
opensensor-space/
  catalog.json
  profiles/
    catalog.json
    <sha256>.json                    profile Collections, immutable
  stations/
    catalog.json
    <station-uuid>/
      collection.json                station Collection, describedby -> profile
      items.parquet                  stac-geoparquet mirror of the day Items
      <yyyy>/<mm>/<dd>.json          optional per-day Item JSON
  discover/
    collection.json                  H3 aggregates, replaces manifest.json
    cells/h3_res=<n>/data.parquet
```

Only stations whose owners enable CC BY 4.0 publishing appear. Geometry is always the H3 cell polygon at the owner's chosen resolution, never the phone fix.

---

## 5. App changes

### 5.1 New core model

Create the planned `:core:model` (pure `commonMain`) with `Station`, `Profile`, `Datastream`, `ObservedProperty`, `Scale`, `ScaleStep`, `ValidityGate`, `Aggregate`, `Role` and `Family`. Add `:core:catalog` to parse STAC JSON (kotlinx.serialization, unknown fields ignored), resolve a profile by digest (device opcode, then cache, then source.coop, then bundled), and validate extension versions.

Then replace the three current descriptions.

| Today | After |
| --- | --- |
| `Metrics.all`, `MetricSpec` | `Profile.datastreams` filtered by `role = measurement` |
| `Metrics.primary = PM25` | `Profile.primary.first()`, and bucket `validRows` uses each datastream's own gate |
| `LiveSample` data class | `Map<String, Double?>` keyed by live key, labelled through the profile. Unknown keys are kept, not dropped |
| `LiveProfiles.resolve` and `VOCABULARY` | Profile lookup by digest. Unknown digest shows raw keys with raw names |
| `WhoGuidelines.forProperty("pm25")` | `Datastream.scale` from the profile |
| `FeedProperty` | `Datastream` read from the discover Collection's `table:columns` |
| `DeviceCapabilities.showsParticles`, schema prefix checks | `Profile.family` and per-datastream roles |
| `pmValidRows` (Room) | Per-datastream valid counts already in `metric_bucket`. Drop the column in a schema bump |

### 5.2 Family plugins

AQ science becomes one plugin, not core logic. Move `:core:airquality` to `:family:air` with `AirMood`, the peacock hero, EPA correction (as an `opensensor:derived` method), QC rules and the Plantower batch candidate. Core only knows a `FamilyPlugin` interface with an optional hero card, derived-column methods and QC hooks. A device with no plugin still gets generic cards, charts and Discover cells.

### 5.3 Screens

- Today. Cards are generated from `Profile.primary` and the remaining measurement datastreams, grouped by category. The air family supplies the mood hero card.
- History. The default metric is `Profile.primary.first()`. Axes use the datastream's scale when there is one, otherwise `opensensor:range` or the data range.
- Devices. The row glance shows the first primary datastream with its scale colour or a neutral chip.
- Discover. The WHO legend becomes "the property's scale when the profile has one". Category chips already come from data.

### 5.4 Rename map

Keep (wire, store or persisted contract).

| Name | Why |
| --- | --- |
| `applicationId` `earth.walkthru.aqsync` | Changing it creates a new Play listing |
| BLE base UUID, `AQ-` advert prefix, `_aqsync._tcp`, `aq-XXXX` LAN host | Firmware wire contract |
| LIVE keys, Parquet column names, footer keys, Hive paths | Firmware file contract |
| `aq-sync.db`, `aqsync_lan_token_v1`, `aqblocks1` | Persisted on phones. Rename only with a migration, low value |

Rename (app-only).

| Current | Proposed |
| --- | --- |
| Kotlin packages `earth.walkthru.aqsync.*` in core modules | `earth.walkthru.opensensor.*` (features already use it) |
| `:core:airquality` | `:family:air` |
| `:core:history` metric registry | moves to `:core:model` and `:core:catalog` |
| `AqGatt`, `AqUuids`, `AqJson` | `DeviceGatt`, `DeviceUuids`, `WireJson` (values unchanged) |
| `AirMood`, `NowReading.pm25`, `TodaySoFar.pm25Mean`, `TodayCard.NOW_AIR` | `Reading`, `NowReading.primary`, `TodaySoFar.primaryMean`, `TodayCard.NOW` |
| `MetricSpec`, `MetricKind` | `Datastream`, `Role` plus `Family` |
| `pm25Label`, `formatPm` | `datastreamLabel`, `formatValue` |
| AQ string keys (`mood_*`, `pm25_*`, `discover_property_pm10`) | Move into the air family module, or into profiles as `title` |

Term choice. Use `Station` for the device as the user sees it, `Datastream` for one measured column, and `Profile` for a dictionary digest. "Data feed" stays the name of the public Discover source. These follow SensorThings and the existing `devices-and-discovery.md` vocabulary.

---

## 6. Firmware changes (later, backward compatible)

1. Add a sibling table per board, `telemetry_semantics.inc`, keyed by column name, with UCUM code, `cf:standard_name`, role, aggregate, range and sensor model. A static assert keeps it the same length and order as `telemetry_fields.inc`, which stays byte identical so the released `dict` digest survives.
2. Generate the descriptor on the host. `tools/telemetry_contract_fixture.cpp:59-77` already compiles `kFields` and prints dictionary JSON, and `tools/test_telemetry_contract.py:160-200` runs it. Extend it to join the semantics table and emit the STAC Collection, then embed the minified bytes as a generated `const char[]` with its `desc` digest.
3. Add opcode `0x11`, frame `0x4F`, the `desc` key in `info` and the two footer keys from section 4.3. Add a test that a row with every LIVE key valid at its widest real value, including a real `utc`, stays under 480 bytes.
4. Point `dictionary_uri` at a pinned address (git tag or the immutable source.coop profile) instead of the `main` blob.
5. Shared-code generalisation for non-AQ boards. Make `sample_interval_ms` and `row_group_rows_max` footer values come from config, replace the `%.1f` LIVE format with per-field precision (a wire change, so it needs a `dict` bump and app support first), make the BLE name prefix a board constant (keep `AQ-` for existing boards), and fill `deployment_id` and `calibration_id` when provisioning supports it.
6. Firmware names that must stay. NVS namespaces `parquet` (`station`, `codec`, `boots`), `aqcfg` (`cfg`, `token`, `location`, `ntp`, `pair`) and `aqdump`, because renaming loses station identity and pairing. Also the `AQS1` LAN handshake on port 47390, mDNS TXT keys and the serial lines `PARQUET`, `AQ OWNER_PIN`, `AQ WIFI_PROFILE` and `AQ CONFIG` that host tools parse.
7. Firmware names that can be renamed, low priority. Doc labels `AQCommon` to `AQLocation`, namespaces `aqlogger` and `aqsync`, Kconfig symbols `AQ_LOGGER_*`, `AQ_LOG_PERSIST_*` and `AQ_PM_DFS_*` (update both boards' `sdkconfig.defaults` together), project names `aq_cores3` and `aq_waveshare` (these change `.bin` names in the release workflow), board components and `created_by` strings.

---

## 7. Phased plan

| Phase | Scope | Repo | Risk |
| --- | --- | --- | --- |
| P0 | Freeze the contract. JSON Schema for `opensensor:`, profile examples, this mapping, owner sign-off | aq-parquet `docs/shared/`, this repo docs | Low |
| P1 | Generate bundled profiles for all 10 known digests. Add `:core:model` and `:core:catalog`. Parity tests that profiles reproduce `Metrics.all` and `LiveProfiles` | both | Low, no UI change |
| P2 | Switch History, tiers, LIVE parsing and Discover to profiles. `LiveSample` becomes a map. Delete `MetricSpec` and `LiveProfiles` | this repo | Medium, needs OnePlus and iPhone checks |
| P3 | Generic Today and Devices. Move AQ code to `:family:air` plugin | this repo | Medium, visible UI |
| P4 | Package and type renames in one mechanical PR per module | this repo | Low, large diff |
| P5 | Firmware semantics table, descriptor opcode and `desc` digest, footer keys, pinned `dictionary_uri`, shared-code limits | aq-parquet | Medium, bench qualification |
| P6 | Publish STAC on source.coop. Profiles, station Collections with stac-geoparquet mirrors, Discover Collection replacing `manifest.json` | host tooling | Low |
| P7 | First non-AQ test board (for example an ESP32-S3 N16R8 adapter with a soil or weather sensor) rendered with zero app code | both | Proof of the goal |

---

## Tracking

Mobile epic #182 with #183 (core model), #184 (profile resolver and bundled profiles), #185 (LIVE map), #186 (History and tiers), #187 (Today and Devices), #188 (air family plugin), #189 (Discover), #190 (STAC through daily files), #191 (device descriptor fetch) and #192 (renames). Firmware epic walkthru-earth/aq-parquet#83 with #84 to #93 there. The extension schema and examples are in walkthru-earth/stac-opensensor. The firmware audit and review are under `docs/shared/stac-contract/` there.

---

## 8. Open questions for the owner

1. Item granularity. Per day from the phone's reconciled files (recommended here) or per dated device file (the firmware audit). Both reviews agree unsynced files never become Items.
2. Collection shape. This report proposes a profile Collection per digest plus a Collection per station. The firmware audit prefers one Collection per board schema with the station as an Item property (`opensensor:station_id`). The second is simpler for Discover, the first is simpler for per-station licensing and extents.
3. Do health scales live only in published profiles (recommended), or can firmware ship them too.
4. Name of the custom extension prefix. `opensensor:` is drafted as v0.1.0 in walkthru-earth/stac-opensensor.
5. Is `Station`, `Datastream`, `Profile` acceptable as the type vocabulary, or do you prefer `Feed` for Datastream.
6. Should the `GET_DESCRIPTOR` opcode be built, or are bundled plus published profiles enough for now.
7. Which scale ships first for PM, WHO 2021 (current app default) or US EPA AQI, and who reviews the Arabic labels.
8. Units. UDUNITS in `unit` plus UCUM in `opensensor:ucum` (proposed), or one system only.
9. Public H3 resolution. Which resolution may appear in public Items.
10. Is `pms_status 6` (#39) planned before or after the descriptor, since it moves `dict`.
11. For LoRa-only boards (PR #68), is a catalog lookup by `desc` hash enough, since they cannot use the opcode.
12. Accra demo timing. P1 and P2 change internals only and could land before the demo. P3 changes visible screens and is safer after.

---

## Sources

- [STAC 1.1.0 release notes (Cloud-Native Geospatial)](https://cloudnativegeo.org/blog/2024/09/stac-1.1.0-released/)
- [STAC common metadata (bands, unit, data_type, statistics)](https://github.com/radiantearth/stac-spec/blob/master/commons/common-metadata.md)
- [STAC best practices](https://github.com/radiantearth/stac-spec/blob/master/best-practices.md)
- [STAC extensions registry](https://github.com/stac-extensions/stac-extensions.github.io)
- [Table extension v1.3.0](https://github.com/stac-extensions/table)
- [Datacube extension v2.3.0](https://github.com/stac-extensions/datacube)
- [CF extension v1.0.0](https://github.com/stac-extensions/cf)
- [Time Series extension (archived)](https://github.com/stac-extensions/timeseries)
- [stac-geoparquet specification](https://github.com/radiantearth/stac-geoparquet-spec/blob/main/stac-geoparquet-spec.md)
- [stac-insitu (OGC Moving Features over STAC)](https://github.com/IAOCEA/stac-insitu)
- [SensorThings API overview](https://en.wikipedia.org/wiki/SensorThings_API)
- [OGC API workshop, SensorThings deep dive](https://ogcapi-workshop.ogc.org/api-deep-dive/sensorthings/)
- [ds2stac SensorThings notes](https://ds2stac.readthedocs.io/en/latest/data_server/SensorThingsapi.html)
- [OGC API Connected Systems Part 2 (23-002)](https://docs.ogc.org/is/23-002/23-002.pdf)
- [SensorML 3.0 (23-000)](https://docs.ogc.org/is/23-000/23-000.html)
- [SWE Common 3.0 (24-014)](https://docs.ogc.org/is/24-014/24-014.html)
- [Connected Systems datastream examples](https://schemas.opengis.net/ogcapi/connected-systems/part2/1.0/openapi/examples/datastreams/)
- [CF standard name for PM2.5 (NERC vocabulary)](https://vocab.nerc.ac.uk/standard_name/mass_concentration_of_pm2p5_ambient_aerosol_particles_in_air)
- [GeoParquet STAC generation guide](https://geoparquet.org/geoparquet-io/guide/stac)
- [GLEN STAC catalog on source.coop](https://source.coop/cboettig/glen)
