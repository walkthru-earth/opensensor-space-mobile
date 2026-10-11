# Discover live feed from OpenAQ aggregates, design

Date 2026-10-12. Owner approved the design in chat on 2026-10-12 (map pill toggle, 24 h and 7 d only).
Branch `ux/openaq-live`, forked from `ux/stations-redesign` at dbea11ad.

## Goal

Discover can switch between the bundled sample and real data. Real data is the OpenAQ
aggregates collection on source.coop, read on the phone with the existing Parquet and HTTP
range code. No publisher change.

## Source

Root `https://data.source.coop/walkthru-earth/opensensor-space/openaq-aggregates/`.

- `manifest.json` has `as_of` (`YYYY-MM-DD`, the latest complete PM2.5 day) and `generation`.
- `cells/h3_res=<1..9>/data.parquet`, SNAPPY, row groups of 16384, min and max statistics, no
  page index. Rows sort by `h3_index` then `date`.
- Columns `h3_index` INT64, `date` DATE (station local day), `devices` INT32 (stations, may be 1),
  `<id>__n` INT32, `<id>__mean`, `<id>__min`, `<id>__max` DOUBLE, null when not measured.
- Footer key `dictionary` holds the properties (`pm25`, `pm10`, `pm1`, `no2`, `temperature`,
  `humidity`, `um003`). Only complete days (18 or more hours) are in the cells.
- HTTP serves `accept-ranges: bytes` and an ETag. Files are replaced whole on a new generation.
- Data lags OpenAQ by about 72 hours.

## Design

### Feed, `:core:publicfeed` (common code)

- `PublicFeed` gains `val windows: List<FeedWindow>`. The sample offers all three, OpenAQ offers
  `DAY` and `WEEK`. Asking OpenAQ for `LAST_HOUR` returns `CellsTable.EMPTY`.
- `ParquetFeedReader` also reads the daily schema. When `updated` is absent and `date` is present,
  each row keeps its date. The existing hourly schema is unchanged.
- `DailyCellFold` (pure) turns daily rows of one file into one `CellRow` per `h3_index` for a
  window, given `asOf` (a local date):
  - Only days in the 7 days ending at `asOf` (asOf minus 6 through asOf) count.
  - `DAY`: per property, the newest day in range that has that property's mean. Its `n`, `mean`,
    `min`, `max` are copied. A cell with no property in range is dropped.
  - `WEEK`: per property, mean of the daily means in range, min of mins, max of maxes, `n` the
    largest daily `n`. Days without the property are skipped, never counted as zero.
  - `devices` is the largest daily `devices` among the days used. `updatedEpochMs` is the end of
    the newest day used, as UTC midnight after that date.
  - Null stays null. Never fabricate a value.
- `OpenAqAggregatesFeed(engine: HttpRangeEngine, ...)`: `isSample = false`. `latest()` reads
  `manifest.json` and returns a `FeedPartition(date = as_of, hour = 0)`, cached for 5 minutes.
  `cells(partition, window, res, ranges)` reads `cells/h3_res=<res>/data.parquet` through
  `HttpRangeSource` with the existing `FooterCache` and range pruning on `h3_index`, then folds.
  Resolution stays 2 to 7 from zoom. Errors propagate as exceptions (the controller decides).
- `:core:publicfeed` depends on `:core:net` for `HttpRangeEngine` and `HttpRangeSource`.
- KDoc of `CellRow.devices` says 3 or more for opensensor cells, and 1 or more for public
  licensed stations such as OpenAQ.

### Source choice, `:core:settings` and `feature/discover`

- A saved setting `discoverSource`, values `SAMPLE` or `OPENAQ`, default `SAMPLE`.
- `DiscoverController` holds both feeds. The active feed is OPENAQ only when the saved source is
  OPENAQ and "Show public sensors" is on. Switching clears rows, selection, window reads and
  failures, then reloads the viewport.
- Windows on screen come from the active feed's `windows`. In real mode the 1 h chip is hidden and
  the mood strip shows two blocks (7 d, 24 h). A selected `LAST_HOUR` window moves to `DAY`.
- A failed real load sets an error state. The map stays in real mode with a short message and a
  Retry action. It never falls back to the sample silently.

### UI

- The "Sample data" pill becomes a compact two-state toggle, Sample or Real. In real mode its
  label names OpenAQ and the data date, for example "OpenAQ, data to 8 Oct". With "Show public
  sensors" off, the toggle is disabled and shows Sample.
- Map attribution adds "Data OpenAQ" linking to https://docs.openaq.org/resources/licenses
  while real mode is on.
- Copy, EN and AR: no em dashes or long dashes, no colons, no semicolons. Simple level never
  names WHO.

### Wiring

- Android `DiscoverRoute` and iOS `AppRoot` build `OpenAqAggregatesFeed` with the platform
  `HttpRangeEngine` (OkHttp on Android, NSURLSession on iOS) and pass both feeds.

## Privacy and network

The `AGENTS.md` boundary already allows anonymous, read-only GETs of the public discover feed,
behind "Show public sensors". No identifiers, cookies or analytics. Coordinates are not read,
only H3 cells.

## Tests

- A small daily-layout fixture written by `tools/discover-fixture` (SNAPPY, same columns and
  dictionary), checked into test resources.
- `DailyCellFold` edges: gap days, nulls, one station, a day outside the 7 days, property only on
  some days, `asOf` boundary.
- Reader over the fixture through `MemoryRangeSource`. Feed over a fake `HttpRangeEngine` serving
  the fixture and a manifest.
- Controller: switching source, disabled when public sensors is off, LAST_HOUR moves to DAY,
  error state and retry.
- The live URL is not in the gate. A manual phone run records dated evidence in `docs/status.md`.

## Docs

`docs/devices-and-discovery.md` names `openaq-aggregates` as the current live feed and records
the daily schema and the fold. `docs/architecture.md` adds the `:core:publicfeed` to `:core:net`
edge.
