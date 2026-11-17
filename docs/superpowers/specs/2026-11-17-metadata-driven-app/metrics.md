# Metrics from metadata

Part of [Metadata-driven app](README.md). 2026-11-17. Proposal.

## 1. Target

Every metric a screen shows comes from the Thing's resolved template, the property registry and, for science, a family. Nothing in `:core` names a board, a column or a pollutant.

```mermaid
flowchart LR
  T[resolved template<br/>served, href, bundled] --> C[MetricCatalog per Thing]
  R[property registry<br/>bundled copy] --> C
  F[DerivedMetricProvider<br/>family science] --> C
  P[AppProfile overlay<br/>optional] --> C
  C --> L[LIVE]
  C --> H[History, tiers, Files]
  C --> D[Today cards, Stations]
  R --> X[Discover labels]
```

## 2. Rules

### 2.1 Catalog per Thing

- `MetricCatalog.of(thing)` builds the metrics of one Thing from its resolved template (`TemplateIndex.resolve`, the rule LIVE and History share).
- A union catalog for cross-Thing screens (Stations, Files) is a `StateFlow` that grows when `ServedTemplates` or `TemplateHrefs` adopt a template. It never shrinks while a file of that template is indexed.
- Tier keys stay the column id. `MetricSpec.inTemplate` keeps refusing a column stored in another unit or rolled up another way.
- No caller gets a default catalog. `Metrics.all` and `Metrics.default` go away.

### 2.2 Labels, units, decimals

| Field | First source | Then | Last |
| --- | --- | --- | --- |
| Label (by language) | Column `title` and its i18n | Registry label by `opensensor:property` | Prettified column name (`soil_moisture_percent` gives "Soil moisture percent") |
| Short label | Registry short label | Column `title` | Label |
| Description | Column `description` | Registry definition | none |
| Decimals | `opensensor:decimals` | Registry default decimals | `AppProfile.displayStepOf` rule (integer 0, else 1) |
| Group | `opensensor:group` | Registry group | Role |
| Display unit | `UcumDisplay.of(unit)` in `:core:units` | Firmware `unit_labels` | Raw UCUM |

- `UcumDisplay` maps the UCUM codes the templates use, `ug/m3` to µg/m³, `Cel` to °C, `[ppm]` to ppm, `%` to %, `{count}` and other annotations to an empty unit. An unknown code passes through.
- `UnitConversion` gains `Cel` beside `°C`, `℃` and `degC`, so imperial works on a template unit.
- A label from the app's own strings (`:ui:strings`) is an overlay, used only when it exists in the user's language. The PM2.5 raw and corrected labels stay app strings.

### 2.3 Scales

Lookup order for a column, first hit wins.

1. Template column `opensensor:scale` (a scale id).
2. Registry default scale for its `opensensor:property` URI.
3. A family scale published for that property URI.
4. A family scale published for its `cf:standard_name`.
5. None, neutral colour.

- A scale id or URI no installed family publishes leaves the column neutral. Nothing is guessed.
- `HealthScale.propertyId` becomes the property URI. `AirFamily.scales` keys WHO PM2.5 and PM10 by their URIs. The URIs live in `:family:air`, so `CoreStaysAirFreeTest` still passes.
- CO2 has no scale anywhere. It stays neutral until the registry publishes one.

### 2.4 LIVE

- `Capability.LIVE` is set for any Thing whose template resolved by a verified SHA-256, served, footer href or bundled.
- Family capabilities apply to any resolved Thing, derived profile or not.
- `plausible_range` and `valid_when` still filter LIVE values as they filter History.

### 2.5 Status column

A status column is a `qualityInformation` column with `classification:classes` that a measurement's `valid_when` references by `equals`.

| Meaning | Rule |
| --- | --- |
| Valid | The class whose value equals the gate's `equals` |
| Absent | The class with `nodata: true` |
| Other | Any other class, shown by its title, else the registry, else its prettified `name` |

- A generic `SensorState(column, value, cls, valid)` replaces `PmsStatus` for gating. LIVE hides gated values while the state is not valid. This already holds for History through `valid_when`.
- Family hints stay in the family, keyed by class `name` (`warming`, `stale`, `sensor-error`, `sleeping`, `model-mismatch`), with a generic line for any other name.
- A bitmask column (`sen6x_device_status`) shows raw at Developer only, until `classification:bitfields` is adopted.

### 2.6 Air family by property and procedure

- `AirFamily` finds its columns by `opensensor:property` URI, never by name. PM2.5 atmospheric and SEN63C PM2.5 share `P07/YUDG9DXK`. CF=1 PM2.5 has its own URN.
- The US EPA correction needs the CF=1 property, RH and a Plantower procedure (`pmsa003`, `pms5003t`). A SEN63C gets no EPA correction.
- Particle bins come from their `particle_count_gt_*` URNs.
- `AirMetrics` resolves named metrics per Thing (`AirMetrics.pm25(thing)`), not from a global catalog.
- NowCast, Today so far, the peacock hero and the PM2.5 view swap read those per-Thing lookups.

### 2.7 Discover

- The base label of a feed property is the registry label for its URI, else the feed label.
- `DiscoverLabels.propertyLabels` stays as an i18n overlay for ids the app translates. An unknown id still renders.

### 2.8 Bundled profiles

`AppProfile` becomes an optional overlay (hero id, hidden columns) on top of the template. Once the bundled templates carry the hints, the two bundled profiles shrink to `hero` and `hidden`. Labels, units, primary and scales leave them.

## 3. Work packages

Ordered. Each passes `./gradlew --no-daemon verify`. Tests are `commonTest` unless noted, so they run on the JVM and the iOS simulator.

| # | Package | Modules | Tests to add |
| --- | --- | --- | --- |
| M0 | Re-copy firmware templates with CrowPanel. Add a fake unknown board template (`acme-soil-probe`, soil moisture, a status column with classes, an unknown property URN, a `firmware` block with `live_keys`) as a test fixture only | `core/model` bundled, `.github/workflows/verify.yml` ref, `core/model` commonTest fixtures | `BundledTemplateBytesTest` pins, `FirmwareTemplatesTest`, fixture parse test |
| M1 | `UcumDisplay` and the `Cel` imperial rule | `core/units` | `UcumDisplayTest` for each unit in the three bundled templates, imperial `Cel` |
| M2 | Parse the new template fields. `Datastream` gains `title`, `description`, `decimals`, `group`, `scale`. `BoardTemplate` gains `primary` | `core/model` (`ModelJson`, `Datastream`, `BoardTemplate`) | Parse of each field, unknown fields ignored, i18n titles |
| M3 | `PropertyRegistry`, bundled byte for byte from stac-opensensor with a pinned SHA-256, lookup by URI then `cf:standard_name` | `core/model` | Pin test, lookup order, unknown URI returns null |
| M4 | `AppProfile.derive` from M1 to M3. Labels, decimals, primary, display unit. `select` merges an overlay | `core/model` | Derived profile of CrowPanel shows PM2.5 in µg/m³ with registry label. Fake board shows prettified names |
| M5 | Scales by property URI, lookup order of 2.3. `HealthScale.propertyId` holds the URI | `core/model`, `core/history` (`Metrics.of`), `family/air` (`AirFamily.scales`, `WhoGuidelines`), `ui/designsystem` (`metricLabelRes`) | CrowPanel PM2.5 gets the WHO scale, CO2 and PM4 stay neutral, CF=1 stays neutral. `CoreStaysAirFreeTest` unchanged |
| M6 | `MetricCatalog` per Thing and the union flow. Remove `Metrics.all` and `Metrics.default` defaults | `core/history` (Aggregation, FileSummary, LatestReading, LatestDay, `Metrics`), `feature/history` (`HistoryController`), `app` (`RoomHistoryTiers`, `FilesViewModel`, `ScanViewModel`), `ios/shared` | Catalog of a served template appears in the union. A tier of the fake board aggregates. History opens on `opensensor:primary` |
| M7 | LIVE for verified templates. Drop the gates at `DeviceCapabilities.kt:72` and `:82` | `core/history` | Fake board and CrowPanel get `Capability.LIVE` from a served template. An unresolved desc gets none |
| M8 | `SensorState` from `valid_when` and `classification:classes`. `PmsStatus` removed. Hints keyed by class name | `core/model` or `core/history` (generic), `family/air` (`AirMood`, `LiveQc`, `AirSurfaces`, `PlantowerBatchCandidate`), `feature/today` (`TodayCards`, `TodayText`), `app` (`MoodText`) | `sen6x_status` 4 is valid, 1 is warming. Waveshare `pms_status` 5 still reads model mismatch, CoreS3 declares only 0 to 4. Fake board status renders its class title |
| M9 | Air family by property and procedure. `AirMetrics` per Thing | `family/air`, `feature/today` (`AirNowFlows`, `TodaySoFarFlow`, `TodayCards`), `feature/history` (`HistoryScreen`), `app` (`FilesViewModel`) | CrowPanel gets NowCast and the hero, no EPA correction. CoreS3 and Waveshare keep EPA. Fake board gets no air capability |
| M10 | Discover and metric labels from the registry, app strings as overlay | `feature/discover` (`DiscoverLabels`), `ui/designsystem` (`MetricLabels`) | Unknown feed id with a registry URI shows the registry label |

### Acceptance fixtures

- CrowPanel SEN63C (bundled after M0). Today shows PM2.5 with the WHO scale, PM1, PM4, PM10, temperature, RH and CO2 in display units. LIVE works. `sen6x_status` gates PM. History opens on its primary.
- Fake unknown board (test fixture, never bundled). Served through `ServedTemplates` in the test. Every screen model builds with no app change, labels come from titles or prettified names, LIVE works, status renders, no air term appears.

## 4. Docs to update with the code

Each package updates the doc that states the old rule, in the same PR. `core/AGENTS.md` (LIVE needs a reviewed profile), `docs/protocol-quickref.md` (no LIVE for a derived profile), `docs/status.md`, `docs/device-location.md`, `docs/history-pipeline.md` and `docs/devices-and-discovery.md`.
