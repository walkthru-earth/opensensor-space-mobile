# Devices and discovery

**Scope:** how the app supports any sensor family (air quality is one of many), and how a new user
sees public sensor data near them on first open through a privacy-preserving H3 map.
**Code:** `core/publicfeed/`, `feature/discover/`, `tools/discover-fixture/`, `core/model/`. **Related:**
[cross-platform-guide.md](cross-platform-guide.md),
`../opensensor-space-firmware/docs/shared/table-and-observation-model.md`.

## Product direction

opensensor.space is a home for many kinds of open hardware: air, weather, water, soil, noise,
energy. The app must treat a new sensor family as data (a dictionary and a family entry), not as
new screens. And the app must be useful before someone owns a device. Like the first run of
Google Home, Eufy or Apple Weather, opening the app shows something real right away. Here that
means readings from public sensors nearby, shown as hexagon cells, never as exact points.

## Part 1. Sensor and hardware agnostic

### Model

The model follows the observation model the firmware already chose
(`table-and-observation-model.md`, SensorThings concepts). The app adopts the same names so the
phone, the firmware and a future host API agree.

| Concept | Meaning in the app | Today |
| --- | --- | --- |
| `Device` (Thing) | A thing with a persistent UUID. The MAC address is separate | Thing UUID and known device row |
| `DeviceFamily` | Hardware plus firmware line, with its transports, capabilities and dictionary | Implied by `cores3-*` schema prefixes |
| `Capability` | What a device can do (BLE sync, LAN sync, live stream, config, reboot) | Schema prefix checks in screens |
| `ObservedProperty` | One exact quantity, for example atmospheric PM2.5 or air temperature | `MetricSpec` in `Metrics.kt` |
| `Datastream` | One property measured by one instrument on one device | Parquet column |
| `Scale` | Optional interpretation of a value, for example the AQI bands | `AirMood` |

Rules.

1. **The dictionary comes from the device.** The firmware's versioned measurement dictionary is
   the source of property identity, unit, procedure and time semantics. The app ships a bundled
   copy for known schema IDs only as a fallback. The app never invents a unit or a band. The
   Settings **Units** choice only relabels a known SI unit for display (`:core:units`, for example
   °C as °F). The feed, the percentile scale and every statistic stay on the dictionary's unit.
2. **Unknown is fine.** A column that is not in the dictionary still shows with its raw name and
   no scale, at Detailed level. It is never hidden, coerced or given a default.
3. **Scales are optional and scientific.** A `Scale` names its source (for example US EPA AQI or
   WHO 2021). PM2.5 and PM10 use the WHO 2021 Air Quality Guidelines (`:core:airquality`,
   `WhoGuidelines`), six fixed steps from "meets the guideline" through interim targets 4 to 1
   to "above all targets", 24-hour means, in one colour-blind checked palette. A fixed step
   means a colour says the same thing in every place, viewport and window. Properties without
   a published scale (CO₂, temperature, humidity) use a neutral sequential colour ramp stretched
   over the cells in view, and say so. Colour never carries a step alone, its name is shown
   beside the value. Brand colours are never used to judge a value, and WHO colours are used
   only to judge values.
4. **Capabilities, not schema strings.** A screen asks `device.can(Capability.LanSync)`. Only the
   family registry reads schema IDs or adverts.
5. **One family entry per hardware line.** A `DeviceFamily` declares how to recognize the device
   (advert, manifest or schema ID), its transports, capabilities, dictionary and its headline
   properties (which one or two values the device card shows). Adding a family needs no change
   to the sync engine, the screens or the design system.
6. **Keep wire names.** Opcodes, UUIDs and archive
   paths stay. New shared types use generic names (`Reading`, `MetricCard`), never `Air*`.

### Adding a device family

1. The firmware repository documents the family's wire and measurement contract in
   `../opensensor-space-firmware/docs/shared/` (or its own contract repo).
2. Mirror the firmware's published board templates (opensensor-space-firmware `docs/shared/templates`, one
   `<board>/<dict>.json` per board plus `index.json` and `README.md`) into
   `core/model/bundled/templates/`, byte for byte, with
   `rsync -a --delete ../opensensor-space-firmware/docs/shared/templates/ core/model/bundled/templates/` from the
   repo root, and, if the derived default is not enough, add an
   `AppProfile` for that `dict` under `core/model/bundled/profiles/`. `BundledCatalog` reads the
   templates through `index.json`, which gives each its `desc`. `BundledTemplateBytesTest` checks each
   file's SHA-256 against its `index.json` desc and pins the full SHA-256 of every copied json file,
   so update its `pinned` map with the copy. `BundledCatalogTest` checks the profiles. The template
   links point at fixed aq commits, the app never fetches or trusts them, only the digests.
   A board whose firmware serves its template over `GET_DESCRIPTOR` works without a bundled one,
   the app fetches and caches it on connect (`BoardDescriptors`). It then renders with a profile
   derived from that template. A synced file whose footer `template.sha256` differs from the cached
   template's full digest evicts it, and the next connect fetches it again.
3. If it uses a new transport, follow "Adding a transport" in the cross-platform guide.
4. Optional. A family may provide a hero component (the air-quality mood face is one). Without
   one, the generic `MetricCard` is used.

## Part 2. Discover public sensors near you

Reassessed on 2026-10-08 against current primary sources (listed at the end). Every version
below was checked on that date. Items marked "verify on phone" are not yet proven on hardware.

### Decisions at a glance

| # | Decision | Section |
| --- | --- | --- |
| 1 | Map first. No sign-in, no onboarding wall, no permission prompt at launch | First screen |
| 2 | One non-modal bottom sheet with three heights. It never stacks a second sheet | First screen |
| 3 | Public values are hourly means, published at least one hour late. The time slider offers Last hour, 24 hours and 7 days, never "now" | Privacy rules |
| 4 | The crowd rule of three applies to every published number, not only the device list, with a differencing guard on parents | Privacy rules |
| 5 | Only outdoor devices can be published. No accounts, no passkeys. Each shared device gets its own anonymous key made on the phone, and its public ID and generated name come from that key, never free text | Privacy rules |
| 6 | The phone reads the same Parquet files as the website `/indices` explorer, one plain file per H3 resolution, through HTTP range reads and the repo's own portable Parquet reader. No JSON side feed | Feed contract |
| 7 | `maplibre-compose` 0.20.0 is the renderer. Its pins match ours (Kotlin 2.4.20, CMP 1.12.1) | Map technology |
| 8 | H3 runs on the phone (files carry no geometry). Viewport cover of at most 1,024 cells, one GeoJSON source, redrawn after 400 ms of camera rest | Map technology |
| 9 | An online Protomaps PMTiles basemap, an optional "Offline map" download in the app Settings (nothing bundled), and "© OpenStreetMap" always reachable | Map technology, Compliance |
| 10 | A "Near me" button asks for approximate location only when tapped. Precise location is never requested | Centring |
| 11 | Shared data is published under CC BY 4.0 and cannot be withdrawn. "Stop sharing" takes effect from the next UTC day | Privacy rules, Compliance |
| 12 | Hosting is source.coop (Radiant Earth partnership, AWS Open Data). The phone never holds source.coop write credentials | Feed contract, Compliance |

### UX benchmark

What comparable apps do, and what we take from them.

| App | First screen | Detail | Notes |
| --- | --- | --- | --- |
| PurpleAir map | Map, no sign-in | Popup with value, recent averages, a multi-sensor chart, status | Metric and averaging live behind a gear. Grey means stale and also "metric not measured". Name search only covers sensors on screen. Web app only |
| IQAir AirVisual | City cards, location asked early | Thing page with last update time | The map shows only PM2.5 things so values compare like for like |
| Sensor.Community | Hexagon map, no sign-in | Tap a hex for count, median and one row per sensor with 24 hour and 7 day means | Metric dropdown, collapsible legend per metric, indoor and reference toggles, a visible sensor counter |
| Apple Weather | Location prompt, then forecast | Air quality map overlay with a compact gradient legend | Overlay switch for temperature, precipitation and air quality |
| Google Maps AQ layer | Layers, Map details, Air quality | Coloured AQI bubbles, zooms out to an overview | Nested menu, hard to find |
| Eufy | Login and email verification before anything | n/a | Account wall |
| Google Home | Get started, Google account, location | n/a | Permission and account up front |
| Netatmo Weathermap | Map of user things, clustered averages when zoomed out | Thing values | Only outdoor modules are shared, opt in |
| Ambient Weather | Network map with layer toggles | Thing page | Stale styling undocumented |
| Windy | Map, layer picker with favourites | Thin colour bar legend, timeline at the bottom | Strong legend and time model |
| BreezoMeter | Now inside Google Maps Platform | n/a | Consumer app status unclear |

Patterns we copy.

1. Map first with no login (PurpleAir, Sensor.Community), the opposite of Eufy and Google Home.
2. The active metric and window are always visible on the map, never behind a gear.
3. A small legend anchored in a corner that expands on tap.
4. A hex shows one value plus a device count. Tapping it lists the devices (Sensor.Community).
5. Every cell and device shows when it last updated (IQAir).
6. Separate styles for "stale" and "this cell does not measure this metric" (PurpleAir uses one grey for both, which is ambiguous).
7. A non-modal sheet with a grabber and peek, half and full heights, with the map still usable (Material standard bottom sheet, Apple sheet detents).
8. The device page shows recent averages and a trend, not only a live value.
9. A count of sensors in view, so an empty area is obviously empty, not broken.

Anti-patterns we avoid. Account or permission walls before any value. Metric pickers hidden in
menus. Silently hiding cells that lack the chosen metric. Search limited to the viewport. Several
sheets stacked on top of each other.

### First screen at the Simple level

Zero taps shows the map, the metric chips, the time slider, the legend, the Sample and Real
toggle, and the sheet at peek height. Nothing else. Every explanation sits behind
an info button.

- **Top bar.** A search field ("Search places") and an "Add your sensor" icon button (`+`). The
  `+` is the only place on Discover that starts pairing, apart from the first-run card.
- **Metric chips.** One row. The chips are built from the properties the visible cells actually
  report, ranked by how many visible cells report them, so the row adapts to any sensor family.
  At most three property chips show, then a "More" chip opens the sheet at full height with every
  property grouped by the category the feed dictionary gives it (Air, Weather, Water, Soil, Noise,
  Energy). Choosing one from "More" moves it into the row. The last choice is remembered. Every
  property and category id the app knows is named in the app's language (`DiscoverLabels.kt`),
  on chips, legends, the sheet and what screen readers say. An id the app does not know shows
  the column title of the discover Collection, else the feed's own label.
- **Time slider.** A 44dp pill on the left edge of the map, not in the chip row. At rest it
  shows only the chosen window. A touch grows it into one snap stop per window the feed on
  screen offers, 1h at the top, then 24h and 7d (Real has no 1h stop, and a 1h choice moves to
  24h on screen while the saved window stays), with a tinted thumb that follows a vertical drag (a haptic tick at each stop).
  Tap a stop to pick it. It folds back about two seconds after the last touch. Screen readers get
  one control named Time window, its state is the full window name, and its actions pick the
  others. The chip row holds metrics only.
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
- **Legend (Simple, 2026-10-12).** For a WHO scale the floating card shows the six mood
  words with their colours, titled with the property, and no numbers. Its info sheet explains
  the moods without naming the guideline body and points to Detailed in Settings. A property
  without a health scale keeps the gradient legend. The card is one accessibility node that
  reads the title and the six words.
- **In view at Simple (2026-10-12).** The spread chart becomes a mood strip, one block per mood
  as wide as its number of areas, no axis. Tapping a block filters the map and the list to that
  mood, tapping it again or tapping the map lets go, and changing the property clears it too.
  A property without a health scale shows no chart at Simple. List rows name the mood word.
  The strip is one accessibility node, and its touch targets are 48 dp when tappable.
- **Cell sheet at Simple (2026-10-12).** Peacock and mood word beside the 24 hour mean, or "n/a"
  when that window has no value, one plain line, then a three block mood strip for 7 days, 24
  hours and the last hour, with a visible caption under each block. Selecting a cell reads the
  other windows for that one cell from the same feed the map uses
  (`DiscoverUiState.selectedWindows`). Real has two blocks, 7 days and 24 hours. Until the 24 hour row is read the sheet says "Loading",
  with no mood and no value. A failed window read is tried again after 1 s and 2 s. If it still
  fails it stays unread (`DiscoverUiState.failedWindows`), the sheet says "Not available right now"
  with a Retry button, and the next pan or Retry reads it again. A failure never reads as "Not
  measured". Loading and that line are polite live regions. The Real feed sits behind the Show public sensors switch. Then sensors,
  freshness and the other measurements as plain value rows, all from the 24 hour row. A property
  without a health scale shows only its number. Detailed and Developer are unchanged. The mood
  words live only in `:ui:designsystem`.
- **Cell styles.** Filled with the scale colour, the fixed step colour of the cell mean
  (`StepScale.colorOf`) for a property with a step scale, the percentile ramp for the rest. A stale cell (no hourly value for 3 hours) is
  drawn at 40 percent opacity with a dashed outline. A cell that does not measure the chosen
  metric is outline only, no fill. Selected cell has a thick ink outline. Staleness is judged on
  the chosen metric's own date, so a newer reading of another metric never hides it.
- **Sample and Real (2026-10-12).** A compact two-state toggle top right under the chips, a
  floating control with the brand blue only on the chosen half. Sample is the bundled fixture,
  Real is the OpenAQ aggregates feed, labelled with the day its data runs to in the phone's
  language, as in "OpenAQ, data to 8 Oct". The choice is saved (`DiscoverSource`) and Real is
  read only while Show public sensors is on (`effectiveDiscoverSource`). With the switch off the
  toggle is disabled and shows Sample, and screen readers hear how to turn it on. A switch of
  feed clears the rows, the selection and the window reads, then reloads. Real offers 24 hours
  and 7 days, so the cell sheet's mood strip shows two blocks. A failed Real read shows "Could not
  load public sensors" with Retry in the sheet, ahead of the first-run card, never the sample in
  its place. Each toggle half and the "Data OpenAQ" link keep their drawn size inside a 48 dp
  touch target. Choosing 24 hours in Real saves it even while a saved last hour is shown as
  24 hours. "Sample data" still
  heads every cell sheet while the feed on screen is the sample, and the info button explains the
  feed on screen. `DiscoverController.onSourceSettings`, `chooseSource` and `retry`.
- **Attribution.** "© OpenStreetMap" bottom right, visible at launch. It collapses to an info
  button after the first pan or after 5 seconds, as the OSMF guidelines allow. In Real mode it
  also shows "Data OpenAQ", which stays while Real is on screen and opens
  https://docs.openaq.org/resources/licenses.
- **First run.** The peek area holds one card instead of the summary. A 48 dp peacock, one line
  "Public sensors near you, shown as areas", and two actions "Add your sensor" and "Explore".
  Either action dismisses it for good. The card carries no numbers. The mascot stays away from
  charts and numbers at Detailed and Developer. At Simple the cell sheet places it beside the
  mood word and its one value, the only exception.

Sheet heights.

| Height | Nothing selected | Cell selected |
| --- | --- | --- |
| Peek (about 200 dp) | The spread chart of the chosen metric first, so it stays in sight while the map is explored, then "12 sensor areas in view" and the freshness, or the first-run card | The spread chart with this cell marked, then the headline value, device count and freshness |
| Half (Material half expanded, Apple medium detent) | The cells in view by value, filtered by the chart's touched or pinned bar | A range row per other metric the cell reports |
| Full (expanded, large detent) | Same list, scrollable | Metrics plus the device list (crowd rule met) |

Tapping a cell opens the sheet at half height. Tapping the map outside a cell returns to peek.
Back returns sheet to peek before it leaves the screen. On Android this is a
`BottomSheetScaffold` standard sheet. On iOS the same Compose sheet is drawn, sized to match
the medium and large detents.

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

**Cell tapped, crowd rule met (3 or more devices)**

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
|--------------------------------------|
| Sensors here                         |
| (air) Blue Heron 41   12   1 h ago > |
| (air) Quiet Fern 07   17   1 h ago > |
| (wx)  Amber Kite 22   21.0 2 h ago > |
+--------------------------------------+
```

Sheet charts (2026-10-09). The feed carries one window of statistics per cell (n, mean, min,
max), no hourly history, so the sheet draws no time series. Charts are Canvas in common Compose,
identical on Android and iOS, and every one carries a spoken summary of its values.

- **Chart and map cross filter.** Touch and slide across the spread chart to read each bar (its
  range, how many areas it holds and the step). While a bar is touched, areas outside it fade
  on the map and the list keeps only its areas. A tap pins the bar, a second tap or a tap on the
  map outside any area lets go. The other way, tapping an area marks it on the chart, and panning
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
  axis spans its min to max. "Higher than N% of areas in view" follows when three or more areas
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

**Cell tapped, fewer than 3 devices**

The cell itself is never drawn with its own value. The map shows the parent cell instead, so a
tap lands on the parent. When the user zooms in far enough that the parent is split, the
lonely child is drawn outline only, and tapping it shows this.

```
+--------------------------------------+
| ----                                 |
| Too few sensors here to show      i  |
| Nearby area                          |
| 11 µg/m³  Good                       |
| 9 sensors  Updated 1 h ago           |
|          avg     min     max         |
| PM2.5    11      4       29   µg/m³  |
| [Show the nearby area]               |
+--------------------------------------+
```

**Device page**

```
+--------------------------------------+
| <  Blue Heron 41                 i   |
| Sample data                          |
| Outdoor air  Updated 1 h ago         |
|                                      |
| 12 µg/m³  Good             (hero)    |
| Area average 14                      |
|                                      |
| (PM2.5) (PM10) (Temp) (More)         |
| (24h) (7d) (30d)   (Avg) (Min) (Max) |
|   chart, hourly, area average dashed |
|                                      |
| Air        PM2.5 12   PM10 18        |
| Weather    Temp 21.0  Humidity 47    |
|                                      |
| About                              > |
+--------------------------------------+
```

"About" holds the family, the data licence and the source link. Public device pages show
hourly values only. An owned device keeps its own full-resolution history from local storage,
and its settings hold "Share this sensor publicly", off by default.

**Navigation (owner decision 2026-10-08).** One bottom bar for the whole app, Discover, My
devices and Settings, drawn once at the app root on Android and iOS. Discover is always the start
tab, whether or not a device is paired. A device's Today, History, Files and Device sections are a
tab row inside the device screens, never a second bottom bar. Each tab keeps its own back stack
(`docs/screens.md`).

### Centring the map and Near me

The map never asks for location at launch. It opens on the first of these that exists.

1. The last viewed area (saved camera).
2. The cell of the user's own published device.
3. A region from the phone's IANA time zone, using a bundled zone to bounding box table. This
   lands on the right country or metro for most people, and on a continent overview otherwise,
   like Google Maps zooming out to an overview.

Search uses a bundled place list (GeoNames cities with population 15,000 or more, licensed
CC BY 4.0, credited in About). Search is local, so typing never leaves the phone.

**"Near me" (owner decision 2026-10-08).** A map button that asks for approximate location only
at the moment it is tapped, because a time-zone guess fails for large zones and store rules
allow it cleanly. Precise location is never requested on either platform.

- Android. Declare only `ACCESS_COARSE_LOCATION` (about 3 km², similar to a resolution 7 cell).
  Foreground only, so no Play location declaration or background disclosure is needed. Checked
  2026-10-09 against `targetSdk` 37. The Android 17 location button (Jetpack
  `androidx.core.locationbutton`) is optional and grants one time precise location, so the
  standard coarse runtime prompt stays.
- iOS. When In Use with `NSLocationDefaultAccuracyReduced` set to true, so the prompt offers
  approximate location by default. Search stays the alternative when the user declines (App
  Review 5.1.1).
- Use. The fix only moves the camera. It is never stored, never sent and never published, so it
  is not "collected" under either store's privacy rules.
- Built (2026-10-09, host builds only). A tonal my-location button sits bottom right above the
  attribution. One shared implementation in `feature/discover` `NearMe.kt` uses maplibre-compose
  location (0.19.0 when built, 0.20.0 now) (`rememberDefaultLocationProvider`, `rememberLocationState` with
  `LocationAccuracy.Low`, `LocationIndicatorLayer` for the puck). Only the prompt is per platform
  (`NearMe.android.kt` asks for `ACCESS_COARSE_LOCATION` alone, `NearMe.ios.kt` calls
  `requestWhenInUseAuthorization`). No provider exists before the tap. After a grant the camera
  moves to the centre of the resolution 6 cell holding the first fix (`nearMeTarget`), at zoom
  10.5, and the puck follows the fix while the map is open. `DiscoverPrefs` saves only the
  camera, and the camera never holds the fix, so the saved view is at most a coarse cell centre.

- Decline. The map stays where it is and search remains. Asking again happens only on the next
  tap, never on launch.
- The same approximate fix may preselect the cell in the sharing cell picker. The owner still
  confirms or moves the cell, and the fix itself is not kept.

`AGENTS.md` records this boundary.

### Privacy rules

Snapping a home to a hexagon does not make its data anonymous. Temperature, humidity, CO2 and
particle series reveal occupancy, cooking and even how many people are home. The rules below
treat each public series as personal data until it is aggregated.

1. **No points, only cells.** Public data carries an H3 cell, never coordinates. The owner picks
   the cell on the map when opting in, optionally starting from an approximate "Near me" fix.
   Coordinates never leave the phone. No jitter is added to
   cells, since snapping already coarsens the place and noise only helps for points.
2. **Precision.** Resolution 7 (5.16 km², edge about 1.4 km) is the finest published and the
   one stored for a shared device. Coarser levels are derived from it. Resolution 8 (0.74 km²)
   is never published, because Play treats location finer than about 3 km² as precise, and
   resolution 7 keeps both store labels at approximate location.
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
7. **Device identity, not user identity (owner decision 2026-10-08).** No accounts, no sign-in
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
9. **Stop sharing (owner decision 2026-10-08).** Stored with that device's settings. It takes
   effect from the next UTC day, so the current day's hours still publish and nothing after
   it does. Data already published stays public under its licence. Sharing can be turned on
   again at any time with a new identity (rule 7).

The publisher enforces every rule before a file is written. The phone never receives a
suppressed cell or a lone device, so a modified client cannot reveal one.

Differential privacy noise is not needed for this design. Count suppression, the differencing
guard and hourly coarsening are the practical baseline, and noise would make small cells
misleading. Revisit if public values ever become finer than hourly.

### Network boundary

This feature changes the offline-only stance in one narrow way. The app may make anonymous,
read-only GET requests to the public feed and the basemap. No cookies, accounts, identifiers or
analytics. A Settings switch, "Show public sensors", turns them off. When it is off or offline,
Discover shows the last cached snapshot or the bundled sample on the bundled basemap, and
pairing and sync work exactly as before. `AGENTS.md` records this boundary.

### Public feed contract (proposed)

The feed mirrors the website's `/indices` explorer (`walkthru-earth.github.io`,
`docs/indices.md` and `docs/parquet-producer-guidance.md`), so one dataset serves the website,
the phone and anyone with DuckDB. Static files on source.coop (owner decision 2026-10-08, a
Radiant Earth partnership on AWS Open Data), no server for reading. The publisher (not this
repo) runs once an hour, applies the privacy rules above, and writes immutable files.

**Upload path.** The phone never holds source.coop write credentials. Signed hourly uploads go
to a small ingest endpoint owned by the publisher, which checks the signature against the
device's registered public key and keeps raw rows private until the hourly run aggregates them.
Who builds and runs the publisher and ingest is still open.

```
opensensor/discover/v1/
  cells/window={1h|24h|7d}/date=YYYY-MM-DD/hour=HH/h3_res={r}/data.parquet
  devices/date=YYYY-MM-DD/hour=HH/h3_res={r}/data.parquet
  series/month=YYYY-MM/data.parquet
```

- **Discovery.** S3 `ListObjectsV2` on the bucket is the manifest, as on the website. The phone
  follows continuation tokens, picks the newest `date` and `hour` (compared as numbers), and
  caches the answer for 5 minutes. Downloads go through `data.source.coop`.
- **Cells file.** One plain Parquet file per resolution, `h3_res` 2 to 7. Columns are
  `h3_index` (INT64, sorted), `devices` (INT32, always 3 or more), `updated` (TIMESTAMP UTC) and
  flat metric columns `<property>__n`, `__mean`, `__min`, `__max` (DOUBLE, null when the cell
  does not measure it). Property IDs, units and families come from the dictionary named in the
  file's key value metadata. No geometry, no nested columns.
- **Devices file.** Rows `h3_index` (INT64, sorted), `public_id`, `name`, `family` and the
  latest hourly headline value per property, only for cells that pass the crowd rule.
- **Series file.** Hourly rows `public_id`, `hour`, then the same flat property columns, sorted
  by `public_id` then `hour`. A 30 day device chart reads one or two monthly files.
- **Writer rules for the phone.** SNAPPY (or LZ4_RAW) compression, not ZSTD, because Apple's
  Compression framework has no zstd and our reader keeps native code out of `:core:*`. PLAIN or
  RLE_DICTIONARY encodings, no DELTA, no BOOLEAN, no nested types. Min and max statistics on
  every column. Row groups small enough for pruning (start near 16,384 rows and measure), plus
  column and offset indexes. The website already reads SNAPPY.
  The owner plans to republish existing datasets with SNAPPY or LZ4 later. Until then W12
  builds on the sample feed, which already follows these rules.

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
   row group's `__n`, `__mean`, `__min` and `__max` columns for every property, plus the devices
   file for the same cell.

The reader is the repository's own `:core:parquet`, made portable in W8 (`ByteReader`, common
LZ4 and Snappy). Issue #62 added `ParquetReader` over a `RangeSource`, an in-memory LRU
`FooterCache` of footers and page indexes, coalesced range requests (`RangePolicy` `LOCAL`,
`CELLULAR`, `WIFI`) and pruning of row groups by footer statistics and of pages by the column and
offset indexes. `:core:net` adds `HttpRangeSource` over OkHttp 5.5.0 on Android and
`NSURLSession` on iOS. It sends `Accept-Encoding: identity`, so byte offsets stay true, and checks
`If-Range` against the ETag, so a replaced file is read again from its new footer. Host and
simulator tests only. `OpenAqAggregatesFeed` reads the OpenAQ aggregates through it (below).
Android `DiscoverViewModel` and iOS `AppRoot` each build one per process, read only while Show
public sensors is on and Real is chosen. Each file read goes through `CachedRangeSource` and the
disk `BlockCache` (#94), so a second open of a view reads cached blocks with no request. No third-party Parquet or DuckDB library exists for iOS that would do this
better.

Paths under `date=` and `hour=` never change, so footers and ranges are cached until the
newest hour moves on. The last complete hour stays on disk for offline use.

**Sample data.** `tools/discover-fixture/` writes the same layout and schema with Python `h3`
and PyArrow, including `sample = true` in the key value metadata, and the files ship in the app.
The phone reads them through the same reader from a local file source, so the sample and the
live feed share one code path. The toggle shows Sample while `sample` is true.

**Live feed, OpenAQ aggregates (current).** Until the opensensor publisher exists, the live feed
is the OpenAQ aggregates collection on source.coop, read with the same reader. Root
`https://data.source.coop/walkthru-earth/opensensor-space/openaq-aggregates/`.

- **Discover Collection (#189).** `collection.json` at the root is a STAC Collection read with
  `ThingCollection` from `:core:model` (`DiscoverCollection`). H3 only. One asset per
  resolution, keyed `h3_res=<n>`, with a relative href (no `..`, no absolute path, no scheme) or an https
  URL on the same host as the feed root. Any other asset, such as cleartext, another host or a
  local address, is dropped alone and the rest of the Collection stands. The zoom picks the resolution. A zoom whose resolution has no asset reads the
  nearest listed one, and between two equally near the one nearer 5, so a Collection that lists
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
  the Android step strings, held by tests). Either answer is kept for 5 minutes. A failed read
  throws and is not cached.
- `cells/h3_res=<1..9>/data.parquet`, one file per resolution holding every day since 2023,
  written by DuckDB. Verified on 2026-10-12 against `h3_res=3` (250 KB, 3,131 rows, generation
  `007d89a5...`): one row group, SNAPPY, dictionary encoded, min and max statistics, no page
  index, every column optional. Columns `h3_index` INT64, `date` INT32 with the DATE converted
  type (days since 1970-01-01, the thing local day), `devices` INT32 (things, 1 or more, no
  crowd rule, key `crowd_rule = none, public licensed things`), then `<id>__n` INT32 and
  `<id>__mean`, `__min`, `__max` DOUBLE, null when not measured. Rows sort by `h3_index` then
  `date`. Key value metadata `feed = openaq-aggregates`, `window = 1d`, `h3_res`, `generation`
  and `dictionary` (`pm25`, `pm10`, `pm1`, `no2`, `temperature`, `humidity`, `um003`). Only
  complete days (18 or more hours) are present, and data lags OpenAQ by about 72 hours.
- `ParquetFeedReader` reads this daily schema when `updated` is absent and `date` is present.
  Each row keeps its `CellRow.date`, its `updatedEpochMs` is the UTC midnight after that date,
  and an optional day range drops other days before a row is built. The hourly schema is
  unchanged.
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
- `DiscoverController` holds the sample and this feed. Real asks `latest()` on every load (the
  manifest is cached for 5 minutes) and keeps the last partition when a later ask fails. A cell's
  other windows are read 24 hours first, so a failing window never delays the headline. A pan
  rereads a failed window without flipping the sheet back to loading. A pan that picks up a new
  partition reads every window of the selected cell again, and a window read of an older partition
  never lands. A new `generation` under the same `as_of` is a new partition. Tests use a 13 row fixture from
  `tools/discover-fixture/make_openaq_fixture.py` served by a fake HTTP engine, and a STAC fixture
  from the same script with PM2.5 on the WHO scale and a soil property with a title and no scale. The live URL is
  not in the gate.

**What we do not copy from the website.** The EOX Sentinel-2 cloudless basemap, whose licence
restricts commercial use, and the deck.gl globe view. Some current website datasets also still
store H3 as strings with a geometry column in one large ZSTD row group. The opensensor feed
starts with the target layout from the producer guidance.

### Map technology

- **Renderer.** `org.maplibre.compose:maplibre-compose` **0.20.0** (2026-10-10), on
  `maplibre-native-ffi` 0.202610.0. Its own
  catalogue pins Kotlin 2.4.20 and Compose Multiplatform 1.12.1, the same as ours, so the
  `expect`/`actual` fallback is not needed now. Since 0.15.0 it renders through
  `maplibre-native-ffi` instead of the platform SDKs. Android needs minSdk 24 and one runtime
  artifact (`maplibre-compose-runtime-opengl-android`, Vulkan later after a phone check). iOS
  needs no CocoaPods or SPM, only `-lc++ -lz` and the system frameworks (CoreFoundation,
  CoreGraphics, CoreText, Foundation, ImageIO, Metal, QuartzCore), which fits our direct Xcode
  framework setup. Minimum iOS 15.5. Android and iOS are Beta and minor releases may break, so
  pin the exact version and upgrade deliberately.
- **Fallback.** If a future pin breaks it, wrap MapLibre Native directly behind the same
  composable. Current releases are Android 13.6.1 and iOS 6.31.0 (iOS 7.0.0 is in pre-release).
- **Cells.** One GeoJSON source built from the loaded rows, with a data-driven fill expression
  on the chosen value. No composable per cell. Each update replaces the whole dataset, so build
  it off the main thread and only after the 400 ms rest. The cover budget keeps it near 1,024
  polygons. Hit testing uses the layer
  `onClick`. Performance needs a phone measurement on both platforms.
- **H3 on the phone.** Required, because the files carry no geometry. Behind one interface in
  `:core:publicfeed`, `com.uber:h3` 4.5.0 on Android and the H3 C library v4 through cinterop on
  iOS, for `polygonToCells`, `cellToBoundary` and `latLngToCell`. `phansier/h3-kmp` vendors the
  same C library for both and is worth a review, but it is at 0.0.8. Descendant ranges need no
  library. A `commonTest` checks both platforms against vectors from Python `h3`.
- **Basemap online.** A Protomaps PMTiles extract hosted on our own storage, not hotlinked from
  the Protomaps daily builds. `pmtiles://` is in MapLibre Native since Android 11.7 and iOS 6.10,
  and the FFI build enables it, but `maplibre-compose` documents no PMTiles API, so it is
  "verify on phone" on both platforms. Style from `@protomaps/basemaps` 5.7.2, the `white` or
  `grayscale` flavour recoloured to the sensing palette, light and dark.
- **Right-to-left labels on iOS (#53).** Up to maplibre-compose 0.19.0 the Apple build of
  `maplibre-native-ffi` compiled a bidi stub, so Arabic names drew unjoined and in the wrong order
  (seen on an iPhone 16 Pro on 2026-10-08), and iOS loaded Latin-only label styles. ffi 0.202610.0
  links ICU on Apple and fixes Arabic shaping and right-to-left order (maplibre-native-ffi #799),
  and maplibre-compose 0.20.0 ships it. Android and iOS now load the same `light` and `dark`
  styles. Built and simulator tested only. Arabic labels on an iPhone still need a dated check in
  `docs/status.md`.
- **Basemap offline (owner decision 2026-10-08).** Nothing is bundled. The app Settings (not a
  device's settings) offer an optional "Offline map" download, a small world extract
  (`pmtiles extract --maxzoom` 3 or 4, a few MB, measured before release) with its glyphs and
  sprites, stored in app storage and removable from the same row. PMTiles sources are not
  cached by MapLibre, so without the download and without network the cells draw on the plain
  paper background.
- **Ornaments.** Use `MapOverlay.AttributionOnly` or draw our own attribution, scale bar off at
  Simple. Compass only after rotation. Flat Web Mercator, no globe.

### Store compliance

**W12 (read only, sample or public feed).**

- **Attribution.** "© OpenStreetMap" visible on the map at launch, collapsing to an info button,
  linking to openstreetmap.org/copyright (OSMF guidelines, ODbL Produced Work). The Protomaps
  credit and GeoNames (CC BY 4.0) sit in About, open source licences. Apple 5.2.2 expects
  permission for third-party content, which the licences give with attribution.
- **Privacy policy.** Required by both stores for every app, as a public URL in the console and
  inside the app (Settings, About). It names the feed and basemap hosts, says each request
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

**Once per-device publishing exists.**

- **What is collected.** Hourly outdoor readings and the chosen cell. Apple label "Coarse
  Location" (the cell) and "Other Data" (readings), not linked to identity, not used for
  tracking. Play "Approximate location" and the closest sensor category, collected, user
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
| `:core:model` | `BoardTemplate`, `TemplateKey`, `TemplateIndex`, `Datastream`, `ObservedProperty`, `Sensor`, `AppProfile`, `Family`, `ThingCollection`, `Scale`, `ValidityGate`, `Aggregate`, `Role` and the `Thing` join, parsed from stac-opensensor v0.1.0 JSON (#183). `BundledCatalog` and `LiveColumns` read the bundled templates and profiles in `core/model/bundled/` (#184). `Capability` ids and the `DerivedMetricProvider` seam live here too. `LiveReading`, `DeviceCapabilities` (a `Capability` set) and the batch estimate use them, `Metrics` still drives the screens |
| `:core:publicfeed` | `PublicFeed` and `FeedReader` ports, `ParquetFeedReader` (common, one `ParquetReader.scan` filtered on `h3_index` with page pruning, `CellsTable` reports pages read of total), `BundledSampleFeed`, the `H3Grid` interface (`com.uber:h3` 4.5.0 on Android and JVM, the vendored H3 C 4.5.0 through cinterop on iOS), `H3Index` bit arithmetic and descendant ranges, `ViewportCover`, `DiscoverCollection` (the discover STAC Collection and the bundled WHO PM scales), `CellScale` (`StepScale` from the property's published scale, else `PercentileScale`), `StepAxis`, `PropertyRanking`, `CellGeoJson`, `TimeZoneRegions`. Built 2026-10-08. `OpenAqAggregatesFeed` over `:core:net`, the daily schema in `ParquetFeedReader` and `DailyCellFold`, built 2026-10-12 (host and simulator tests only). Its reads go through the `:core:net` block cache (#94). Planned here: S3 listing for the opensensor feed |
| `:core:stac` | `FileItems.build` turns one verified device file, its `opensensor` footer and its `BoardTemplate` into a STAC File Item of the stac-opensensor v0.1.0 profile (815fc45), or a `FileItemResult.NoItem` reason (#190). Only dated `data_HHMM_` files with a Thing UUID, a mobility, a UTC time on every row and at least one H3 cell get one (`ITEM_NEEDS_LOCATION`, bridge question 41). A fixed Thing gets its cell polygon and `grid:code`, a moving Thing the union of its visited cells (`H3Grid.cellsToMultiPolygon`), coarsened to the coarsest resolution. The Item also carries `anon:size`, `anon:warning`, `file:size`, `file:checksum` and `statistics` per measurement column under its `valid_when` gate. `FileItems.sidecarPath` places it beside the file. Pure, no upload, built 2026-10-26 (host and simulator tests only, JVM tests validate against the profile schema). Nothing calls it yet |
| `:core:net` | `HttpRangeSource` and the `HttpRangeEngine` port, `OkHttpRangeEngine` (OkHttp 5.5.0, Android and JVM), `UrlSessionRangeEngine` (`NSURLSession`, iOS). Built with issue #62, used by `OpenAqAggregatesFeed` in both apps |
| `:core:parquet` | The portable reader from W8, with `RangeSource`, `ParquetReader` (footer cache, coalesced reads, one-pass decoding), row group pruning by INT64 statistics and page pruning by the column and offset indexes |
| `:feature:discover` | CMP `commonMain` map screen (`DiscoverScreen`), `DiscoverController` state holder, `DiscoverStrings` read from its Compose resources, one copy for both apps, the bundled sample and basemap styles as Compose resources. Targets Android (`com.android.kotlin.multiplatform.library`) and iOS, not the JVM, because the maplibre-compose desktop variant needs Java 25. Planned here: the public device page |
| `tools/discover-fixture/` | `make_discover_sample.py` writes the sample feed in the live layout (Python `h3` 4.5.0, PyArrow 25.0.1, SNAPPY, one row group per file sorted by `h3_index`, 16-row pages with page indexes, about 270 KB for 18 files, made-up cells around Berlin, Cairo, Lisbon, Nairobi, New York, Jakarta and Accra). `make_basemap_style.mjs` writes the light and dark styles from `@protomaps/basemaps` 5.7.2, each with a `-latin` variant for iOS. Output committed under `feature/discover/src/commonMain/composeResources/files/` |

`:feature:discover` is shared Compose, so Android and iOS show the same screens. Platform code
provides only the strings, the saved state (`DiscoverPrefs`, DataStore on Android, NSUserDefaults on
iOS), the H3 binding and later the HTTP engine and the cache directory.

**What the first slice does (W12, 2026-10-08, host, Android emulator and iOS simulator only, no
phone run).** Discover opens on the last camera or the time zone region, reads the bundled sample
through the common Parquet reader with row group and page pruning, covers the viewport with at most 1,024
cells at `round(zoom * 0.8)` clamped to 2..7, and redraws one GeoJSON source after 400 ms of camera
rest. Chips rank properties by visible cells (three, then More), a vertical time slider on the left
snaps between Last hour, 24 hours and 7 days, the legend shows the steps of the scale the feed publishes, WHO 2021 for PM2.5 and PM10 (host tests and builds only) and the 5th to 95th percentile of the cells in view on a viridis
ramp, and the sheet peeks with the first-run card or the area count and opens on a cell tap with
a spread chart of the chosen property and a range row per other property. Search moves the map over a small built-in city list. The basemap is
the Protomaps OpenStreetMap PMTiles build on source.coop (`pmtiles://` rendered on the Android
emulator and the iOS simulator), "© OpenStreetMap" shows at launch and collapses to an info button.
"Show public sensors" in Settings (both apps) swaps in a plain background style, so no request
leaves the phone. Not built yet. Near me, the GeoNames place list, the offline map download (TODO),
the half sheet height, stale outline dashes, the device list and device page, the live feed on screen, the
peacock on the first-run card and recolouring the basemap to the sensing palette. The basemap
still points at the Protomaps build on source.coop, not at our own extract. HTTP range reads use
OkHttp and `NSURLSession` behind `:core:net` (issue #62) instead of Ktor.

### Owner decisions and open questions

Decided on 2026-10-08.

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
   rule. The read only map in W12 works without it, on the sample data.

### Sources

UX and platform
- PurpleAir map guide, https://community.purpleair.com/t/purpleair-map-guide/90
- IQAir AirVisual, https://apps.apple.com/app/iqair-airvisual-air-quality/id1048912974 and https://www.iqair.com/support/knowledge-base/reported-aqi-levels-seem-lower-than-it-appears-visually
- Sensor.Community map, https://maps.sensor.community/
- Apple Weather, https://support.apple.com/guide/iphone/check-the-weather-iph1ac0b35f/ios and https://support.apple.com/102594
- Google Air Quality heatmaps, https://developers.google.com/maps/documentation/air-quality/heatmaps
- Eufy sign-up, https://service.eufy.com/article-description/Sign-up-for-A-eufy-Account
- Netatmo Weathermap contribution, https://helpcenter.netatmo.com/hc/en-us/articles/360020909052-How-does-contribution-to-the-Netatmo-Weathermap-work
- Ambient Weather Network, https://ambientweather.com/ambient-weather-network
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
- maplibre-compose releases, https://github.com/maplibre/maplibre-compose/releases and v0.15.0 notes https://github.com/maplibre/maplibre-compose/releases/tag/v0.15.0
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
- S3 ListObjectsV2, https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListObjectsV2.html
- hyparquet, the website's reader, https://github.com/hyparam/hyparquet
- Apple Compression algorithms (no zstd), https://developer.apple.com/documentation/compression/compression_algorithm
- zstd-kmp, https://klibs.io/project/square/zstd-kmp
- H3 Java, https://github.com/uber/h3-java , h3-kmp https://github.com/phansier/h3-kmp
- EOX cloudless licence, https://cloudless.eox.at/documentation/license
- DuckDB on Android, https://duckdb.org/docs/lts/dev/building/android
- Ktor releases, https://github.com/ktorio/ktor/releases
- source.coop range and ETag behaviour, checked with curl on 2026-10-08 against https://data.source.coop/protomaps/openstreetmap/v4.pmtiles

Store compliance
- OSMF attribution guidelines, https://osmfoundation.org/wiki/Licence/Attribution_Guidelines
- App Review Guidelines (1.2, 5.1.1, 5.1.5, 5.2.2), https://developer.apple.com/app-store/review/guidelines/
- App privacy details, https://developer.apple.com/app-store/app-privacy-details/
- Required reason APIs, https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api
- Play Data safety, https://support.google.com/googleplay/android-developer/answer/10787469
- Play privacy policy requirement, https://support.google.com/googleplay/android-developer/answer/10144311
- Play user-generated content, https://support.google.com/googleplay/android-developer/answer/9876937
