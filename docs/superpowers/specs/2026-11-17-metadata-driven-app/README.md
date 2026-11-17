# Metadata-driven app, any board without an app change

Design, 2026-11-17. Status is proposal. Nothing here is built. Checked against `main` at ff9ae37d.

Goal. A new board, sensor or Thing shows its readings, its LIVE view, its setup and its settings from published metadata alone, the way STAC Browser renders any catalog it has never seen. The app keeps code only for safety rules, OS services and family science.

This continues [Thing plugins](../2026-10-27-thing-plugins/README.md) (epic [#219](https://github.com/walkthru-earth/opensensor-space-mobile/issues/219)) and [the sensor-agnostic STAC contract](../2026-10-25-sensor-agnostic-stac-contract.md). Those made the data layer and the Thing kinds generic. This one does the same for labels, scales, LIVE, status, setup and settings.

| File | Concern |
| --- | --- |
| [metrics.md](metrics.md) | Runtime metric catalog, labels, units, scales, LIVE, status, air family by property, Discover |
| [setup-manifest.md](setup-manifest.md) | Setup steps and settings sections declared by the board, device-side completion, generic fields, safety floors |
| [portolan.md](portolan.md) | Later target, what Portolan wants from the published catalog |

## 1. Owner decisions, 2026-11-17

1. Display hints go into published metadata. A template column may carry `title` and `description` (other languages in `opensensor:title_i18n` and `opensensor:description_i18n`), `opensensor:decimals`, `opensensor:group` and `opensensor:scale`. A template or Collection may carry its main metric (`opensensor:primary`) and its `opensensor:scales`. stac-opensensor gets a property registry keyed by `opensensor:property` URI (label, short label, symbol, definition, group, unit, default decimals, default scale) and a scale registry, like STAC Browser's `@radiantearth/stac-fields`. This reverses the rule that the app profile never reaches published STAC.
2. LIVE shows for any template whose SHA-256 is verified. No reviewed profile is needed. Plausible range and `valid_when` still filter values.
3. Setup steps and Device settings sections are declared by the board in the template `firmware` block, generated from `board.json` and hashed with the template. The app keeps a fixed catalog of step components. The device stores which steps are done. Board-specific settings move to `SET_CONFIG` keys on a new page. Setup lives in BLE and the template, never in STAC Collections or Items. The template declares names and types, never values.
4. App safety floors the manifest can never lower. See [setup-manifest.md section 5](setup-manifest.md#5-safety-floors).
5. Order. Clean state first, then the contracts (firmware `docs/shared` and stac-opensensor), then the app. No back-compat. Firmware and app move in lockstep.
6. Portolan is a later target ([portolan.md](portolan.md)).

## 2. Already generic, keep

| Piece | Where |
| --- | --- |
| Footer reading, `OpenSensorFooter`, `FileTemplates` | `core/history` |
| Template resolution by `desc` then `dict`, served before bundled, footer href with SHA-256 | `TemplateIndex`, `BoardDescriptors`, `ServedTemplates`, `TemplateHrefs` |
| Long-format tiers, `metric_bucket` | `core/database`, `core/history` |
| LIVE values by `live_keys` | `LiveColumns`, `LiveReading` |
| Open vocabulary (`Role`, `Aggregate`, `Family`, `Capability`) | `core/model` |
| Public feed, H3 cover, per-column `opensensor:scale` in the Discover Collection | `core/publicfeed` |
| File Item and Thing Collection builders | `core/stac` |
| Thing kinds, `PublishGate` | `core/thing` |
| Generic Today hero and cards | `GenericPresenter`, `GenericMetricHero` in `feature/today` |
| Family hook | `DerivedMetricProvider`, guarded by `CoreStaysAirFreeTest` |

## 3. Hardcoded today

Paths from the repo root. Line numbers are for ff9ae37d.

| What | Where | Why it blocks a new board |
| --- | --- | --- |
| Metric catalog built from bundled templates only | [Metrics.kt:126](../../../../core/history/src/commonMain/kotlin/space/opensensor/data/history/Metrics.kt#L126) | A served or href template adds no metrics to pickers, summaries or tiers |
| `Metrics.all` as the default catalog | [Aggregation.kt:74](../../../../core/history/src/commonMain/kotlin/space/opensensor/data/history/Aggregation.kt#L74), [FileSummary.kt:57](../../../../core/history/src/commonMain/kotlin/space/opensensor/data/history/FileSummary.kt#L57), [LatestReading.kt:43](../../../../core/history/src/commonMain/kotlin/space/opensensor/data/history/LatestReading.kt#L43), [LatestDay.kt:51](../../../../core/history/src/commonMain/kotlin/space/opensensor/data/history/LatestDay.kt#L51), [RoomHistoryTiers.kt:26](../../../../app/src/main/java/space/opensensor/data/history/RoomHistoryTiers.kt#L26), [HistoryController.kt:44](../../../../feature/history/src/commonMain/kotlin/space/opensensor/history/HistoryController.kt#L44), [:129](../../../../feature/history/src/commonMain/kotlin/space/opensensor/history/HistoryController.kt#L129), [FilesViewModel.kt:87](../../../../app/src/main/java/space/opensensor/ui/files/FilesViewModel.kt#L87), [ScanViewModel.kt:67](../../../../app/src/main/java/space/opensensor/ui/scan/ScanViewModel.kt#L67), [:93](../../../../app/src/main/java/space/opensensor/ui/scan/ScanViewModel.kt#L93) | Same, every caller sees only bundled boards |
| LIVE off for a derived profile | [DeviceCapabilities.kt:72](../../../../core/history/src/commonMain/kotlin/space/opensensor/data/history/DeviceCapabilities.kt#L72), [:82](../../../../core/history/src/commonMain/kotlin/space/opensensor/data/history/DeviceCapabilities.kt#L82) | A verified unknown template has no LIVE, and `:82` also drops every family capability |
| Derived profile labels are column names, units are raw UCUM, no scale | [AppProfile.kt:63](../../../../core/model/src/commonMain/kotlin/space/opensensor/model/AppProfile.kt#L63) | `pm25_mass_ug_m3` in `ug/m3` instead of PM2.5 in µg/m³ |
| Scale chosen by column name through the profile | [Metrics.kt:160](../../../../core/history/src/commonMain/kotlin/space/opensensor/data/history/Metrics.kt#L160) | A new board's PM2.5 column gets no WHO scale |
| Air family keyed by PMS column names | [AirFamily.kt:49](../../../../family/air/src/commonMain/kotlin/space/opensensor/family/air/AirFamily.kt#L49), [:59](../../../../family/air/src/commonMain/kotlin/space/opensensor/family/air/AirFamily.kt#L59), [AirMetrics.kt:15](../../../../family/air/src/commonMain/kotlin/space/opensensor/family/air/AirMetrics.kt#L15), [:39](../../../../family/air/src/commonMain/kotlin/space/opensensor/family/air/AirMetrics.kt#L39) | SEN63C columns (`pm25_mass_ug_m3`, `sen6x_status`) match nothing |
| `PmsStatus` enum with fixed codes | [AirMetrics.kt:43](../../../../family/air/src/commonMain/kotlin/space/opensensor/family/air/AirMetrics.kt#L43) | A status column with other classes cannot drive the hero |
| Today cards on PMS columns | [TodayCards.kt:215](../../../../feature/today/src/commonMain/kotlin/space/opensensor/today/TodayCards.kt#L215), [:337](../../../../feature/today/src/commonMain/kotlin/space/opensensor/today/TodayCards.kt#L337), [:674](../../../../feature/today/src/commonMain/kotlin/space/opensensor/today/TodayCards.kt#L674), [:704](../../../../feature/today/src/commonMain/kotlin/space/opensensor/today/TodayCards.kt#L704) | |
| NowCast and Today so far on PM2.5 ids | [AirNowFlows.kt:80](../../../../feature/today/src/commonMain/kotlin/space/opensensor/today/AirNowFlows.kt#L80), [TodaySoFarFlow.kt:53](../../../../feature/today/src/commonMain/kotlin/space/opensensor/today/TodaySoFarFlow.kt#L53) | |
| Status text per `PmsStatus` | [TodayText.kt:202](../../../../feature/today/src/commonMain/kotlin/space/opensensor/today/TodayText.kt#L202), [MoodText.kt:15](../../../../app/src/main/java/space/opensensor/ui/components/MoodText.kt#L15) | |
| PM2.5 view swap in History and Files | [HistoryScreen.kt:347](../../../../feature/history/src/commonMain/kotlin/space/opensensor/history/HistoryScreen.kt#L347), [FilesViewModel.kt:109](../../../../app/src/main/java/space/opensensor/ui/files/FilesViewModel.kt#L109) | |
| Discover labels by feed id | [DiscoverLabels.kt:40](../../../../feature/discover/src/commonMain/kotlin/space/opensensor/discover/DiscoverLabels.kt#L40) | A new feed property shows its raw label |
| No UCUM display mapping, and `Cel` has no imperial rule | [Units.kt:61](../../../../core/units/src/commonMain/kotlin/space/opensensor/units/Units.kt#L61) | A derived metric in `Cel` stays in Celsius under imperial |
| Setup steps fixed per kind | [SetupStep.kt:95](../../../../feature/setup/src/commonMain/kotlin/space/opensensor/setup/SetupStep.kt#L95) | |
| Every `Fields` step renders the Wi-Fi form | [SetupScreen.kt:179](../../../../feature/setup/src/commonMain/kotlin/space/opensensor/setup/SetupScreen.kt#L179), [:192](../../../../feature/setup/src/commonMain/kotlin/space/opensensor/setup/SetupScreen.kt#L192) | |
| Setup pending held on the phone | [SettingsKeys.kt:47](../../../../core/settings/src/commonMain/kotlin/space/opensensor/settings/SettingsKeys.kt#L47), [SetupController.kt:199](../../../../feature/setup/src/commonMain/kotlin/space/opensensor/setup/SetupController.kt#L199), [:271](../../../../feature/setup/src/commonMain/kotlin/space/opensensor/setup/SetupController.kt#L271) | A second phone does not know |
| `SET_CONFIG` allowlist | [ConfigField.kt:117](../../../../core/protocol/src/commonMain/kotlin/space/opensensor/data/protocol/ConfigField.kt#L117), merge at [:169](../../../../core/protocol/src/commonMain/kotlin/space/opensensor/data/protocol/ConfigField.kt#L169) | A board key the app does not list is dropped |
| Device settings order | [DeviceConfigSection.kt:207](../../../../feature/device/src/commonMain/kotlin/space/opensensor/device/DeviceConfigSection.kt#L207) | |
| CrowPanel template not bundled | [core/model/bundled/templates](../../../../core/model/bundled/templates) | Firmware `docs/shared/templates` has it since #182. Open PR [#321](https://github.com/walkthru-earth/opensensor-space-mobile/pull/321) bundles it and moves the pin to e2eb30b |

## 4. Contract dependencies

Opened in parallel, 2026-11-17. The app work starts after both merge.

| Repo | PR | Carries |
| --- | --- | --- |
| opensensor-space-firmware | [#183](https://github.com/walkthru-earth/opensensor-space-firmware/pull/183) | `docs/shared` changes. Decision 0003, LIVE for any verified template, planned `primary`, `setup`, `settings` and typed `config_keys` in the `firmware` block, `thing.setup_ack` and `setup_v` on page 1, a board settings page after page 6, CrowPanel in the templates table |
| stac-opensensor | [#16](https://github.com/walkthru-earth/stac-opensensor/pull/16) | Property and scale registries, display hint fields and their fallback order, `classification:bitfields`, CrowPanel template and example Thing, `PROFILE.md` reversal |

## 5. Order

1. Clean state. Open PRs land or close, docs match code.
2. Contracts. The two PRs above, reviewed together.
3. App. [metrics.md](metrics.md) work packages M0 to M10, then [setup-manifest.md](setup-manifest.md) S1 to S8. M0 to M3 and S1 can run in parallel.

Each package passes `./gradlew --no-daemon verify` on its own. A host or simulator pass does not qualify a phone. The CrowPanel needs a dated phone run in `docs/status.md` before it counts as supported.

## 6. Open points for the owner

- The CrowPanel screen colours PM2.5 with US EPA 2024 AQI bands (`firmware/esp-idf-crowpanel-epaper/display/air_band.h`). The app and the spec use WHO 2021. Flagged only, no firmware change here.
- No CO2 scale exists. CO2 stays neutral until the scale registry gets one (a TODO in stac-opensensor #16).
- `sen6x_device_status` has no `classification:bitfields` yet. stac-opensensor #16 defines the field, and the firmware template must add the bits before the app can name them.
- The PM4 property is a local URN with no CF name. Its label comes from the registry, never a guessed CF name.
- The `setup_v` rule and the board settings page number are open in firmware #183.
