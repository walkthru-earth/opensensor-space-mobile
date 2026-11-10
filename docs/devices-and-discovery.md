# Devices and discovery

**Scope:** how the app supports any sensor family (air quality is one of many), and how a new user
sees public sensor data near them on first open through a privacy-preserving H3 map.
**Code:** `core/model/`, `core/thing/`, `core/publicfeed/`, `ui/map/`, `feature/discover/`, `tools/discover-fixture/`. **Related:**
[cross-platform-guide.md](cross-platform-guide.md),
`../opensensor-space-firmware/docs/shared/table-and-observation-model.md`.

## Product direction

opensensor.space is a home for many kinds of open hardware: air, weather, water, soil, noise,
energy. The app must treat a new sensor family as data (a board template and an app profile), not as
new screens. And the app must be useful before someone owns a device. Like the first run of
Google Home, Eufy or Apple Weather, opening the app shows something real right away. Here that
means readings from public sensors nearby, shown as hexagon cells, never as exact points.

## Part 1. Sensor and hardware agnostic

### Model

The model follows the observation model of the firmware (`table-and-observation-model.md`,
SensorThings concepts) and the stac-opensensor v0.1.0 profile, so the phone, the firmware and a
host agree on names. The types live in `:core:model` unless the table says otherwise.

| Type | Meaning |
| --- | --- |
| `BoardTemplate` | What one board measures, parsed from the template the firmware publishes. Keyed by `TemplateKey` (`dict` and an optional `desc`) and resolved by `TemplateIndex`, a template a board served first, then a bundled one |
| `Datastream` | One column of a template, or one phone-derived stream. It names its `ObservedProperty`, its `Sensor` (procedure and board), unit, aggregate, role, validity gate and settle time |
| `AppProfile` | The app's presentation choices for one `dict` (primary columns, hero id, labels, display steps and units, scale ids, hidden columns). Bundled, or derived from the template when none is bundled |
| `Thing` | `Thing.join` of a template, a profile and the Thing Collection. A Thing has a persistent UUID, and the MAC address is separate |
| `Capability` | An open id of what a connected Thing can do (`LIVE`, `CONFIG`, `SENSOR_IDENTITY`). `DeviceCapabilities` in `:core:history` builds the set from `INFO` and the resolved Thing |
| `ThingKind` (`:core:thing`) | One kind of Thing. It says how its Things are recognized (`Evidence`), where their data comes from (`DataSource`) and whether it may ever be published (`PublishPolicy`). `OpenSensorBoardKind` is the one kind, and each app lists its kinds in `ThingKinds` |
| `Scale`, `HealthScale` | An optional interpretation of a value. A `Scale` is parsed from a Collection's `opensensor:scales`. A `HealthScale` is one a family publishes through `DerivedMetricProvider.scales`, such as `WhoGuidelines` in `:family:air` |
| `DerivedMetricProvider` | The seam a sensor family plugs into. It gives derived metrics, health scales, extra capabilities and a row check for a template |

Rules.

1. **The template comes from the device.** The firmware's published board template is the
   source of property identity, unit, procedure and time semantics. The app bundles a copy of
   the known templates, takes one a board serves over `GET_DESCRIPTOR`, and can fetch one from a
   file's footer href (`TemplateHrefs`). Each is used only when its SHA-256 is the expected desc.
   The app never invents a unit or a band. The
   Settings **Units** choice only relabels a known SI unit for display (`:core:units`, for example
   °C as °F). The feed, the percentile scale and every statistic stay on the template's unit.
2. **Unknown is fine.** A Thing keeps every datastream of its template whatever its role, and a
   datastream without a profile label shows its template title or column name and no scale. A
   file whose template nothing resolves keeps its rows and shows no metrics. Nothing is coerced
   or given a default.
3. **Scales are optional and scientific.** A scale names its source (for example WHO 2021).
   PM2.5 and PM10 use the WHO 2021 Air Quality Guidelines (`:family:air`,
   `WhoGuidelines`), six fixed steps from "meets the guideline" through interim targets 4 to 1
   to "above all targets", 24-hour means, in one colour-blind checked palette. A fixed step
   means a colour says the same thing in every place, viewport and window. Properties without
   a published scale (CO₂, temperature, humidity) use a neutral sequential colour ramp stretched
   over the cells in view, and say so. Colour never carries a step alone, its name is shown
   beside the value. Brand colours are never used to judge a value, and WHO colours are used
   only to judge values.
4. **Capabilities, not schema strings.** A screen reads `DeviceCapabilities` (`configurable`,
   `sensorIdentity` and the `Capability` set). Only `ThingKind.recognize` and
   `DeviceCapabilities` read adverts, `INFO` or the protocol version. No screen checks a schema
   or dictionary string.
5. **A new board is a template.** A board line is one template, plus an `AppProfile` when the
   derived default is not enough. A new source of Things is one `ThingKind`. Neither needs a
   change to the sync engine, the screens or the design system.
6. **Keep wire names.** Opcodes, UUIDs and archive
   paths stay. New shared types use generic names (`Reading`, `GenericMetricHero`), never `Air*`.

### Adding a device family

1. The firmware repository documents the family's wire and measurement contract in
   `../opensensor-space-firmware/docs/shared/` (or its own contract repo).
2. Mirror the firmware's published board templates (opensensor-space-firmware `docs/shared/templates`, one
   `<board>/<dict>.json` per board plus `index.json` and `README.md`) into
   `core/model/bundled/templates/`, byte for byte, with
   `rsync -a --delete ../opensensor-space-firmware/docs/shared/templates/ core/model/bundled/templates/` from the
   repo root. Set the firmware checkout `ref` in `.github/workflows/verify.yml` to the commit copied,
   so CI checks the copy against it with `FirmwareTemplatesTest`. If the derived default is not enough, add an
   `AppProfile` for that `dict` under `core/model/bundled/profiles/`. `BundledCatalog` reads the
   templates through `index.json`, which gives each its `desc`. `BundledTemplateBytesTest` checks each
   file's SHA-256 against its `index.json` desc and pins the full SHA-256 of every copied json file,
   so update its `pinned` map with the copy. `BundledCatalogTest` checks the profiles. The template
   links point at fixed firmware commits. The app trusts only the digests, and follows a link only
   through `TemplateHrefs`, for a file whose desc nothing on the phone resolves.
   A board whose firmware serves its template over `GET_DESCRIPTOR` works without a bundled one,
   the app fetches and caches it on connect (`BoardDescriptors`). It then renders with a profile
   derived from that template. A synced file whose footer `template.sha256` differs from the cached
   template's full digest evicts it, and the next connect fetches it again.
3. If it uses a new transport, follow "Adding a transport" in the cross-platform guide.
4. Optional. A family may provide a `FamilyPresenter` with its own hero, registered in
   `HeroRegistry` under the profile `hero` id (`air-mood` is the air-quality mood face). Without
   one, `GenericMetricHero` shows the first `primary` reading and a metrics card lists the rest.

## Part 2. Discover public sensors near you

Discover reads the bundled sample and the OpenAQ aggregates. The opensensor publisher feed, the
public device list and device page, per-device sharing and the Offline map download are not
built. Their rules below are the constraints for when they land. Phone evidence is in
[status.md](status.md).

### Decisions at a glance

| # | Decision | Section |
| --- | --- | --- |
| 1 | Map first. No sign-in, no onboarding wall, no permission prompt at launch | First screen |
| 2 | One non-modal bottom sheet with a peek and an expanded height. It never stacks a second sheet | First screen |
| 3 | Public values are hourly means, published at least one hour late. The time slider offers Last hour, 24 hours and 7 days, never "now" | Privacy rules |
| 4 | The crowd rule of three applies to every published number, not only the device list, with a differencing guard on parents | Privacy rules |
| 5 | Only outdoor devices can be published. No accounts, no passkeys. Each shared device gets its own anonymous key made on the phone, and its public ID and generated name come from that key, never free text | Privacy rules |
| 6 | The phone reads the same Parquet files as the website `/indices` explorer, one plain file per H3 resolution, through HTTP range reads and the repo's own portable Parquet reader. No JSON side feed | Public feed |
| 7 | `maplibre-compose` is the renderer, pinned as `maplibreCompose` in `gradle/libs.versions.toml` | Map technology |
| 8 | H3 runs on the phone (files carry no geometry). Viewport cover of at most 1,024 cells, one GeoJSON source, redrawn after 400 ms of camera rest | Map technology |
| 9 | An online Protomaps PMTiles basemap, an optional "Offline map" download in the app Settings (nothing bundled), and "© OpenStreetMap" always reachable | Map technology, Compliance |
| 10 | A "Near me" button asks for approximate location only when tapped. Precise location is never requested | Centring |
| 11 | Shared data is published under CC BY 4.0 and cannot be withdrawn. "Stop sharing" takes effect from the next UTC day | Privacy rules, Compliance |
| 12 | Hosting is source.coop (Radiant Earth partnership, AWS Open Data). The phone never holds source.coop write credentials | Public feed, Compliance |

### First screen at the Simple level

Zero taps shows the map, the metric chips, the time slider, the legend, the Sample and Real
toggle, and the sheet at peek height. Nothing else. Every explanation sits behind
an info button.

- **Top bar.** A search field ("Search places") and an "Add your sensor" icon button with a
  sensor icon. It is the only place on Discover that starts pairing, apart from the first-run card.
- **Metric chips.** One row. The chips are built from the properties the visible cells actually
  report, ranked by how many visible cells report them, so the row adapts to any sensor family.
  At most three property chips show, then a "More" chip opens the sheet with every
  property grouped by the category the feed dictionary gives it (Air, Weather, Water, Soil, Noise,
  Energy). Choosing one from "More" moves it into the row. The last choice is remembered. Every
  property and category id the app knows is named in the app's language (`DiscoverLabels.kt`),
  on chips, legends, the sheet and what screen readers say. An id the app does not know shows
  the column title of the discover Collection, else the feed's own label.
- **Time slider.** A 48 dp pill on the left edge of the map, not in the chip row. At rest it
  shows only the chosen window. A touch grows it into one snap stop per window the feed on
  screen offers, 1h at the top, then 24h and 7d (Real has no 1h stop, and a 1h choice moves to
  24h on screen while the saved window stays), with a tinted thumb that follows a vertical drag (a haptic tick at each stop).
  Each stop is a 48 dp target. Tap a stop to pick it, which folds the pill. It stays open until a
  window is picked, the end of a drag picks the nearest stop, and tapping the chosen stop folds it
  with no change. Screen readers get one button named Time window, its state is the full window
  name, a double tap opens or folds it, and its actions pick the others. The chip row holds metrics only.
- **Legend (Detailed and Developer, #189).** Bottom left, just above the sheet, a floating card with the
  label and unit. The legend follows the scale the feed publishes for the property
  (`opensensor:scale` on its `__mean` column, see the discover Collection below), never the
  property id. A step scale shows one discrete, equal swatch per step (not a gradient), in the
  scale's `color_hint` colours, with its edges under the borders and the guideline border marked
  with an ink tick. Steps without colours take an even viridis ramp. The PM2.5 and PM10 scales
  are the WHO 2021 Air Quality Guidelines (PM2.5 15, 25, 37.5, 50, 75, PM10 45, 50, 75, 100, 150,
  µg/m³, never converted). A WHO scale, one citing the WHO guidelines with six steps and an open last step, keeps the
  "WHO" mark and a caption naming the averaging, "WHO 24-hour guideline" in the 24 hour window
  and "Compared with the WHO 24-hour guideline" in the 1 hour and 7 day windows (owner decision,
  WHO colours in every window). Its info button explains the steps and cites "WHO global air
  quality guidelines, 2021". Any other step scale is titled "Published scale", marks its
  guideline "Guideline" and names its steps from the scale's own labels in the app's language.
  A property without a scale, PM2.5 included, keeps the neutral viridis ramp stretched from the
  5th to the 95th percentile of the cells in view, as the website does, shows those two values
  and says "No health scale published". Practice followed, Mazama Science "Qualitative display
  of air quality data", the South Coast AQMD accessible AQI scale, and the EPA Fire and Smoke
  map (`CellScale` and `StepScale` in `:core:publicfeed`).
- **Legend size.** Both legends are at most 270 dp wide. They take all the width but the one
  48 dp column of zoom and Near me beside them, and the attribution sits on its own line below,
  so it never narrows them.
- **Legend (Simple).** For a WHO scale the floating card shows the six mood
  words with their colours, titled with the property, and no numbers. Its info sheet explains
  the moods without naming the guideline body and points to Detailed in Settings. A property
  without a health scale keeps the gradient legend. The card is one accessibility node that
  reads the title and the six words.
- **In view at Simple.** The spread chart becomes a mood strip, one block per mood
  as wide as its number of areas, no axis, with its mood word under it. Tapping a block filters the map and the list to that
  mood, tapping it again or tapping the map lets go, and changing the property clears it too.
  A property without a health scale shows no chart at Simple. List rows name the mood word.
  The strip is one accessibility node, and its touch targets are 48 dp when tappable.
- **Cell sheet at Simple.** Peacock and mood word beside the 24 hour mean, or "n/a"
  when that window has no value, one plain line, then a three block mood strip for 7 days, 24
  hours and the last hour, with a visible caption under each block. Selecting a cell reads the
  other windows for that one cell from the same feed the map uses
  (`DiscoverUiState.selectedWindows`). Real has two blocks, 7 days and 24 hours. Until the 24 hour row is read the sheet says "Loading",
  with no mood and no value. A failed window read is tried again after 1 s and 2 s. If it still
  fails it stays unread (`DiscoverUiState.failedWindows`), the sheet says "Not available right now"
  with a Retry button, and the next pan or Retry reads it again. A failure never reads as "Not
  measured". Loading and that line are polite live regions. The Real feed sits behind the Show public sensors switch. Then sensors,
  freshness and the other measurements as plain value rows, all from the 24 hour row. A property
  without a health scale shows only its number. The mood
  words live only in `:ui:designsystem`.
- **Cell styles.** Filled with the scale colour, the fixed step colour of the cell mean
  (`StepScale.colorOf`) for a property with a step scale, the percentile ramp for the rest. A stale cell (no value for `CellFreshness.STALE_AFTER_MS`, 3 hours) is
  drawn at 40 percent opacity. A cell that does not measure the chosen
  metric is outline only, no fill. Selected cell has a thick ink outline. Staleness is judged on
  the chosen metric's own date, so a newer reading of another metric never hides it.
- **Sample and Real.** A compact two-state toggle top right under the chips, a
  floating control with the brand blue only on the chosen half. Sample is the bundled fixture,
  Real is the OpenAQ aggregates feed, labelled with the day its data runs to in the phone's
  language, as in "OpenAQ, data to 8 Oct". On a start with no network that still has a kept index,
  Real shows the cells read before and says "OpenAQ, cached 2 h ago" with the age of that read.
  The choice is saved (`DiscoverSource`) and Real is
  read only while Show public sensors is on (`effectiveDiscoverSource`). With the switch off the
  toggle is disabled and shows Sample, and screen readers hear how to turn it on. A switch of
  feed clears the rows, the selection and the window reads, then reloads. Real offers 24 hours
  and 7 days, so the cell sheet's mood strip shows two blocks. A failed Real read shows "Could not
  load public sensors" with Retry in the sheet, ahead of the first-run card, never the sample in
  its place. Each toggle half and the "Data OpenAQ" link keep their drawn size inside a 48 dp
  touch target. Choosing 24 hours in Real saves it even while a saved last hour is shown as
  24 hours. "Sample data" still
  heads every cell sheet while the feed on screen is the sample, and the info button explains the
  feed on screen. At peek the area list shows it as a one line tag above the chart title, so the
  chart stays in sight and the tag is never cut at a large font. `DiscoverController.onSourceSettings`, `chooseSource` and `retry`.
- **Zoom.** A compact floating pair of Zoom in and Zoom out buttons bottom right, above Near me,
  each a 48 dp target with its own spoken name, so the map never needs a pinch. One step per tap.
  The map holds pinch and the buttons to the same zoom 1 to 16. With Reduce Motion on, zoom, place search and Near me move the camera
  without an animation.
- **Attribution.** "© OpenStreetMap" bottom right under the map controls, visible at launch. It collapses to an info
  button after the first pan or after 5 seconds, as the OSMF guidelines allow. In Real mode it
  also shows "Data OpenAQ", which stays while Real is on screen and opens
  https://docs.openaq.org/resources/licenses.
- **First run.** The peek area holds one card instead of the summary. A 48 dp peacock, one line
  "Public sensors near you, shown as areas", and two actions "Add your sensor" and "Explore".
  On the sample feed a one line "Sample data" tag sits over the heading beside the peacock, so
  the actions stay inside the peek.
  Either action dismisses it for good. The card carries no numbers. The mascot stays away from
  charts and numbers at Detailed and Developer. At Simple the cell sheet places it beside the
  mood word and its one value, the only exception.

Sheet heights.

| Height | Nothing selected | Cell selected |
| --- | --- | --- |
| Peek (`PEEK_HEIGHT`, 200 dp) | The spread chart of the chosen metric first, so it stays in sight while the map is explored, then "12 sensor areas in view" and the freshness, or the first-run card | The spread chart with this cell marked, then the headline value, sensor count and freshness |
| Expanded | The cells in view by value, filtered by the chart's touched or pinned bar, then "Your sensors" | A range row per other metric the cell reports |

Tapping a cell expands the sheet. Tapping the map outside a cell returns to peek.
The sheet header is a 48 dp button with a chevron, named Show more or Show less, so one tap opens
the sheet or returns it to peek without a drag. Back from the full property list of a cell
returns to that cell. Otherwise Back clears the selection and returns the sheet to peek before it
leaves the screen. Both apps draw the same Compose `BottomSheetScaffold` standard sheet.

**First open**

```
+--------------------------------------+
| [Search places              ] [ + ]  |
| (PM2.5) (Temp) (Humidity) (More)     |
|                    (Sample | Real i)|
| .1h   __    __                       |
| |    /  \__/  \__      map of hexes  |
| o24h \__/  \__/  \                   |
| |       \__/  \__/                   |
| .7d                                  |
|                                      |
| [green..yellow..red  µg/m³]  © OSM i |
+--------------------------------------+
| ----                                 |
| (peacock) Public sensors near you,   |
|           shown as areas             |
|  [Add your sensor]   [Explore]       |
+--------------------------------------+
| Discover    My devices    Settings   |
+--------------------------------------+
```

**Cell tapped**

```
+--------------------------------------+
| (PM2.5) (Temp) (Humidity) (More)     |
| time slider, map, selected hex outlined|
+--------------------------------------+
| ----                                 |
| Sample data                        i |
| 14 µg/m³  PM2.5                      |
| [#] Meets WHO guideline              |
| 5 sensors  Updated 1 h ago           |
|                                      |
| PM2.5 µg/m³ avg 24 hours min 6 max 31|
|                   WHO 15             |
|   . : # : .  ¦ .  step coloured bars |
|       |      ¦      rule at the mean |
| [neutral axis==(o)===¦=============] |
|     (=====|======)  min to max band  |
| 0                                 25 |
| Higher than 72% of areas in view     |
|--------------------------------------|
| Temp     °C  [--(==o==)------]  21.5 |
| Humidity  %  [------(=o===)--]    48 |
+--------------------------------------+
```

Sheet charts. The feed carries one window of statistics per cell (n, mean, min,
max), no hourly history, so the sheet draws no time series. Charts are Canvas in common Compose,
identical on Android and iOS, and every one carries a spoken summary of its values.

- **Chart and map cross filter.** Touch and slide across the spread chart to read each bar (its
  range, how many areas it holds and the step). While a bar is touched, areas outside it fade
  on the map and the list keeps only its areas. A tap pins the bar, a second tap or a tap on the
  map outside any area lets go. While a bar is pinned a visible Clear filter button lets go too.
  Screen readers get one action per bar that pins it, and Clear filter first while one is pinned.
  The other way, tapping an area marks it on the chart, and panning
  redraws the chart for the areas in view. The map filters with a style expression on each
  cell's mean (`v` in the GeoJSON), so nothing is rebuilt while a finger moves.
- **Spread chart.** A histogram of the chosen metric's cell means in view. For a step scale whose
  first edge is above zero the value axis starts at zero and ends on the smallest edge at or above
  the 95th percentile in view, at least the guideline (`StepAxis.high`), so the guideline is
  always in view and charts compare across cells. Bin borders fall on every edge in view
  (`StepAxis.bins`), so each bar holds one step and takes its step colour with a faint ink
  edge. The guideline, when the scale has one, is a dashed ink line labelled "WHO 15" (or 45)
  for a WHO scale and "Guideline" with its value otherwise. Past the last edge the
  95th percentile is the top. Values past the top count in the end bar, an ink arrow marks the
  axis end and "Some areas go past the chart, up to N" says how far. The axis track stays
  neutral, no colour bands sit behind the data. Other metrics keep thin grey bars on an axis
  drawn as the map's viridis scale, clamped at the 5th and 95th percentile like the cells. End
  values sit under the axis. With a cell selected its bin turns ink (outlined in ink for step
  bars), an ink rule and a ringed dot in the cell colour mark its mean, and a band below the
  axis spans its min to max. The step axis track and range row tracks are hollow outlines and the
  band is solid ink, so every mark reaches 3 to 1 on the sheet in light and dark (`chartColors`).
  Range row labels wrap to two lines, and edge labels shrink to share the width instead of
  overlapping. "Higher than N% of areas in view" follows when three or more areas
  report the metric. With nothing selected the same chart, unmarked, sits above the list of
  cells.
- **Step names.** Colour never carries a step alone. The cell headline, each row of the
  cell list and each range row with a step scale name the step in text, the scale's own label in
  the app's language (for WHO, Within the WHO guideline, Within WHO interim target 4 to 1, Above
  every WHO target).
- **Range rows.** One row per other metric the cell reports, in dictionary order, grouped by
  category past five rows. Label and unit, a track for that metric's axis (zero to an edge
  with a dashed guideline tick for a step scale, the extent in view for the rest), the cell's min to max
  band, the mean as a ringed dot in the colour the map would give it, and the mean as text.
  Tapping a row makes it the chosen metric (chips, map and hero follow) without closing the
  sheet. A missing min or max leaves the band out, never draws zero.
- Marks are ink and grey from the theme (onSurface), text is never in a data colour, and the only
  hues are the scale (published steps or viridis), so light and dark both read. Marker moves animate
  for 350 ms.

**Navigation.** One bottom bar for the whole app, Discover, My
devices and Settings, drawn once at the app root on Android and iOS. Discover is always the start
tab, whether or not a device is paired. A device opens on its Station page, the hub of that sensor.
Its gear opens Device settings and its More card opens History, Files and, at Developer, Debug. Each
is a plain push over the Station page, so Back returns to it, and there is no section tab row or
second bottom bar. Each bottom bar tab keeps its own back stack (`docs/screens.md`).

### Centring the map and Near me

The map never asks for location at launch. It opens on the first of these that exists.

1. The last viewed area (saved camera).
2. A region from the phone's IANA time zone, using a bundled zone to bounding box table
   (`TimeZoneRegions`). This
   lands on the right country or metro for most people, and on a continent overview otherwise,
   like Google Maps zooming out to an overview.

Search uses the bundled GeoNames cities15000 list, every place of 15,000 people or more, with its
ASCII names (`PlaceSearch` in `:ui:map`, written by `tools/places/make_places.py`). Only the 53
curated large and Ghana cities of `CURATED_ARABIC` in that script have Arabic names, shown in Arabic
and found by them. Every other place shows its GeoNames name in both languages. The untagged
alternate names of cities15000 mix old and district names (Casablanca would read أنفا), so wider
Arabic names wait for the tagged `ar` names of alternateNamesV2. It ignores case, accents, Arabic
hamza forms and the article. Search is local, so typing never
leaves the phone. The same search sits on the area map of setup and Device settings.

**"Near me".** A map button that asks for approximate location only
at the moment it is tapped, because a time-zone guess fails for large zones and store rules
allow it cleanly. Precise location is never requested on either platform.

- Android. Declare only `ACCESS_COARSE_LOCATION` (about 3 km², similar to a resolution 7 cell).
  Foreground only, so no Play location declaration or background disclosure is needed. The Android 17 location button (Jetpack
  `androidx.core.locationbutton`) is optional and grants one time precise location, so the
  standard coarse runtime prompt stays.
- iOS. When In Use with `NSLocationDefaultAccuracyReduced` set to true, so the prompt offers
  approximate location by default. Search stays the alternative when the user declines (App
  Review 5.1.1).
- Use. The fix only moves the camera. It is never stored, never sent and never published, so it
  is not "collected" under either store's privacy rules.
- Code. A tonal my-location button sits bottom right above the
  attribution. One shared implementation in `ui/map` `NearMe.kt` uses maplibre-compose
  location (`rememberDefaultLocationProvider`, `rememberLocationState` with
  `LocationAccuracy.Low`, `LocationIndicatorLayer` for the puck). Only the prompt is per platform
  (`NearMe.android.kt` asks for `ACCESS_COARSE_LOCATION` alone, `NearMe.ios.kt` calls
  `requestWhenInUseAuthorization`). No provider exists before the tap. After a grant the camera
  moves to the centre of the resolution 6 cell holding the first fix (`nearMeTarget`), at zoom
  10.5, and the puck follows the fix while the map is open. A fix that does not arrive within
  15 seconds leaves the map where it is. A "Location not found" note shows beside the button
  until the next tap, and screen readers hear it as the button's state in a polite live region. `DiscoverPrefs` saves only the
  camera, and the camera never holds the fix, so the saved view is at most a coarse cell centre.

- Decline. The map stays where it is and search remains. Asking again happens only on the next
  tap, never on launch.
- The same approximate fix moves the area map of setup and Device settings (**Use phone
  location**). The owner still taps the cell, and the fix itself is not kept.

`AGENTS.md` records this boundary.

### Your own sensors on the map (#226)

Each of the user's own Things shows as a pin at the centre of its saved H3 cell, a paper dot
ringed in ink. The pins are their own clustered GeoJSON source above the public cells and below the
Near me puck. Pins closer than 44 dp merge into an ink badge with its count, up to zoom 14. A badge
tap zooms two steps on it. A pin tap falls through to the public cell under it. Neither takes the
brand blue, which marks selection only. Each point carries only its index in the list, never a
Thing UUID or name, and the public source never carries an own Thing. A test holds both
(`ownThingsNeverReachThePublicSource`).

The cell comes from `thing_place` on the phone ([data model](data-model.md)), written when Device
settings reads or saves the location. A Thing with no well-formed cell has no pin. A moving Thing
stands at the newer of its last GNSS cell and the last GNSS or phone fix in its own rows, never
the configured cell of its fixed hours. The cells of those fixes in the last 24 hours are outlined
with an ink fill and line in a track source of their own, under the pins
and with no Thing UUID (#263, [device location](device-location.md#mobility-and-gnss)). A row with
no location is no fix, so a moving Thing with no fix has no pin and never a guessed one. A
forgotten sensor loses its pin, because Forget deletes its cells and the host joins the places with
the Things the phone still knows. A Thing with no stored place shows once its location is next read. The source is rebuilt
off the main thread, and only when a Thing, its cell or its last update changes. No request leaves
the phone for it and nothing is published.

The list alternative for TalkBack and VoiceOver is the sheet. Under the areas in view, the first
run card, a failed load or the unavailable message it lists "Your sensors" by name, each row a
button that moves the map to that pin. Only a selected area and the property list take its place
(`everyBodyBesideTheMapPinsListsTheOwnThings`). The cluster badge shows
the count only.

Each row of "Your sensors" carries the Thing's newest local reading under its name, the main metric
with its label, value and unit as the profile formats it (`LatestReadings`, from the 15 minute
tiers of its last two days, or of the newest older day the day tier finds with a reading when those
two have none). Beside it the row says Live, an ink dot and bold ink word, while the
phone holds a link to that Thing over Bluetooth or LAN. Otherwise it says how old the reading is,
"just now", "12 min ago", "3 h ago" or "2 d ago", ticking once a minute. A Thing with no reading
says neither. Both phones join the readings, the links and the uploads in one shared flow
(`observeOwnStatus`). Android reads the links from `DeviceConnectionManager` and iOS from
`CompanionState.linkedThings()`. All of it comes from the phone's own history and link state, so
it works offline and sends nothing.

Own data never touches a public cell. The controller keeps it apart from the rows, the selection
and the GeoJSON (`ownStatusNeverChangesThePublicCells`), and no code adds an own value to a public
mean or takes one from it. A cell sheet says "Includes your sensor" only when the feed on screen
folds in shared sensors (`PublicFeed.foldsSharedSensors`), an own Thing has uploaded at least one
file (`ArchiveIndex.observeUploadedThings`) and that Thing's cell lies inside the public cell. The
check reads the cell ids only. OpenAQ and the sample fold in no shared sensor, so the line stays
hidden on both today (`includesYourSensorShowsOnlyOnceTheSensorSharesIntoAFeedThatFoldsIt`).

The info button of the Sample and Real toggle and of the legend lists each source on screen with
its date. "Your sensors, from this phone" with the day of the newest own reading, shown once an own
pin exists. "Public sensors, OpenAQ" with the day its data runs to, and its cached age when it came
from the kept index. "Sample, bundled with the app" with the day its data runs to.

### Privacy rules

Snapping a home to a hexagon does not make its data anonymous. Temperature, humidity, CO2 and
particle series reveal occupancy, cooking and even how many people are home. The rules below
treat each public series as personal data until it is aggregated. Rules 3 to 9 bind the
opensensor publisher and per-device sharing, which are not built. Nothing uploads today. The
Real feed on screen is OpenAQ, public licensed stations with one or more per cell
(`crowd_rule = none` in its metadata).

1. **No points, only cells.** Public data carries an H3 cell, never coordinates. The owner picks
   the cell on the map when opting in, optionally starting from an approximate "Near me" fix.
   Coordinates never leave the phone. No jitter is added to
   cells, since snapping already coarsens the place and noise only helps for points.
2. **Precision.** Every published Thing, fixed or moving, carries
   H3 resolution 8. The board writes its cells at 8
   and no publisher coarsens them, so a File Item's `grid:code` and the `h3_cell_id` rows of the
   Parquet file beside it name the same cell. A res 8 cell averages about 0.74 km², with an edge
   of about 530 m. That is an area, not an address, but it is not anonymous on its own. Where few
   people live, one cell can hold a single home. The Item says this about itself. Its Anonymized
   Location fields declare the cell size (`anon:size`, 0.01 degrees, twice the res 8 edge) and
   that the geometry is a cell, not the device position (`anon:warning`). A file whose cells are
   not res 8 is not published (`FileItemResult.WrongResolution`), whatever the device was set to.
   Play treats location finer than about 3 km² as precise, so the store forms declare a
   published cell as precise location on Play
   ([store-compliance.md](store-compliance.md#decisions-for-discover-and-uploads)).
3. **Crowd rule for every number.** A cell's aggregate and its device list are published only
   when it holds at least three publishing devices. With fewer, the devices count only toward
   the parent. A parent is published only when the devices it holds outside its published
   children number zero or at least three, so a sibling cannot be subtracted from the parent
   to recover one device.
4. **Time.** Public values are hourly means, published at least one hour after the hour ends.
   No live or minute values ever leave the owner's phone through this feature.
5. **Rounding.** Values are rounded to the instrument's real precision from the dictionary (for
   example PM to 1 µg/m³, temperature to 0.5 °C, humidity to 1 percent).
6. **Outdoor only.** A device must be marked outdoor to publish. The indoor or outdoor choice
   defaults to indoor. Indoor devices never appear in public data (Netatmo and
   Sensor.Community do the same).
7. **Device identity, not user identity.** No accounts, no sign-in
   and no passkey at app start. Passkeys need a relying party server and are an account in
   store terms, which brings sign-in screens and in-app account deletion for no gain here.
   Instead, turning sharing on makes a fresh P-256 key pair for that device in the Android
   Keystore or the iOS Keychain (Secure Enclave where available). The private key never leaves
   the phone and signs each upload. The public ID is a truncated SHA-256 of the public key, and
   the public name is derived from the same hash as two words and a number (for example "Blue
   Heron 41"), so there is no free text and nothing to moderate. Turning sharing on again later
   makes a new key, ID and name. Nothing is derived from the thing UUID, MAC address, serial
   or the owner. The thing UUID, MAC, BLE name, Wi-Fi and LAN token never leave the phone.
   If the firmware later uploads by itself, the key moves to the board through an
   `opensensor-space-firmware` contract change.
8. **Opt in per device, with an open licence.** Reading the feed is anonymous and read-only.
   Uploading is off for every device by default and is turned on only in that device's settings
   ("Share this sensor publicly"), never globally, in onboarding or in pairing. One
   confirmation shows the cell picker, the generated name, what is shared (hourly outdoor
   readings and the area), what never is (exact location, IDs, Wi-Fi, PIN, token), and that
   published data is released under CC BY 4.0 and cannot be taken back.
9. **Stop sharing.** Stored with that device's settings. It takes
   effect from the next UTC day, so the current day's hours still publish and nothing after
   it does. Data already published stays public under its licence. Sharing can be turned on
   again at any time with a new identity (rule 7).

The publisher enforces every rule before a file is written. The phone never receives a
suppressed cell or a lone device, so a modified client cannot reveal one.

Differential privacy noise is not needed for this design. Count suppression, the differencing
guard and hourly coarsening are the practical baseline, and noise would make small cells
misleading. Revisit if public values ever become finer than hourly.

### Network boundary

Beyond BLE and LAN the app makes only anonymous, read-only GET requests, to the public feed
and the basemap. Under the same switch it may also GET a
synced file's board template from its footer `template.href`, only an https link on the firmware
template host with no redirect, and only when nothing on the phone resolves that template. The
bytes are kept only when their SHA-256 is the footer desc (`TemplateHrefs`, see
`history-pipeline.md`). No cookies, accounts, identifiers or analytics. A Settings switch, "Show
public sensors", turns them all off. When it is off, Discover
shows the bundled sample on the bundled basemap. When the network fails, Real shows the last kept
index and its cells from the block cache, labelled cached with its age, and with nothing kept it
shows the failed load with Retry. Own pins, their readings and Live read only the phone. Pairing
and sync never need the network. `AGENTS.md` records this boundary.

### Public feed

Discover reads two feeds through the `PublicFeed` port and the repository's own Parquet reader.
Both carry H3 cells and no geometry, and both mirror the website's `/indices` explorer layout,
one plain Parquet file per H3 resolution.

- **Sample.** `BundledSampleFeed` reads the files under
  `feature/discover/src/commonMain/composeResources/files/discover-sample/v1`, written by
  `tools/discover-fixture/make_discover_sample.py`. `latest.json` names the partition.
- **Real.** `OpenAqAggregatesFeed` reads the OpenAQ aggregates on source.coop (below).

The opensensor feed is not built. No publisher, ingest endpoint, device list file or per-device
series file exists, and the phone has no upload code. When it lands it is static files on
source.coop with no server for reading, written by a publisher outside this repo that applies
the privacy rules above, and the phone never holds source.coop write credentials.

```
discover-sample/v1/
  latest.json
  cells/window={1h|24h|7d}/date=YYYY-MM-DD/hour=HH/h3_res={r}/data.parquet
```

- **Cells file (`FeedPaths.cells`).** One plain Parquet file per resolution, `h3_res` 2 to 7.
  Columns are `h3_index` (INT64, sorted), `devices` (INT32), `updated` (TIMESTAMP UTC) and
  flat metric columns `<property>__n`, `__mean`, `__min`, `__max` (DOUBLE, null when the cell
  does not measure it). Property IDs, units and families come from the `dictionary` key of the
  file's key value metadata. No geometry, no nested columns.
- **What a feed file must be.** SNAPPY or LZ4_RAW compression, not ZSTD, because iOS has no
  zstd in its system libraries and the reader keeps native code out of `:core:*`. Flat columns
  in the encodings [parquet.md](parquet.md) lists as accepted. Min and max statistics on every
  column, and column and offset indexes so pages prune.

Reading on the phone.

1. The camera rests for 400 ms. Resolution is `round(zoom * 0.8)`, clamped to 2 to 7, as on the
   website. Deeper zoom keeps showing resolution 7.
2. The viewport is covered with H3 cells at that resolution, coarsening until the cover is at
   most 1,024 cells, then turned into merged INT64 descendant ranges (pure bit arithmetic on the
   index, in `commonMain`).
3. The reader fetches the footer once per URL, keeps row groups and pages whose
   `h3_index` min and max overlap a range, and range reads only `h3_index` and the chosen
   metric's `__mean` column. Rows are then filtered exactly.
4. A tap is an `h3_index` lookup in the rows already loaded, then one more range read of that
   row group's `__n`, `__mean`, `__min` and `__max` columns for every property.

The reader is the repository's own `:core:parquet` ([parquet.md](parquet.md)), `ParquetReader`
over a `RangeSource`, with an in-memory LRU `FooterCache` of footers and page indexes, coalesced
range requests (`RangePolicy` `LOCAL`, `CELLULAR`, `WIFI`) and pruning of row groups by footer
statistics and of pages by the column and offset indexes. `:core:net` adds `HttpRangeSource` over OkHttp (`okhttp` in `gradle/libs.versions.toml`) on Android and
`NSURLSession` on iOS. It sends `Accept-Encoding: identity`, so byte offsets stay true, and checks
`If-Range` against the ETag, so a replaced file is read again from its new footer.
`OpenAqAggregatesFeed` reads the OpenAQ aggregates through it (below).
Android `DiscoverViewModel` and iOS `AppRoot` each build one per process, read only while Show
public sensors is on and Real is chosen. Each file read goes through `CachedRangeSource` and the
disk `BlockCache` (#94), so a second open of a view reads cached blocks with no request.

**Sample data.** `tools/discover-fixture/` writes the layout and schema above with Python `h3`
and PyArrow, including `sample = true` in the key value metadata, and the files ship in the app.
The phone reads them through the same reader, so the sample and the Real feed share one code
path.

**Real feed, OpenAQ aggregates.** The Real feed is the OpenAQ aggregates collection on
source.coop, read with the same reader. Its root is `OpenAqAggregatesFeed.DEFAULT_ROOT`.

- **Discover Collection (#189).** `collection.json` at the root is a STAC Collection read with
  `ThingCollection` from `:core:model` (`DiscoverCollection`). H3 only. One cells asset per
  resolution. Its key names the resolution, `cells_r<n>` as the live feed writes it or `h3_res=<n>`.
  An asset with any other key is a cells asset only when it is Parquet and its href has one
  `h3_res=<n>` path segment, so `annual`, `daily`, `hourly`, `stations` and `manifest` are ignored.
  `<n>` is plain decimal, 0 to 15. A key with another `<n>`, an href segment that names another
  resolution than the key, and every asset of a resolution listed twice are dropped alone. Both key
  forms are read until stac-opensensor#6 fixes one. The href is relative (no `..`, no absolute
  path, no scheme) or an https URL on the same host as the feed root. Any other asset, such as
  cleartext, another host or a local address, is dropped alone and the rest of the Collection
  stands. The weekly `live-key-drift` workflow checks the live Collection with the same rules
  (`tools/check_discover_collection.py`) and fails when the map would have no cells at resolution
  2 to 7. The zoom picks the resolution. A zoom whose resolution has no asset reads the
  nearest listed one, and between two equally near the one nearer 8, so a Collection that lists
  only some resolutions never shows an empty map. `item_assets.data.table:columns` name `<id>__n`,
  `__mean`, `__min` and `__max` per property. The `__mean` column's `title` names the property
  for an id the app does not know, and its `opensensor:scale` is a key of the Collection's
  `opensensor:scales`, the legend's step scale. A column without one gets the neutral ramp, and so does one whose
  scale does not parse or breaks a bound (more than 12 steps, or an edge or guideline that is not
  finite or is beyond 1e9), which is dropped alone while the rest of the Collection stands. Only the
  first 64 entries of `opensensor:scales` are read, later ones are dropped the same way. The
  end of `extent.temporal.interval` is `as_of`, `updated` is the generation. `latest()` answers
  `FeedPartition(as_of, 0, updated)`. A resolution without an asset answers an empty table with
  no request, so the controller asks the feed for the nearest listed resolution first.
- `manifest.json` is the fallback when the Collection answers 404 or 403. Any other failure
  throws. It carries `as_of` (`YYYY-MM-DD`, the newest complete PM2.5 day) and `generation`,
  plus fields the phone ignores. `latest()` answers `FeedPartition(as_of, 0, generation)`. A
  manifest feed, like the bundled sample, gets the PM2.5 and PM10 WHO scales the app ships
  (`DiscoverCollection.BUNDLED`, the same edges, colours and step names as `WhoGuidelines` and
  the Android step strings, held by tests). Either answer is kept for 5 minutes. Each body that
  parses is also kept, in memory and in the `BlockCache` under an `index-` name for the root, with
  the time it was read. When a later read fails, `snapshot()` answers that kept index with
  `cachedAtEpochMs` set, for 30 seconds before the network is tried again, so a cold offline start
  still finds its partition and the cell blocks read before. With nothing kept the failure
  propagates. A broken body never replaces a kept one.
- `cells/h3_res=<1..9>/data.parquet`, one file per resolution holding every day since 2023,
  written by DuckDB. One row group, SNAPPY, dictionary encoded, min and max statistics, no page
  index, every column optional. Columns `h3_index` INT64, `date` INT32 with the DATE converted
  type (days since 1970-01-01, the thing local day), `devices` INT32 (things, 1 or more, no
  crowd rule, key `crowd_rule = none, public licensed things`), then `<id>__n` INT32 and
  `<id>__mean`, `__min`, `__max` DOUBLE, null when not measured. Rows sort by `h3_index` then
  `date`. Key value metadata `feed = openaq-aggregates`, `window = 1d`, `h3_res`, `generation`
  and `dictionary` (`pm25`, `pm10`, `pm1`, `no2`, `temperature`, `humidity`, `um003`). Only
  complete days (18 or more hours) are present, and data lags OpenAQ by about 72 hours.
- `ParquetFeedReader` reads this daily schema when `updated` is absent and `date` is present.
  Each row keeps its `CellRow.date`, its `updatedEpochMs` is the UTC midnight after that date,
  and an optional day range drops other days before a row is built. The sample keeps the hourly
  schema.
- `DailyCellFold` turns the daily rows of the 7 days ending at `as_of` into one cell each. 24
  hours takes, per property, the newest day in range that has a mean, as it is. 7 days takes the
  mean of the daily means, the min of the mins, the max of the maxes and the largest daily `n`,
  skipping days without the property. `devices` is the largest daily count among the days used,
  `updated` the end of the newest day used. A property last measured on an older day keeps the end
  of its own newest day in `CellRow.updatedByProperty`, and the sheet freshness and stale styling
  read the shown property's date (`CellRow.updatedEpochMs(property)`). A cell with nothing in range
  is dropped. Null stays null.
- The feed offers 24 hours and 7 days only. Last hour answers an empty table without a request.
  Errors propagate, the screen decides what to show. Android builds it over `OkHttpRangeEngine`
  in `DiscoverViewModel`, iOS over `UrlSessionRangeEngine` in `AppRoot`, one per process.
- `DiscoverController` holds the sample and this feed. Real asks `snapshot()` on every load (the
  manifest is cached for 5 minutes), keeps the last partition when a later ask fails, and carries
  the snapshot's `cachedAtEpochMs` into the state for the cached label. A cell's
  other windows are read 24 hours first, so a failing window never delays the headline. A pan
  rereads a failed window without flipping the sheet back to loading. A pan that picks up a new
  partition reads every window of the selected cell again, and a window read of an older partition
  never lands. A new `generation` under the same `as_of` is a new partition. Tests use a 13 row fixture from
  `tools/discover-fixture/make_openaq_fixture.py` served by a fake HTTP engine, and a STAC fixture
  from the same script with PM2.5 on the WHO scale and a soil property with a title and no scale. The live URL is
  not in the gate.

**What we do not copy from the website.** The EOX Sentinel-2 cloudless basemap, whose licence
restricts commercial use, and the deck.gl globe view. Some current website datasets also still
store H3 as strings with a geometry column in one large ZSTD row group, which the phone does not
read.

### Map technology

- **Renderer.** `org.maplibre.compose:maplibre-compose`, pinned as `maplibreCompose` in
  `gradle/libs.versions.toml`. It renders through `maplibre-native-ffi` instead of the platform
  SDKs. Android needs one runtime artifact (`maplibre-compose-runtime-opengl-android`). iOS
  needs no CocoaPods or SPM, only `-lc++ -lz` and the system frameworks (CoreFoundation,
  CoreGraphics, CoreText, Foundation, ImageIO, Metal, QuartzCore), which fits our direct Xcode
  framework setup. Minimum iOS 15.5. Android and iOS are Beta and minor releases may break, so
  pin the exact version and upgrade deliberately.
- **Fallback.** If a future pin breaks it, wrap MapLibre Native directly behind the same
  composable (`BaseMap` in `:ui:map`).
- **Cells.** One GeoJSON source built from the loaded rows, with a data-driven fill expression
  on the chosen value. No composable per cell. Each update replaces the whole dataset, so build
  it off the main thread and only after the 400 ms rest. The cover budget keeps it near 1,024
  polygons. Hit testing uses the layer
  `onClick`.
- **H3 on the phone.** Required, because the files carry no geometry. Behind one interface in
  `:core:publicfeed` (`H3Grid`), `com.uber:h3` (`h3` in the catalog) on Android and the JVM and
  the vendored H3 C library through cinterop on iOS, for `polygonToCells`, `cellToBoundary` and
  `latLngToCell`. Descendant ranges need no library. A `commonTest` checks both platforms against vectors from Python `h3`.
- **Basemap online.** The Protomaps OpenStreetMap PMTiles build on source.coop, read through
  `pmtiles://` (the URL is in `ui/map` `files/basemap/light.json` and `dark.json`). The light
  and dark styles are written by `tools/discover-fixture/make_basemap_style.mjs` from
  `@protomaps/basemaps`, with glyphs and sprites from `protomaps.github.io`.
- **Right-to-left labels on iOS (#53).** The Apple build of `maplibre-native-ffi` links ICU, so
  Arabic names shape and run right to left, and Android and iOS load the same `light` and `dark`
  styles. Arabic labels on an iPhone need a dated check in `docs/status.md`.
- **Basemap offline.** Nothing is bundled, and the optional "Offline map" download of decision 9
  is not built. Without network, or with "Show public sensors" off, the cells draw on the plain
  paper style (`MapStyles`). Settings sizes and clears the tile cache (`MapCache`).
- **Ornaments.** The app draws its own attribution. No rotation or tilt, flat Web Mercator, no
  globe.

### Store compliance

**Read only, sample or public feed (built).**

- **Attribution.** "© OpenStreetMap" visible on the map at launch, collapsing to an info button,
  linking to openstreetmap.org/copyright (OSMF guidelines, ODbL Produced Work). The Protomaps
  credit and GeoNames (CC BY 4.0) sit in the map info. Apple 5.2.2 expects
  permission for third-party content, which the licences give with attribution.
- **Privacy policy.** Required by both stores for every app, as a public URL in the console and
  inside the app (Settings, About). It names the feed and basemap hosts and the firmware template
  host (`raw.githubusercontent.com`), says each request
  carries the phone's IP address as any web request does, and says we keep no request logs.
- **Data safety and privacy label.** Reading the feed sends no identifier, and we receive no
  request logs, so nothing is collected. Play Data safety declares no data collected or shared.
  Apple's label is "Data Not Collected", since an IP address sent in a request and not retained
  need not be disclosed. Confirm in writing with Radiant Earth what source.coop and AWS log for
  public reads and that it is not shared with us. If that changes, both answers change. The
  Near me fix is used on the phone only and is not collected.
- **Privacy manifest.** `PrivacyInfo.xcprivacy` with `NSPrivacyTracking` false and the required
  reason entries for the APIs the binary uses (UserDefaults, file timestamps, disk space).
  Audit the built framework, since Kotlin/Native, Compose and MapLibre may call them.

**Once per-device publishing exists (not built).**

- **What is collected.** Hourly outdoor readings and the chosen cell. Apple label "Coarse
  Location" (the cell) and "Other Data" (readings), not linked to identity, not used for
  tracking. Play "Precise location" (the res 8 cell, see store-compliance.md decision 1) and the
  closest sensor category, collected, user
  initiated, optional, not shared with third parties beyond public publication. Disclose that
  the data becomes public.
- **Licence and stopping.** No accounts, so account deletion rules do not apply. Published data
  is CC BY 4.0, which the opt-in confirmation states before anything uploads. "Stop sharing"
  stops new data from the next UTC day and does not withdraw what is published. The privacy
  policy says this, and keeps a contact address for legal removal requests, since per-device
  series could still count as personal data under laws such as the GDPR even when the licence
  is irrevocable. Uploads prove ownership with the per-device key (privacy rule 7), never an
  account.
- **User-generated content.** Generated names keep the feature out of Apple 1.2 and Play UGC
  rules. If free-text names are ever allowed, the app must add filtering, reporting, blocking
  and a contact address first.

### Code layout

| Module | Owns |
| --- | --- |
| `:core:model` | `BoardTemplate`, `TemplateKey`, `TemplateIndex`, `Datastream`, `ObservedProperty`, `Sensor`, `AppProfile`, `Family`, `ThingCollection`, `Scale`, `ValidityGate`, `Aggregate`, `Role` and the `Thing` join, parsed from stac-opensensor v0.1.0 JSON (#183). `BundledCatalog` and `LiveColumns` read the bundled templates and profiles in `core/model/bundled/` (#184). `Capability` ids and the `DerivedMetricProvider` seam live here too. `LiveReading`, `DeviceCapabilities` (a `Capability` set), the batch estimate and `Metrics` in `:core:history` use them |
| `:core:publicfeed` | `PublicFeed` and `FeedReader` ports, `ParquetFeedReader` (common, one `ParquetReader.scan` filtered on `h3_index` with page pruning, `CellsTable` reports pages read of total), `BundledSampleFeed`, the `H3Grid` interface (`com.uber:h3` on Android and JVM, the vendored H3 C library through cinterop on iOS), `H3Index` bit arithmetic and descendant ranges, `ViewportCover`, `DiscoverCollection` (the discover STAC Collection and the bundled WHO PM scales), `CellScale` (`StepScale` from the property's published scale, else `PercentileScale`), `StepAxis`, `PropertyRanking`, `CellGeoJson`, `TimeZoneRegions`, `OpenAqAggregatesFeed` over `:core:net`, the daily schema in `ParquetFeedReader` and `DailyCellFold`. Its reads go through the `:core:net` block cache (#94) |
| `:core:stac` | The board writes each File Item, a sidecar `.json` beside its `.parquet`, and the phone is its transport and validator. `FileItems.build` regenerates that Item from one verified device file, its `opensensor` footer and its `BoardTemplate`, and must equal the board's Item as JSON values. It follows the stac-opensensor v0.1.0 profile (tag v0.1.0, 83787768cc8451109d48a03d4ef5493ffcd403dd), or gives a `FileItemResult.NoItem` reason (#190). Only dated `data_HHMM_` files with a Thing UUID, a mobility, a UTC time on every row and at least one H3 cell get one (`ITEM_NEEDS_LOCATION`, bridge question 41). Every cell must be res 8, or the file gets `WrongResolution` and no Item, the phone never coarsens. The partition comes from the UTC start of the rotation window that holds the first row (`file.rotation_interval_s`). A footer with no allowed `rotation_interval_s` gets `NoRotationWindow`, a name whose `HHMM` is another window gets `WindowMismatch`, a cell H3 rejects (`isValidCell`) gets `InvalidCell` and a `file.purpose` other than `telemetry`, such as a codec benchmark, gets `NotTelemetry`. `FooterSchemaTest` runs the profile's `footer.json` over its footer fixtures, and the reader refuses a footer the schema refuses for a missing member or another version. The geometry is the union of the sorted cells (`H3Grid.cellsToMultiPolygon`). A fixed Thing also gets `grid:code` and lists Grid, a moving Thing does not. The Item also carries `anon:size` (0.01), `anon:warning`, `file:size`, `file:checksum` and `statistics` per measurement column under its `valid_when` gate. The data href is relative, `./<file>.parquet`, so an Item stays valid in any bucket or mirror. `FileItems.sidecarPath` places it beside the file. Pure, no upload. JVM tests validate it against the profile and opensensor schemas, the core STAC 1.1.0 Item schema and the Grid, Anonymized Location, Table and File extension schemas, all bundled. Common tests on the JVM and iOS check that a phone Item rebuilt from each v0.1.0 row fixture equals its example as JSON values under the profile's canonical form (geometry within 1e-9 degrees), and pin the polygon order of a MultiPolygon to the h3-js reference. The JVM binding matches the H3 C reference within 1e-9. `PublishGate` in `:core:thing` is its only caller. `ThingCollections.build` writes the Thing Collection from the board template, the newest footer, the owner config (`ThingOwner`, mapped from the #148 site keys of `ThingSite`) and the upload ledger, and common tests prove it equals the v0.1.0 fixed and moving example Collections (#266). `CanonicalJson` holds the profile's JSON value equality |
| `:core:thing` | The `ThingKind` contract (`KindId`, `Evidence`, `Recognition`, `DataSource`, `PublishPolicy`), `OpenSensorBoardKind` (recognized by its GATT service, `_ossync._tcp` or its `INFO`, capabilities from `DeviceCapabilities`, `PullSync` with `OptInCcBy` at res 8) and the `ThingKinds` registry, a plain list built by Android `ThingModule` and iOS `launchThingKinds` (#222). `metricProviders` lists the kinds' derived metric providers, which each app hands to `AirFamily.install`. `PublishGate` issues a `PublishTicket` only for a Recording kind with `OptInCcBy` and an opted in Thing (`UploadConsent`, none by default) (#224). For a `PullSync` board file it rebuilds the Item at the policy's `publishedRes` only to check the board's sidecar and clears the sidecar as written. For a `PhoneLog` file of this phone's own install it clears the Item the phone built, and it refuses a board file there (#266). Each file asks consent again. No upload. `PlatformNeedsTable` lists what the app shell and each kind ask of the Android manifest and the iOS bundle, which `ManifestPermissionsTest` and `IosBundleGateTest` hold the files to (#227). `:core:thing:testkit` holds `ThingKindContract` |
| `:core:archive`, `:core:sync` | File identity (#273). `FileIdentity` keys a file by its Thing, File Item id and SHA-256 (`file:checksum`). `CatchUpPlanner` plans a restore or catch up of an owned Thing from a remote listing. It skips a file already archived with the same name and checksum, surfaces the same name with other bytes as a conflict and never plans a second copy. Each UTC day reads raw or compacted files, never both, and an archived day keeps the tree it has. `ArchiveIndex.markUploaded` sets the uploaded marker from an upload status listing. No network code. See [history-pipeline.md](history-pipeline.md#file-identity-and-cloud-catch-up-273) |
| `:core:net` | `HttpRangeSource` and the `HttpRangeEngine` port, `OkHttpRangeEngine` (OkHttp, `okhttp` in `gradle/libs.versions.toml`, Android and JVM), `UrlSessionRangeEngine` (`NSURLSession`, iOS). Used by `OpenAqAggregatesFeed` and `TemplateHrefs` in both apps |
| `:core:parquet` | The portable reader, with `RangeSource`, `ParquetReader` (footer cache, coalesced reads, one-pass decoding), row group pruning by INT64 statistics and page pruning by the column and offset indexes |
| `:ui:map` | The shared map of both apps, CMP `commonMain`. `BaseMap` and `rememberBaseMapState` with the zoom limits, no rotation or tilt, the camera rest callback (`CameraRest`), a one time fit and a content slot for the caller's sources and layers. `MapStyles` and `rememberMapStyle` give the bundled basemap styles (Compose resources) or a paper style when public data is off. `ZoomControls`, `MapCache` (the tile cache Settings sizes and clears), `CellOutlines` (plain H3 cell outlines as GeoJSON) Near me (`rememberNearMe`, `NearMeButton`, `NearMeLayer`, `nearMeTarget`), the offline place search (`PlaceSearch`, `PlaceSearchField`) and the resolution 8 area picker of setup and Device settings (`CellPicker`, `CellPickerGrid`). Its own string catalogue holds the zoom, Near me, search and picker labels and the map credits. Depends on `:core:publicfeed`, `:ui:designsystem` and `:ui:permissions` only, so any feature can use it. `:feature:discover` and `:feature:device` use it. Targets Android and iOS, not the JVM |
| `:feature:discover` | CMP `commonMain` map screen (`DiscoverScreen`) on `:ui:map`, `DiscoverController` state holder, the public cell layers and legend, the own Things layer (`OwnThingGeoJson`, `knownOwnThings`), `DiscoverStrings` read from its Compose resources, one copy for both apps, the bundled sample as Compose resources. Targets Android (`com.android.kotlin.multiplatform.library`) and iOS, not the JVM |
| `tools/discover-fixture/` | `make_discover_sample.py` writes the sample feed (Python `h3`, PyArrow, SNAPPY, one row group per file sorted by `h3_index`, 16-row pages with page indexes, about 270 KB for 18 files, made-up cells around Berlin, Cairo, Lisbon, Nairobi, New York, Jakarta and Accra). `make_basemap_style.mjs` writes the light and dark styles from `@protomaps/basemaps`. `make_openaq_fixture.py` writes the OpenAQ and STAC test fixtures. The sample is committed under `feature/discover/src/commonMain/composeResources/files/`, the styles under `ui/map/src/commonMain/composeResources/files/basemap/` |

`:feature:discover` is shared Compose, so Android and iOS show the same screens. Platform code
provides only the saved state (`DiscoverPrefs`, DataStore on Android, NSUserDefaults on iOS), the
H3 binding, the HTTP engine and the cache directory.

### Owner decisions and open questions

Decided by the owner.

1. "Near me" ships, approximate location only, asked on tap.
2. No accounts or passkeys. A per-device anonymous key, with the ID and name derived from it.
3. Published data is CC BY 4.0 and permanent. "Stop sharing" applies from the next UTC day.
4. Hosting is source.coop. Logging terms to confirm with Radiant Earth.
5. No bundled basemap. An optional "Offline map" download in the app Settings.
6. H3 runs on the phone as native code by default (`com.uber:h3` on Android, the C library on
   iOS). It is needed anyway for the viewport cover, Near me and the cell picker. The
   publisher still does all aggregation and privacy work, so the phone only does geometry.

Still open.

1. Who builds and runs the hourly publisher and the upload ingest, and in which repository.
   This is the job that turns raw uploads into the public cell files and enforces the crowd
   rule. The read only map works without it, on the sample and the OpenAQ aggregates.

### Sources

UX and platform
- Material bottom sheets, https://github.com/material-components/material-components-android/blob/master/docs/components/BottomSheet.md and https://developer.android.com/develop/ui/compose/components/bottom-sheets-partial
- Apple HIG sheets, https://developer.apple.com/design/human-interface-guidelines/sheets

Privacy and location
- H3 resolution table, https://h3geo.org/docs/core-library/restable
- Sensor.Community privacy policy, https://devices.sensor.community/privacy-policy
- Location masking among PurpleAir owners (2026), https://ggis.illinois.edu/news/2026-05-26/study-examines-how-location-privacy-concerns-shape-crowdsourced-socio-environmental
- Occupancy from temperature and humidity, https://content.iospress.com/articles/journal-of-computer-security/jcs181133
- Occupancy from CO2, https://bayen.berkeley.edu/sites/default/files/occupancy_detection.pdf
- Smart meter privacy, https://rts.cs.umass.edu/papers/pdf/buildsys10.pdf
- Geo-indistinguishability, https://arxiv.org/abs/1212.1984
- Android location permissions, https://developer.android.com/develop/sensors-and-location/location/permissions
- Play location policy, https://support.google.com/googleplay/android-developer/answer/9799150
- NSLocationDefaultAccuracyReduced, https://developer.apple.com/documentation/bundleresources/information-property-list/nslocationdefaultaccuracyreduced

Map technology
- maplibre-compose releases, https://github.com/maplibre/maplibre-compose/releases
- maplibre-compose version catalogue, https://raw.githubusercontent.com/maplibre/maplibre-compose/main/gradle/libs.versions.toml
- maplibre-compose docs, https://maplibre.org/maplibre-compose/getting-started/ , https://maplibre.org/maplibre-compose/controls/ , https://maplibre.org/maplibre-compose/interaction/
- MapLibre Native releases, https://github.com/maplibre/maplibre-native/releases
- PMTiles on Android, https://maplibre.org/maplibre-native/android/examples/data/PMTiles/
- MapLibre Native iOS changelog, https://raw.githubusercontent.com/maplibre/maplibre-native/main/platform/ios/CHANGELOG.md
- FFI data updates, https://maplibre.org/maplibre-native-ffi/guides/update-data/
- Protomaps flavours, https://docs.protomaps.com/basemaps/flavors , downloads https://docs.protomaps.com/basemaps/downloads , MapLibre use https://docs.protomaps.com/basemaps/maplibre
- Large GeoJSON guidance, https://docs.mapbox.com/help/troubleshooting/working-with-large-geojson-data/

Feed and libraries
- Website `/indices` explorer, `../../website/walkthru-earth.github.io/docs/indices.md` and `docs/parquet-producer-guidance.md` (local)
- Apache Parquet page index, https://parquet.apache.org/docs/file-format/pageindex/
- hyparquet, the website's reader, https://github.com/hyparam/hyparquet
- Apple Compression algorithms (no zstd), https://developer.apple.com/documentation/compression/compression_algorithm
- zstd-kmp, https://klibs.io/project/square/zstd-kmp
- H3 Java, https://github.com/uber/h3-java , h3-kmp https://github.com/phansier/h3-kmp
- EOX cloudless licence, https://cloudless.eox.at/documentation/license
- DuckDB on Android, https://duckdb.org/docs/lts/dev/building/android
- source.coop range and ETag behaviour, https://data.source.coop/protomaps/openstreetmap/v4.pmtiles

Store compliance
- OSMF attribution guidelines, https://osmfoundation.org/wiki/Licence/Attribution_Guidelines
- App Review Guidelines (1.2, 5.1.1, 5.1.5, 5.2.2), https://developer.apple.com/app-store/review/guidelines/
- App privacy details, https://developer.apple.com/app-store/app-privacy-details/
- Required reason APIs, https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api
- Play Data safety, https://support.google.com/googleplay/android-developer/answer/10787469
- Play privacy policy requirement, https://support.google.com/googleplay/android-developer/answer/10144311
- Play user-generated content, https://support.google.com/googleplay/android-developer/answer/9876937
