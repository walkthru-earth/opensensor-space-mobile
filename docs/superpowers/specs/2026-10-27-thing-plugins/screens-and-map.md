# Screens and map for many Things

Part of [Thing plugins](README.md). Proposal, 2026-10-27.

## 1. Today

- Discover is already family neutral. Property chips rank from the feed dictionary (`DiscoverController.kt`), a published scale wins, otherwise a viridis percentile ramp is used (`CellScale.kt`). All cells come from one GeoJSON source with fill, outline and selected layers (`DiscoverMap.kt`), capped at 1,024 cells (`ViewportCover.kt:30`), redrawn after 400 ms of camera rest. maplibre-compose 0.20.0.
- `AppProfile` carries `family`, `primary`, `hero`, `display_step`, `scales` and `hidden`, and `Thing.primary` respects the profile order.
- Hardcoded to air.
  - Only `GENERIC` and `AIR` families exist (`Vocabulary.kt`).
  - The device row reads `lastDayPm25Mean`, `AirMood` and `Metrics.PM25` (`feature/devices/.../DeviceRows.kt:222`, `DeviceModels.kt:60`).
  - The Today hero is PM2.5 only (`feature/today/.../TodayCards.kt`). Mood words are tied to WHO steps.
  - `profile.hero` is parsed (`ModelJson.kt:91`) and read by no screen.
- Stations cards and the shared Station page are spec only ([2026-10-12-stations-ux-design.md](../2026-10-12-stations-ux-design.md) slice 2).

## 2. Cards and Station page from template plus profile

- `ThingCard(thing)` shows the profile label, the `primary[0]` value at `display_step`, the unit, the scale step name beside its colour, freshness and one action.
- The Station page has a hero, a Day, Week and Month strip for `primary`, and rows that unlock with the detail level. All of it reads `ThingDatastream`.
- `lastDayPm25Mean` becomes `lastDayPrimaryMean` plus a property id.

Hero slot, the same on Android and iOS, in `commonMain`.

```kotlin
interface FamilyPresenter {
    @Composable fun Hero(thing: Thing, reading: ReadingState, level: DetailLevel)
}

@Composable
fun StationScreen(
    state: StationState,
    hero: @Composable () -> Unit = { GenericMetricHero(state) },
    extras: @Composable ColumnScope.() -> Unit = {},
)
```

- A `HeroRegistry` is keyed by `profile.hero`. The air entry is the current peacock hero with moods. Every other family gets `GenericMetricHero`.
- A plugin adds a composable only when data cannot express it, for example a wind rose or a water level gauge.
- Presenters never touch transport.

## 3. One map for own and public Things

Layers, bottom to top.
1. Public cells, the existing source.
2. Own fixed Things as a point source at each cell centre, `cluster = true` with `clusterRadius` and `clusterMaxZoom` ([GeoJsonOptions](https://maplibre.org/maplibre-compose/api/lib/maplibre-compose/dev.sargunv.maplibrecompose.core.source/-geo-json-options/index.html)). Badges show `point_count`.
3. Own moving Thing track, owner only. Stored on the phone as `(res 8 cell, hour, mean)` rows, drawn as a fill layer with its own id plus a line through cell centres. Never published (stac-opensensor#2 use case 3). If ever published, the res 7 trimmed copy (section B).
4. Selection.

- Own Things never mix into the public source, so privacy holds by construction. A test asserts the public GeoJSON never carries owned cells.
- Public cells need no clustering, since coarser H3 resolution with zoom already does that.
- Following Meshtastic, each source is keyed by Thing id plus last update, and its GeoJSON is rebuilt only when keys change, off the main thread, after the 400 ms rest.
- Property picker. The union of `ObservedProperty` ids from own templates and the feed dictionary, ranked by count in view. Group chips by family only when there is more than one. Cap at 6 ranked chips plus More. The first chip stays a stable default.
- Filters live in one compact floating surface (Family, Property) that grows on touch and folds back. Cells that do not measure the property keep the outline only style.
- Legends come from `StepScale` steps and names, or from the percentile ramp labelled relative to view. Always show the scale source name. Colour follows the scale, never the family. Brand blue stays for selection only.
- Frame time needs a dated measurement on the OnePlus and an iPhone.

## 4. Accessibility, RTL and font scale

- Every coloured mark carries its step name in text. A cluster badge reads like "3 stations, worst Hazy".
- Map features have a list alternative, the existing sheet list, since canvas features are not reachable by TalkBack or VoiceOver.
- Legends and chips mirror in Arabic. Numbers and units use bidi isolation.
- Chips, legend items and card values use `FitText` with a floor at 1.0 font scale and never break mid word. Minimal copy with an info button.

## 5. Prior art

- [Home Assistant tile card](https://www.home-assistant.io/dashboards/tile/). One generic card with optional features. Copy it, skip the YAML configuration.
- [openSenseMap](https://opensensemap.org). Phenomenon picker across arbitrary sensors, clustered boxes, mobile boxes as tracks. Avoid its raw coordinate tracks.
- [Sensor.Community](https://maps.sensor.community). Hex aggregation with a legend per metric and a drill down to a list.
- [Netatmo Weathermap](https://weathermap.netatmo.com). Clustered averages when zoomed out, only opted in outdoor modules shared.
- [PurpleAir](https://map.purpleair.com). Map first, no sign in. Avoid one grey for both stale and not measured.

## 6. First step

1. Build slice 2 generically with the hero slot and `GenericMetricHero`.
2. Own fixed Things on Discover as a clustered point source.
3. Moving track and family filters wait until a second family template exists in `core/model/bundled/templates/`.
