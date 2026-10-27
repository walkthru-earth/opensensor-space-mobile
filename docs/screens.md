# Screens, what the user sees, and which data path feeds it

**Scope:** navigation graph, every destination's behaviour, detail levels, the transport chip.
**Code:** `feature/shell` (`AppKey.kt`, `TabNavigator.kt`, `AppShell.kt`), `ui/Navigation.kt` (Android entries), `ios/shared/.../AppRoot.kt` (iOS entries), `ui/<screen>/*Screen.kt` + `*ViewModel.kt`, `data/settings/AppSettings.kt`.
**Related:** [history-pipeline.md](history-pipeline.md) (what History/Today read), [transports.md](transports.md) (what the Device screen writes), [background-sync.md](background-sync.md) (Auto-sync card, background runs).

## Navigation (Navigation 3 keys)

```mermaid
flowchart LR
  subgraph Bar["One bottom bar (app root)"]
    Discover["DiscoverKey<br/>Discover (start)"]
    Scan["ScanKey<br/>My devices"]
    Settings["SettingsKey<br/>Settings"]
  end
  Scan --> Today["TodayKey(address)"]
  Today <--> History["HistoryKey"]
  Today <--> Files["FilesKey"]
  Today <--> Device["DeviceKey"]
  Today -. Developer .-> Debug["DebugKey"]
  Files -. Detailed .-> Metrics["FileMetricsKey(thing, name)"]
  Discover -. Add your sensor .-> Setup["SetupKey(resume)<br/>Add sensor wizard"]
  Setup --> Today
```

**One bottom bar (owner decision 2026-10-08).** Both apps host the shared `AppShell`
(`:feature:shell`, [ADR 0001](adr/0001-shared-navigation.md)), one `Scaffold` whose bottom bar
(`BrandBottomBar`) holds Discover, My devices and Settings, around one `NavDisplay`. Android passes
its screens in from `MainNavigation`, iOS from `AppRoot`. No screen draws its own bottom bar. Discover is always the start tab, with or without a paired
device, and nothing is asked at launch.

- Each tab has its own Navigation 3 back stack, decorated separately, so an open device keeps its
  ViewModels while another tab is shown. The rules live in `TabNavigator` (plain class in
  `commonMain`, covered by `TabNavigatorTest` on the host and the iOS simulator). Every key is an
  `AppKey` saved through `AppNavConfig`.
- Reselecting the active tab pops it to its root.
- Back (Android predictive back, the iOS start edge swipe) pops the active tab's stack. At the
  root of My devices or Settings it returns to Discover. At the Discover root the Android app
  closes. On Discover the full property list of a cell returns to the cell first, and an open
  cell sheet returns to peek, through a `NavigationBackHandler`
  in the shared `DiscoverScreen` that wins over the shell's.
- Inside a device, Today, History, Files and Device are a tab row under the top bar
  (`DeviceSectionTabs` in the shared `ThingChrome`, with the thing header and link banner), siblings that replace each other. The bottom bar stays visible with My
  devices selected.
- The bottom bar is hidden only on Debug, Metrics and the Add sensor wizard, full-screen pages
  with their own back arrow.
- "Add your sensor" on Discover opens the Add sensor wizard over the My devices root, so closing it
  lands on My devices. A device screen that loses its session returns there too. Settings is reached only from the bottom bar.
  The wizard's Location page has no Skip. Next waits until the sensor confirms a saved H3 cell, and a
  setup left before Done resumes at Location.
- Each thing action has one home. Today holds Smart Sync, its Stop, Reconnect or Disconnect in the
  header, Forget in the header menu, and Debug at Developer. Files holds the archive list and Sync all. Device holds the sensor
  settings, Wi-Fi access, paired phones and Auto-sync. Every thing page reads one
  `ThingStatus` (connection, reconnecting, sync running, held in background). A page held by a
  background worker or Collect all says so and waits, it never offers Reconnect.

Every destination except Discover, Scan and Settings is keyed by the device's **Bluetooth address**, on both pipes, the
address keys the enrolled Room row and controller, including reconnect after a worker closes its
session. Archive/history use thing epochs; a full
`info.dev` check guards continuity. Returning to Devices retains other controllers and their work.

Each Navigation 3 entry owns its ViewModel and saveable UI state. Popping a detail page or
replacing a sibling tab clears that screen's jobs; the process-owned controller and any
WorkManager collection continue. Room and repository flows update visible screens without
manual reload. A screen refreshes device state after reconnect, when its request key changes,
or when the user requests it. Compose recomposition only renders the latest state.

## Detail levels (`DetailLevel`, DataStore)

| Level | Shows | Notes |
| --- | --- | --- |
| **Simple** (default) | The reading now (hero card), today so far, sync state in words with one primary action, the automatically synchronized clock, History and Files one tap away. Device settings as state in words and their actions | Designed first. No identifiers, hex, byte offsets, CRC, MTU, RSSI numbers, schema or firmware build strings, log tails or diagnostics |
| **Detailed** | + other particle sizes, particle counts, board temperature and battery, storage counters, batch estimate card, device card (firmware, protocol), **Finalize buffered rows**, Files chart and **Metrics** inspector (rows, time span, columns), transfer rate, the sensor, pairing and paired phones cards, token replacement | `AppSettings.detailed` |
| **Developer** | + identifiers and internals everywhere (thing, device and boot IDs, schema, dictionary, max_read, payload, endpoint, MAC, dBm, file names, partial counts, CF=1 raw values, codec, heap, uptime, footer, row groups, metadata) and the **Debug** destination | `AppSettings.advancedMode`. An old `advanced_mode = true` with no `detail_level` reads as Developer |

Only presentation changes. Every level reads the same data, and nothing the Simple experience
needs may depend on Developer. A new field states its level in code (`level.detailed`,
`level.developer`). In ViewModel state, `advancedMode` always means Developer and `detailed`
means Detailed or Developer. Files reads `detailLevel` directly.

Both apps use the shared detail-level setting and the same thing pages, so technical values stay behind
Detailed and Developer on both.

### What moved where (W4 audit, 2026-10-08)

| Screen | Simple | Detailed adds | Developer adds |
| --- | --- | --- | --- |
| Devices (Scan) | Name, paired and known tags, signal bars, archive line (mood for a banded metric, primary metric, day, synced ago), peacock guide when nothing is nearby, connect and privacy notes behind the top bar info button | Coverage percent and file count on the archive line | MAC address, RSSI dBm, thing prefix |
| Connection banner | Connected, Connecting, Setting up the link, pairing repair message | Failure reason | Service discovery, MTU, LAN authentication steps, reconnect reason and attempt count |
| Today | Hero by the profile `hero` (the peacock expression, number, mood pill, advice and snapshot note in its info sheet on air boards, the first primary reading, unit and scale step name on other things), today so far, temperature and humidity on ambient boards, Smart Sync, automatic clock state | Readings since midnight, placed and unplaced counts, Finalize, batch estimate, PM10 and PM1, particles, the other readings of a non air thing, board and battery with its state, storage (problem counters only when not zero), device card | Thing in the top bar, sample number, CF=1 raw, mV and USB mV, codec, partials, uptime, heap, dropped, errors and missed always, anchor generation, last sample instant, schema, IDs, dictionary, max_read, payload |
| Device | Device name and link in words, Auto-sync one line, wake when nearby, Wi-Fi state, scan and join, Sync over Wi-Fi switch and Get token. The pairing card only when it needs attention (default PIN, pending restart) | Firmware and protocol, sensor sticker card, pairing mode and Restart, paired phones and Clear, Replace token, server line, Wi-Fi and Bluetooth reachability lines, network name, passphrase state, IP address, discovery errors, phone installation record | Display flag, RSSI, MAC, access point security and channel, mDNS host, port, clients, discovered address, IDs, endpoint, settings read age |
| Files | Local time per file, friendly size, state, Sync all, peacock when all synced, file count | Chart by file, Metrics inspector, transfer rate, current file progress, LIST failure text | File names, UTC folder subtitles, partial and microSD counts, FILE frame mismatch, resumed offsets, recovered windows |
| Metrics (Detailed only) | n/a | Size, rows, columns, UTC first and last, valid particle rows, column min and max | Footer, codec, schema, writer, statistics, index source, decode time, uptime span, sequence, boot, row groups, column types and footer agreement, key value metadata |
| History | Chart, metric chips, range, archive summary, Current and Earlier thing chips | (unchanged) | Thing ID on chips, Developer source card |
| Debug | Not reachable | Not reachable | Whole screen (pops itself when the level drops) |

## Copy and the info affordance

Every card shows a title, its value or state in words, and one primary action. A card may add one
short supporting line when the state needs it (a warning, "Restart the sensor to apply the
change"). Anything longer lives behind a small outlined **i** next to the card title:
`InfoButton(InfoContent(title, paragraphs…))` in `:ui:designsystem` (`InfoButton.kt`), placed through
`CardHeader(title, info = …, trailing = { Switch(…) })`. It opens `InfoSheet`, a bottom sheet with
the heading and short paragraphs. Its content description is "More about <title>". The same
pattern serves radio options: pairing modes show only their label, and their descriptions are
paragraphs of the pairing info sheet. Confirmation dialogs stay (reboot, token rotation, forget
paired phones, pairing mode none, finalize) with one or two short sentences. Copy rules
in `cross-platform-guide.md` apply, and missing values print `NO_VALUE` ("n/a"), never a dash.
`CopyLintTest` in `:ui:strings` fails on a dash, semicolon (Arabic ؛ included) or sentence colon in any
`strings.xml`, every language included. The data layer
never writes sentences. Its snackbars are `DeviceMessage` codes (`data/DeviceMessage.kt`) that
`ui/components/DeviceMessages.kt` maps to resources, and exception detail stays in the log or a
Developer-level line.

| Screen | Behind the info button |
| --- | --- |
| Devices | How to connect, the pairing code, the privacy note. Collect from all |
| Today | Right now (snapshot, mood cues), Smart Sync, Clock (automatic UTC, finalize), batch estimate |
| Device | Auto-sync and wake when nearby, Wi-Fi (passphrase storage), Sync over Wi-Fi (token, local network), pairing modes, paired phones, sensor sticker and batch factor |
| Files | Chart by file legend |
| History | Valid readings, coverage, estimated time, earlier things |
| Settings | What a detail level changes, Smart Sync never rewrites files |

### Languages

English and Arabic. Android lists both in the system per-app language picker
(`res/xml/locales_config.xml`, Android 13 and later), iOS in the app's Language setting
(`knownRegions`, `CFBundleLocalizations`). Arabic lays every screen out right to left. Rows, cards,
the bottom bar and sheets mirror because layouts use start and end. Back and paging arrows mirror
through `autoMirror`. Canvas charts keep time running left to right in both directions. Digits
follow each platform's number formatting, so Arabic-Indic digits may appear in Android resource
strings. Both points need a phone check. How to add a language is in
[cross-platform-guide.md](cross-platform-guide.md#languages-and-right-to-left). The Arabic copy is
machine drafted and waits for a native speaker's review.

## Permission timing

There is no permission wall. The app opens on Discover and asks only from the tap that needs a
permission. The shared `:ui:permissions` module holds the policy for both apps. `PermissionTriggers`
is the one table from user action to permission, `PermissionFlow` decides ask, explain or settings,
and `RuntimeAccess` runs it. `PermissionFlowTest` (commonTest, Android host and iOS simulator)
covers the table, both decision paths and the iOS status mapping. The Android launchers stay in
`app/.../ui/permissions/RuntimeAccess.kt`, the iOS ones in `ios/App` and the Near me actuals.

An in-app rationale appears only when Android reports one is due. Android offers the app's
system settings page after a denial with no rationale due, but only when a rationale was due
before that prompt or the permission was already asked in this session. Closing a first-ever
prompt with Back or a tap outside is quiet, since Android 11 and later do not count it. The page
opens through `rememberOpenAppSettings` (the app details intent on Android,
`UIApplication.openSettingsURLString` on iOS). iOS shows each prompt once and never
explains first. A fresh "no" is quiet. The next tap, or the Bluetooth and Local Network messages
on the iOS screen, offer Settings. Restricted access gets no link, since the user cannot change it.
iOS has no Local Network status API (TN3179), so it stays unknown until the browser reports
`PolicyDenied`. Store answers live in [store-compliance.md](store-compliance.md).

| Permission | Android trigger | iOS trigger | Without it |
| --- | --- | --- | --- |
| Bluetooth (`BLUETOOTH_SCAN` neverForLocation, `BLUETOOTH_CONNECT`; iOS Core Bluetooth) | Scan, Collect from all, Reconnect, Reconnect over Bluetooth. Turning the adapter on uses the system dialog from the same tap. Opening a sensor never asks, it opens on its archive and connects only over a ready link (#147). Once held, Devices scans while visible | My devices Scan or Collect from all tap, and Reconnect on a sensor page or in Device settings. With existing access it also scans on tab entry. First access creates `CBCentralManager` | Archive views (History, Files) still open. A sensor opens without a link until Reconnect |
| Local network (`ACCESS_LOCAL_NETWORK`, API 37+; iOS Local Network) | My devices Scan tap, Sync over Wi-Fi switch on, Allow local network, Get Wi-Fi token. Discovery runs only once held | My devices Scan or an automatic scan with existing Bluetooth access starts `NWBrowser`; Find Wi-Fi sensors on the device page also starts a browse | Bluetooth sync works. The token fetch still runs |
| Notifications (`POST_NOTIFICATIONS`, API 33+) | Sync now, Sync all, file sync, Collect from all, Auto-sync on, Set up wake when nearby. Asked once per screen | Not requested | The sync runs, its progress notification is hidden |
| Camera (iOS only, AVFoundation video) | Not requested. Google Code Scanner runs in Play services with no camera permission | Scan on the sensor sticker card in Device settings, straight to the system prompt with no rationale. A later tap after a denial offers Settings. Restricted gets a note only | Typing the sticker always works |
| Location (`ACCESS_COARSE_LOCATION` only, never fine) | Discover **Near me** only moves the map. Device settings **Use phone location** obtains a one-shot fix and converts it to the selected H3 cell before UI or transport use. **Save on device** sends only that cell and optional declared country. No exact fix is stored or sent | Same explicit taps, When In Use with reduced accuracy | The map opens on the last area or the time zone region |

## Design system

The identity follows the website (`walkthru-earth.github.io`). Tokens live in the shared
`:ui:designsystem` module (`BrandTokens.kt`, used by Android and iOS) with the website role names (`main`, `ink`, `paper`, `surface`,
`accent`, `deep`, `text`, `muted`, `border`), light and dark, copied from `lib/brand.ts`
palette `sensing`. `BrandTokensTest` pins every value and checks text contrast on both themes.

- **Colour.** `brandColorScheme` maps roles to Material 3. Light primary is `ink` on `paper`,
  dark primary is `main` blue with `ink` text. The primary action (`PillButton`) and any
  selection use `FloatingControl.Selected` (Signal blue with ink) in both themes. The brand scheme is the default on
  every phone. Wallpaper dynamic colour stays off (identity first). Air-quality colours
  are the six WHO 2021 steps (`WhoStep.color`, mapped once by `WhoStep.color()` /
  `AirMood.color()`), a scientific scale, never brand colours, and Signal blue never judges a
  value. Ink on a step uses `onColor()`, step-coloured text uses `textColorOnSurface()` and falls
  back to the text colour where a step cannot reach 3:1 (yellow on light paper, maroon on dark paper).
- **Type.** Quicksand (OFL variable TTF from google/fonts) for text, Cairo (OFL variable TTF
  version 3.130 from google/fonts `ofl/cairo`, the same file as the website's) for Arabic-script
  locales, right-to-left layouts and display numbers, because Quicksand has
  proportional digits and no `tnum` while Cairo's digits share one width. Both fonts and the
  type scale live in `:ui:designsystem` (`Typography.kt`, Compose resources under
  `composeResources/font/`), and both apps wrap content in the one `OpenSensorTheme`, so they
  render the same faces. Each weight pins the variable `wght` axis through `FontVariation.Settings`, which
  Compose resources apply on Android and on iOS (Skia `makeClone`). The language comes from
  `Configuration.locales[0]` on Android and `NSLocale.preferredLanguages` on iOS.
  `TypographyTest` checks the Arabic-script and right-to-left rule, the tabular display styles and that every
  weight sits inside the bundled axis. License texts ship in `composeResources/files/licenses/`.
- **Shape and components.** One content card radius, 32 dp (`CardShape`, used by `BrandCard`,
  tones Plain, Soft, Hero). The components live in `:ui:designsystem` for both apps. Every
  control uses a curved card, never a circle or full pill. `PillButton` (Signal blue with ink
  text and a trailing arrow, the one primary action) and `OutlinePillButton` use
  `FloatingControl.Shape`. Chips, `MoodPill`, small buttons and segmented rows use
  `FloatingControl.SmallShape` (`FloatingControl.segmentShape`), tags and the transport chip use
  `FloatingControl.BadgeShape`. Selected chips, the active segment, the selected Wi-Fi network
  and the bottom bar indicator use `FloatingControl.chipColors`, `segmentedColors` and
  `FloatingControl.Selected`, Signal blue with ink text (6.5 to 1 contrast). The bottom bar is
  `BrandBottomBar`, one component for Android and iOS, its selected icon on a curved card, not a pill. Circles stay only for
  round things (status dots, the mascot, ring charts, radio buttons and switches).
  `SignalBars` completes the set.
  Icons are `AppIcons`, the Material vectors the apps use, vendored node for node so neither app
  ships `material-icons-extended`.
- **Floating controls.** Anything over the map or a list uses `FloatingSurface`,
  `FloatingIconButton` or `FloatingTextButton` (the Scan and Sync all actions) and the
  `FloatingControl` shapes and tint (curved cards, Signal blue only for the selection).
  The rules are in `docs/cross-platform-guide.md`, "Floating controls".
- **Mascot.** One peacock represents air moods and app states on Android and iOS. The original
  website reference (`ui/designsystem/src/commonMain/composeResources/drawable/peacock.webp`,
  from `public/mascots/`) stays the full welcome artwork. Compact, static portraits derived from
  that reference live beside it and are selected through `PeacockExpression`, `PeacockIcon` and
  `AirQualityPeacock` in `:ui:designsystem`. In-app portraits are at least 48 dp and keep the
  character's face, crest and feather gestures readable. They accompany live readings, archive
  summaries and History's ring, and replace generic faces or emoji in guide, empty, progress and
  success states. All art ships with the app and works offline, with no Internet asset fetch.
  The expression supplements the translated state or mood label, advice, value and unit.
  WHO thresholds, step colours and chart scales retain their scientific meaning. An image beside
  a label that already explains its state is decorative (`contentDescription = null`).
  Today uses one prominent portrait, 176 dp or 136 dp on narrow screens. Its head, crest and
  wing gesture extend above a smaller white backplate. The daily average and Smart Sync cards
  add their own values or state without repeating the character or the live mood label.

| Situation | Peacock expression |
| --- | --- |
| Fresh air / Fair | Happy / Calm |
| Getting hazy | Concerned |
| Poor / Smoky | Masked, with a serious expression |
| Thick smoke | Feather shield across the beak |
| Missing readings or an empty result | Confused, with the translated explanation beside it |
| Sensor warming up, stale or unavailable | Working, Sleeping or Concerned, with the sensor state in words |
| Collection in progress / verified success, in Files and notifications | Working / Happy |
| Android reconnect or sync failures | Concerned |

Android notifications use the same compact portraits as coloured large icons. Their small status
icon is a white-and-transparent peacock silhouette because Android tints notification icons.
Portraits preload off the main thread and are cached; the silhouette is also the immediate fallback.
The system controls notification icon sizing. This does not add an air-quality alert or an iOS
notification permission request.

## Route and screen split

Today and Device are a stateful `XRoute` (ViewModel, one-off events through `CollectEvents`
while STARTED, launchers, permissions, confirmation dialogs) and a stateless
`XScreen(state, onAction)` with one file per card in `ui/device/cards/`. Previews in
`ui/device/ScreenPreviews.kt` render light and dark at Simple and Developer from fake state.
The Wi-Fi `connecting` poll lives in `DeviceViewModel`. Capability questions (sensor sticker,
batch estimate, live profile, configurable) come from `DeviceCapabilities.from(info, live)` in
`:core:history` `commonMain`, never from schema strings in the UI. The 1 Hz clock is read only
by leaf texts (`TickingText`, `FreshnessLabel`), and the hero uses `rememberIsFresh`, which
flips once when a reading goes stale.

## Destinations

| Screen | Behaviour | Data path |
| --- | --- | --- |
| **Discover** | Public H3 map. Simple speaks in moods (mood legend, mood strip of areas, mood cell sheet). Detailed and Developer show the scale the feed publishes, WHO for PM2.5 and PM10. PM2.5 is labelled PM2.5 raw, because the feed's pre-aggregated means carry no humidity to correct with. A Sample and Real toggle top right picks the bundled sample or OpenAQ (Real only with Show public sensors on, labelled "OpenAQ, data to 8 Oct", no 1h window, "Data OpenAQ" attribution, a failed read shows Retry). See `devices-and-discovery.md` | `DiscoverController` over the bundled sample and `OpenAqAggregatesFeed`, saved `DiscoverSource` from settings on both apps |
| **Devices** (Home) | Scans for service UUID `255f595e-0001-…`, lists each board by its advertised name (`OS-xxxx` today) with RSSI + bond state, remembers known devices. Active controller rows show per-device connection/progress and open that controller. Each known device's card shows what the **phone archive** holds (files, last sync, last UTC day with valid readings of the thing's main metric as average + coverage at the profile display step in the owner's units, from `LatestDays`, with a mood when that metric has a WHO scale, PM2.5 corrected when that day has corrected buckets, else PM2.5 raw), no link needed. ≥ 2 known devices → **Collect from all N devices** (one worker pass, LAN if discovered + token else bond, up to two concurrent devices, missing files only; each already-owned device is skipped). One shared screen for both apps (`:feature:devices`, `MyDevicesScreen` over `DevicesController`). It merges nearby, known and Wi-Fi sensors into one list. Opening a sensor never asks for Bluetooth. It opens on the phone archive and connects only when Bluetooth is allowed and on, or over Wi-Fi when it has a link, and Reconnect in the thing header asks (#147). The Scan button floats at the end below the list, and the list stops above it, so no row is ever under it, at rest or scrolled, at any font size (#146). Existing access allows discovery while My devices is visible; Scan starts both searches and asks for missing Bluetooth and Local Network access from that tap. Either radio can still show results when the other is unavailable. A Wi-Fi result without a saved token first needs Bluetooth enrollment. Android collects in its worker. iOS collects one sensor at a time while the app is open. iOS cards show the same day line from its archive tiers. iOS link rows show connecting, connected, reconnecting (a dropped link under a run, or a paused Wi-Fi run) and failed (a lost bond), plus the Auto-sync mark. iOS has no pairing phase. | Ports `KnownDeviceStore`, `DeviceRadio`, `CollectAllPort`. Android `ScanViewModel` over `BleScanner`, `KnownDeviceDao`, `metric_bucket` DAY tier, `AutoSyncScheduler.collectAll`. iOS `DevicesBridge` over `CompanionState`, saved known records, `LanCredentials.list`, the Room index and `LatestDays.observe` over the archive tiers |
| **Today** | LIVE is read through the thing of the board's dictionary, its bundled template and app profile. CoreS3 shows board temperature (`imu_t`, never shown as air temperature), Waveshare ambient temperature (`t`) and humidity. Particle bins show only the keys the template declares. Cards follow the template, so a column it does not declare has no card. A null key or a declared key missing from a sample shows a dash, and an overflow sample clears every value. The particle cards also need the sample to carry the PM sensor state. Before INFO arrives the live card waits quietly. Only INFO with a dictionary the app does not bundle shows the explanatory unsupported placeholder, and collection still works. **Hero card** (`MetricHeroCard`, PM2.5 from `NowReading`, corrected from the live CF=1 value and RH when RH is valid, else the atmospheric raw value): one large six-mood peacock portrait rising above a small tilted backplate, big number labelled "PM2.5 corrected" when the live sample has a valid RH and "PM2.5 raw" otherwise (Detailed adds the raw value under a corrected one), mood pill, advice and comparison, a 10 second snapshot explained in its info sheet, **Today so far** = the *local* day's weighted average + coverage from the hourly tier, corrected PM2.5 when the day has it, else raw, with its label (re-evaluated every minute), or an indication that time synchronization is pending. Sensor state (warming up / stale / error) in words before any number. **Smart Sync** checks clock quality, synchronizes UTC automatically, downloads/verifies missing files and reports real/estimated/unplaceable readings plainly. **Devices** returns Home while retaining the connection; Disconnect closes only this device. Always: automatic clock state. Detailed adds **Finalize buffered rows**, every measurement card and the device card (`status.open`/`open_rg` only when present). Cards come from the template and the profile `primary` list. The profile `hero` picks a `FamilyPresenter` from `HeroRegistry`, `air-mood` for the bundled air boards. Any other thing gets `GenericMetricHero` and a metrics card, so a soil board shows soil cards. The board card shows the battery percent and its state from the template classes (Waveshare `bstate`, CoreS3 `chg`), in words beside the icon. **Forget** sits in the header menu, needs no link and asks first. Android keeps the system bond, iOS removes the app record and the Keychain Wi-Fi access and says the bond is removed in iOS Settings. One shared screen and copy for both apps (`:feature:today`, `TodayScreen` over `TodayController`), which owns the Finalize and Forget confirmation dialogs. Debug shows at Developer level only. On iOS an enrolled sensor opens the shared Today destination from My devices. Today reads INFO, STATUS and LIVE from an authenticated LAN session when present, and prefers it for Sync, UTC synchronization and Finalize; Bluetooth is used when LAN is unavailable. Device opens the shared Device settings, which borrow authenticated LAN when available, while obtaining or rotating the Wi-Fi token requires Bluetooth. The sync bar has no byte total. On iOS the freshness labels and sync times are the instants the messages and the run arrived, kept by `CompanionState`, so looking again never moves them. A sensor page the user left open reconnects when the app is back on screen, unless Collect all holds the link. Disconnect or leaving the page ends that. | Port `TodaySource`. Android `TodayViewModel` over the repository, `live`/`status` pushes, `DeviceCapabilities.reading(live)` + `NowReading.of(reading)`, `HistoryRepository`. iOS `CompanionTodaySource` over `CompanionState`, `IosArchive` and `BluetoothCompanion.command` |

For the reviewed Waveshare v4 dictionary, Today shows a separate **unvalidated batch estimate**
when the board has opted in, its sticker is in the known batch, and a fresh valid particle count
arrived after that config acknowledgement. The main Now value stays the hero PM2.5 above, corrected or raw with its label, never the batch estimate.
History exposes the archived candidate as a separate metric gated by candidate status 3; it has
no mood or standards band.
| **History** | Day · Week · Month · Year of one logger **from the archive** (no session), whole local days (1/7/30/365). Day = a local-day **ring** + one hourly column per elapsed hour (23 or 25 across daylight saving changes; repeated 01:00 hours show their UTC offsets). Each ring hour grows outward by its WHO step and sits on the neutral track inside a 3 to 1 border, so the step colour reads true and the step never rests on colour alone, and screen readers step through the ring hour by hour after its summary. At a font scale of 1.3 or more the day's value moves below the ring. Week = hourly columns. Month/Year = **local-day calendar heatmap** + daily/weekly columns. A calendar day fills its cell from the bottom by its WHO step inside a 3 to 1 border, Month rows are 48 dp tall so each day is a full touch target, and a month label that would overlap the one before it is left out. Bars are hourly, daily or weekly values rolled up by each metric's template aggregate, a mean unless the template says sum, mode, minimum, maximum or point (a caption says which), coloured by their own WHO 2021 step on a neutral background (neutral ink for metrics without a WHO scale), faded by coverage, hollow-dashed on estimated time; y axis from zero, 95th percentile with a floor, snapped up to a WHO edge for PM2.5 and PM10, off-scale spikes capped and labelled; dashed, labelled WHO 24 h guideline line for PM2.5 (15) and PM10 (45). The touch readout names the mood and WHO step in words. Neutral signed metrics include negative values and a zero baseline. Tap or drag sideways to read a column, a vertical swipe over the chart scrolls the page. Paging back uses local dates and stops at the first archived day, and a **Today** button returns to the current span. An open span follows the clock across local midnight. Metric chips come from the board template datastreams of the thing's files and show only metrics with valid archived buckets. They open on the first primary metric of the app profile, PM2.5 corrected on both bundled boards. Simple offers only the profile primary metrics, with PM2.5 corrected and not raw. A board with no primary metric offers them all. Detailed and Developer offer every measured metric, PM2.5 corrected then PM2.5 raw. A thing without humidity has no corrected buckets, so its first available metric (PM2.5 raw) replaces the default. Earlier authenticated thing epochs can be selected separately. One shared screen for both apps (`:feature:history`). On iOS it reads the whole phone archive and opens from a History button on My devices and on the device page. | `HistoryController` → `HistoryWindow` → `HistorySeriesReader.observe` → rows (Day) or tiers (Week/Month/Year); `Columns.rebinHours/rebinLocalDays/rebinLocalWeeks`. See [history-pipeline.md](history-pipeline.md#shared-screen-featurehistory-18) |
| **Files › Chart by file** | One column per synced file in **file order** (not time): a 3-row FLUSH file is as visible as a full window; estimated-time files hollow; unplaceable files say so; label jumps show where the logger was off. Metric chips follow History. The chart opens on PM2.5 corrected, Simple offers corrected only and Detailed adds PM2.5 raw. When no read file has a humidity reading it opens on PM2.5 raw and offers no corrected chip. A raw choice falls back to corrected when the level returns to Simple. 96 files/page, tap → name · rows · valid rows · span · mean/min/max. Archive only. | `HistoryRepository.fileSummaries` → `FileSummary`, chips from `fileChartMetrics` |
| **Files** | Device manifest grouped by **local calendar day** (dated files: the SD path `day=` and the name's `HHMM` are UTC, so `FileGrouping` converts the window start to the phone's zone; the header's subtitle names the UTC folder(s), each row shows its local start time) then unsynced-boot / benchmarks / legacy. **Sync all** downloads what the archive lacks with per-file + overall progress and "recovered N lost windows". Per-file state: not synced · partial · syncing · synced (verified) · removed from phone · mismatch · invalid name. A file the user removed in Settings stays removed and is not downloaded, and counts as done for Sync all. A `LIST` refresh (manual, post-connect, post-sync) keeps the previous list on screen, "refreshing…" while in flight, "Refresh failed: …, showing the last listing." on failure (`ManifestState.last`); the red full-width "LIST failed" card appears only when no listing exists yet. Share/Inspect resolves the exact storage `(INFO/enrolled thing, filename)`, including legacy/benchmark paths and retained producer-thing paths; missing ownership refuses a cross-device fallback. Rows have no tap of their own. Each offers its actions as separate 48 dp buttons that name the file, Share when synced, Sync while it is only on the sensor and the link is ready, and Metrics (Detailed). The no-listing failure card has a **Try again** button while the link is ready, and an empty phone archive shows a short guide. Byte counts read KB, MB and GB in steps of 1024, chosen after rounding so a count just under 1 MB reads 1.0 MB, through `byteSize` in `:core:units`, which the Android sync notifications and messages share. Auto-sync lives only in Device. One shared screen for both apps (`:feature:files`), the device listing, sync controls, link banner, section tabs and chart come in through slots on Android. Without a device listing it shows the phone archive grouped by local day. On iOS it lists the whole phone archive and opens from a Files button next to History on My devices. | `FilesController` (`:feature:files`), `SyncPlanner`, repository `sync` state |
| **Export** (from Files) | Share one file, share all files loose, share or save a zip of the raw archive, and **Share daily files (derived)**, which zips `derived/v1/<thing>/day=….parquet` and manifests (Android only, iOS has no derived files yet). Android shares through the chooser and saves through SAF. iOS shares through `UIActivityViewController` and saves through `UIDocumentPickerViewController(forExporting:asCopy:)`. Every share, and the iOS save, stages copies in its own run directory (Android `cacheDir/export`, iOS `tmp/opensensor-export`), removed once the sheet closes. Runs coexist, so a later export never removes a file an earlier share still offers or another thing's controller is still staging. A run that is staging or shown holds a lease in `ExportRuns`. An Android share has no close signal, so its run is released when the chooser opens and removed by age, one hour after that, by the next export's sweep or the startup janitor. A leased run is never swept (#161). The Android **Save zip to…** streams the zip straight into the `CreateDocument` target through `ExportPresenter.saveStreaming`, so it needs no cache copy as large as the archive, and a failed write deletes the half document. Shares stay staged because the chooser gives no close signal and its targets read a finished file by URI, often with its size, after the chooser returns. A pipe would have no owner to keep it open. The daily zip is share only, so it stays staged, and adding a daily save would be a new menu entry. iOS `UIDocumentPickerViewController(forExporting:)` exports an existing file URL, so its save stays staged. A staged zip is checked against the free space of that directory, and an export that would not fit says so before anything is written (#61). Every zip goes through `ArchiveExport.writeZip`, which closes each source after its copy. Every export holds exactly the verified records the page lists, by their keys, the connected thing's alone when it is known and every thing's on the phone archive page. A final without an index record, such as a `PAR1` failure the janitor keeps, is never exported. A listed final that is gone by the time its zip is written is left out, and `writeZip` and `StagedFile.skipped` count it. The Files snackbar says how many were left out once the sheet or picker returns. The zip fails when no Parquet file is left, so a daily zip never holds manifests alone. Any other open or read error, such as a denied permission, still fails the export (#168). The daily zip keeps only the `derived/v1/<thing>/` folders of those same things (#156). Exports only read the archive. | `FilesController` over `ArchiveExport` (`:core:archive`), `ExportPresenter` per platform |
| **Device** | **Auto-sync** card (any proto): per-device switch; **wake when nearby** line + *Set up wake when nearby* (one system dialog; a BLE session is dropped for it and restored) / *Turn off*. Waveshare: **air quality sensor** card selects the supported Plantower PMS5003T, scans its barcode with Google Code Scanner or accepts manual sticker entry, shows the batch, and offers an opt-in unvalidated batch estimate for the reviewed 2026-04-08 batch. Board acknowledgement precedes the phone's installation history entry. Barcode scanning may download a Google Play services module on first use; manual entry remains available. v2 (Detailed): Bluetooth pairing mode (Random code, Fixed PIN, No code, descriptions in the card's info sheet), the fixed PIN typed masked with *Show PIN*, dropped when Fixed PIN or the page is left. While the PIN or Wi-Fi passphrase field is on screen it is kept out of captures, `FLAG_SECURE` on Android, and on iOS a cover before the app switcher snapshot plus a forced mask while the screen is recorded or mirrored. iOS cannot block a still screenshot, so a screenshot hides a revealed PIN or passphrase again (#164). **Restart device** (confirm). The default PIN warning and the pending restart notice also show at Simple. **Wi-Fi**: state/IP/RSSI, *Scan networks* → pick → hidden passphrase → *Save and connect*, polled while `connecting`, radio on/off. **LAN**: server on/off, token held?, *Get Wi-Fi token* (BLE only), *Replace token* (Detailed, confirm), *Allow local network* (API 37+, asked from the action). **Clear paired phones** (Detailed, confirm). Wi-Fi has no separate Check again, the state refreshes while the page is open. Reconnect sits in the top bar only when the thing offers it. It and Reconnect over Bluetooth ask for Bluetooth from the tap. Every write is one validated `SET_CONFIG`. The cards, the four confirmations and their copy are one shared section (`:feature:device`, `DeviceConfigController`). Android draws them inside its Device screen. iOS shows them as the Device section of a thing (`DeviceKey` and `DeviceSettingsKey`, an Auto-sync switch first for a known peripheral), over authenticated LAN or Bluetooth, with the VisionKit sticker scan (Scan shown only on A12 and later, camera asked on that tap) and manual entry, and no installation history or endpoint line (parity P53 to P63). Worker-owned sessions mark controls busy; interactive sync/config/raw commands wait for that run to finish. Disruptive buttons are disabled while a sync runs (`canDisrupt = canEdit && !syncRunning`). | `DeviceSession.getConfig/setConfig/wifiScan/reboot/getToken` via repository |
| **Settings** (tab) | **Detail level**. Device UTC synchronizes automatically with no clock setting. **Show public sensors**, the one switch for Discover network use. **Units**, Automatic (default, the phone's measurement system, imperial for the US, Liberia and Myanmar), Metric or Imperial, applied live to every reading, chart label and spoken summary on Today, History, Files and Discover. Conversion lives once in `:core:units` (°C to °F, hPa to inHg, mm to in, m/s and km/h to mph, m to ft, km to mi and a few more). µg/m³, ppm, %, mV and unknown units pass through. Bands, moods, colours, chart geometry and stored data stay SI. One shared screen and copy for both apps (`:feature:settings`). Both platforms use automatic UTC regardless of legacy clock preferences. Notes behind the info button. **Storage** shows sensor files, History cache, map cache and temporary files in MB, with Clear on the two caches. **Old format files** (info button) finds device files in the old layout, for all dates or a UTC date range limited to the days the phone archive spans plus one, shows the count and MB, and removes them from the phone after one confirmation. Files that could not be read are shown as a count, never as none found. It may run while a sync runs. After a removal the card shows what went, how many could not be removed, how many stayed, and when History updates later. A removal that throws shows its own failure line. Nothing is sent to the device. **Removed files** shows how many removed files sync skips, and **Download again** (one confirmation) forgets them, so the next sync downloads them again when the device still lists them. Other sensor files are never deleted. Each Storage row reads as one sentence with its size or status, and its buttons name what they act on. About shows the version, the one line privacy note and the font licence. Device settings stay in the Device section. | `SettingsController` over `SettingsRepository` (`:core:settings`) and `StorageControls` (Android `AppStorageControls`, iOS `IosArchive.storageControls`), old format cleanup in `LegacyFileCleanup` (`:core:history`), removed files in `PrunedFiles` (`:core:archive`) |
| **Metrics** (Detailed) | Shared on both apps (`:feature:files`). One archived file through the app's own reader: rows, row groups, schema/writer version, codec, footer size, UTC/uptime span, sequence range, valid rows of the headline metric, per-row-group spans from footer statistics, every column decoded (nulls/min/max/first/last/"footer agrees", only the first and last value formatted), key/value metadata. A geometry cell shows a point as `lon` and `lat` to five decimals, the H3 cell centre the file holds, and any other shape by its type. At Developer level a Rows card pages through the rows by row index, 20 at a time, decoding only the row groups that hold the page and formatting only its rows (#98). A page that cannot be read shows "Rows could not be read" with a retry button instead of a spinner. The archive copy is never touched. | `FileInspector` over `ParquetFile` (`:core:parquet`) |
| **Debug** (Developer) | Active transport + endpoint + `payload_max`; Smart Sync decision trace and archive time accounting (real / same-boot estimate / unplaceable); live `status` JSON; last 200 raw frames (dir, type, len, first 16 bytes; `TOKEN` and `SET_CONFIG` redacted); **Device log** (`LOG_TAIL 8192`); **Raw command** (hex → decoded frames; refuses `REBOOT` and `SET_CONFIG`, which need the Device screen's confirmation). | `FrameRecorder`, `DeviceSession.raw`, repository diagnostics |

The Debug screen is one shared Compose screen in `:feature:debug` (`DebugScreen`, `DebugController`)
for both apps. A platform lends it a `DebugSource`. Android adapts the connection repository and its
long-lived session (`RepositoryDebugSource`). iOS takes a lease of the connection's `IosLink` for each
request (`IosDebugSource`) and keeps one frame recorder per sensor (`FrameRecorderRegistry`), so the
frame history covers Bluetooth and Wi-Fi syncs and debug requests alike. The denylist
(`RawCommandPolicy`, `REBOOT` and `SET_CONFIG`) and the redaction (`FrameRedaction`) live in
`:core:sync` and are checked twice, in `RawCommandSender` and again in `DeviceSession.rawCommand`.
Status and config JSON pass through `FrameRedaction.scrubSecrets` before they are shown. The shell
opens Debug only at the Developer level (`TabNavigator.openDebug`), and the screen pops itself when
the level drops. iOS opens it from Today at Developer too, and requests borrow authenticated LAN or Bluetooth. iOS feeds
the Smart Sync trace card from `IosSyncDiagnostics`, and reads the config on request.

## The transport chip

Top bar of Today, Files, Device and Debug shows **Bluetooth** or **Wi-Fi LAN**. Single source:
`DeviceFacade.activeTransport` (re-evaluated on every session swap *and* connection
state change, de-duplicated). Never derive it per screen from the session object
([lesson](lessons-learned.md#ui--compose)).

## Uses → affects

| Edit… | Affects… |
| --- | --- |
| `feature/shell/.../AppKey.kt` | `AppNavConfig` saved state, Android and iOS entry providers, `AppNavConfigTest` |
| `DetailLevel` / `AppSettings` | every `detailed`/`advancedMode` collector; Debug visibility; Files table icon |
| `ui/designsystem/.../PeacockExpressions.kt`, mood copy in shared `strings.xml` | Live and archived air moods, empty and guide states on both apps, Android notification portraits; the metric registry still owns labels, units and scales |
| `ui/theme/BrandTokens.kt` | Every screen, `BrandTokensTest` (website values and contrast) |
| `DeviceCapabilities` (`:core:history`) | Today hero and batch card, Device sensor card, `setSensorIdentity` guard |
| `BandColumnChart.kt`, `DayRing.kt`, `CalendarHeatmap.kt` | History and Files › Chart by file (share `Columns` + `HistoryColumn`) |
| `feature/files/.../FileGrouping.kt` | Files list headers on both apps and per-row local time (pure: `zone`/`locale` parameters, `FileGroupingTest`); the firmware's `day=`/`data_HHMM_` naming in `../opensensor-space-firmware/docs/shared/telemetry-pipeline.md` is what it parses |
| `DeviceRoute` confirmation dialogs | the **only** call path to `rebootAndReconnect()`. Keep it that way ([sync-engine.md](sync-engine.md#smart-sync-and-state-changing-requests)) |
