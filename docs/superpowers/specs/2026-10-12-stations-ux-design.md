# Stations UX redesign

**Date** 2026-10-12. **Status** draft for owner review.
**Owner decisions (2026-10-12).** Approach A, five slices in order. No app side backward
compatibility and no migrations. Remove legacy and unused code. The firmware wire contract
(`../aq-parquet/docs/shared/`) is unchanged.

## Goal

A normal user opens the app and sees, at a glance, how the air is at their own sensors and
at public stations near them, with the peacock. First setup is a short guided flow. Settings
hold only things changed later. Simple never shows technical charts or the word "WHO".

Success means a first time user can add a sensor, read its mood, and read a nearby public
station's mood without seeing an axis, an identifier or a standard's name.

## What stays fixed

- Discover is the start tab. The bottom bar is Discover, **Stations** (was My devices), Settings.
- Moods and colours come from the WHO 2021 steps (`WhoStep`) at every level. Simple only
  changes the wording and the drawing. Detailed and Developer keep today's charts and WHO labels.
- Privacy boundaries in `AGENTS.md`. Phone location is asked only from a tap, approximate,
  converted to an H3 cell at once, never stored. Uploads stay off and out of scope.
- Copy rules. No dashes, colons or semicolons in strings (`CopyLintTest`). Minimal copy, details
  behind the info button. English and Arabic.

## Slice 1. Shared mood model, MoodStrip, Simple Discover

**Model** (`core/airquality/.../StationSummary.kt`, commonMain, keyed on `WhoStep` so Discover
does not depend on `:core:history`).

```kotlin
data class MoodBlock(val step: WhoStep?, val weight: Float = 1f)   // null step = n/a, never guessed
data class StationSummary(
    val propertyId: String, val value: Double?, val unit: String,
    val step: WhoStep?, val scaled: Boolean, val updatedEpochMs: Long?,
    val blocks: List<MoodBlock>,                                   // oldest first
) { companion object { fun of(propertyId: String, unit: String, value: Double?,
        updatedEpochMs: Long?, history: List<Double?>): StationSummary } }
```

A property without a WHO scale has `scaled = false` and no blocks. The number still shows, no
mood is invented. `core/publicfeed/.../PublicSummary.kt` builds the public summary from three
windows (7 d, 24 h, 1 h, headline is the 24 h mean). Slice 2 adds the owned builder.

**Composables** (`ui/designsystem/.../MoodStrip.kt`). `MoodStrip(blocks, captions, onBlock)`
draws weighted rounded blocks with no axis. A null block is hatched and spoken "n/a". It mirrors
in right to left. `MoodLegend` shows the six mood words with swatches. `moodWord(step)`,
`moodLine(step)` (one plain sentence, no "WHO") and `peacockExpressionFor(step)`. The strip and
legend are each one accessibility node with a spoken summary.

Mood words move into `:ui:designsystem` strings (`mood_fresh` … `mood_thick`, `mood_none`,
`mood_*_line`). The duplicate `mood_*_title` keys in today, history, devices and `app/` are
deleted, so no screen prints an English mood name under Arabic.

**Discover at Simple.** `DiscoverScreen` takes `level`. Android reads it in `DiscoverViewModel`,
iOS passes the level `AppRoot` already holds. The controller stays level agnostic and, on
`selectCell`, reads the other two windows for that one cell (same anonymous feed GETs, gated by
Show public sensors).

| Surface | Simple | Detailed, Developer |
| --- | --- | --- |
| Legend | `MoodLegend`, info sheet without WHO | unchanged WHO step bar and captions |
| In view | six block strip weighted by areas per step, tap highlights that step on map and list | spread chart unchanged |
| Cell sheet | peacock, mood word, value or "n/a", mood line, three block strip (7 d, 24 h, 1 h), sensors, freshness. Other metrics as plain value rows | unchanged |

`SimpleCopyTest` fails if any `mood_*` string contains "WHO" or its Arabic name.

## Slice 2. Stations tab and Station page

**Modules.** New `:feature:stations` replaces `:feature:devices` and `:feature:today`, both
deleted. The pure collect all and ordering rules move in. The nearby radio model moves to slice 3.

**Navigation** (`feature/shell/AppKey.kt`, `TabNavigator.kt`).

```kotlin
data object StationsKey                           // tab root, was ScanKey
data class OwnedStationKey(val address: String)   // was TodayKey
data class PublicStationKey(val cell: String)
data class LiveDetailsKey(val address: String)    // Detailed
data class DeviceSettingsKey(val address: String) // DeviceKey merged in, both apps
data class DiscoverKey(val focusCell: String? = null)
// kept HistoryKey, FilesKey, FileMetricsKey, DebugKey, SettingsKey. SetupKey in slice 3.
```

`DeviceSectionTabs`, `openSection` and `isDeviceSection` are deleted. Every page is a plain push,
so Back always returns to the Station page. `focusOnMap(cell)` selects Discover with that cell.

**Owned sensor H3 cell.** Stored on the known sensor record as `cellHex` and `cellRes` only.
1. Config readback (authoritative). After each location read or save, and once after INFO when
   no cell is known. The cell centre lat and lon from `LocationConfig` are dropped, never stored.
2. Newest synced file (offline). `ParquetIndexer` reads footer statistics of `h3_cell_id` and
   `h3_resolution`. A file whose min and max differ yields null. `ArchiveIndex.observeLatestCells()`.

A cleared location clears the stored cell. Android excludes the columns from cloud backup.

**Stations tab.** `StationsController` emits `yours` and `nearby` lists of cards built from
`StationSummary`.
- Your stations. Connected first, then newest reading, then none. Value is the live reading when
  connected, else the newest 15 minute bucket from `HistoryTiers`, the same on both apps, which
  gives iOS a mood on the card for the first time. Older than 3 hours is stale (Sleeping peacock,
  "Old reading", dimmed number). A sensor with `setupDone = false` shows "Finish setup".
- Nearby public. Hidden when Show public sensors is off (no request). Seeds are owned cells. With
  no owned sensor, a Near me card makes a one shot coarse fix, converted to a resolution 5 cell in
  memory and dropped. Up to 6 cells from the 1 h window, owned cells excluded, ranked by distance
  between cell centres, widened once when fewer than 6. Offline shows "Nearby needs internet".
- Floating "Add sensor" opens the wizard. Overflow holds Collect from all (2 or more sensors).
- The controller keeps the quiet mDNS browse so added sensors still reconnect over Wi-Fi. There
  is no scan list.

**Station page** (`StationScreen(state, onAction)`, one layout).

| Part | Owned | Public |
| --- | --- | --- |
| Hero | peacock, mood, number, mood line, link state in words | same, "Public" tag |
| Strip | Day, Week, Month from `HistoryTiers` | three blocks, 7 d, 24 h, 1 h |
| Action | sync line ("Synced 5 min ago") with Sync. Clock shown only when not synced | See on map |
| More rows | Detailed adds Live details, History details, Files. Developer adds Debug | none |
| Top bar | gear to Device settings, overflow Disconnect | none |

Today's cards move as follows. Hero and today so far become the hero and the Day strip.
Environment becomes one line under the hero. Batch estimate, readings, particles, board, storage,
device info, clock and Finalize move to `LiveDetailsScreen` (Detailed, "Connect to see live
details" without a link). Pages above the current level pop themselves.

## Slice 3. Setup wizard

New `:feature:setup`, full screen, no bottom bar, from "Add sensor" or "Finish setup".

`FIND → NAME → LOCATION → WIFI → DONE`. Location and Wi-Fi can be skipped. Back goes one step,
but stops at NAME after pairing (unpairing belongs to settings). Closing after pairing asks first,
and the sensor stays added with `setupDone = false`. No step progress, location draft or password
is persisted. Resuming opens at NAME and reconnects silently.

- **Find.** The BLE scanner moves out of `ScanViewModel` into an app scoped radio used only here.
  Rows already added carry "Added" and start a re-run. Below proto 3 fails with "Update firmware".
  Pairing failures map to one message each. Wrong PIN and cancel look the same on both platforms.
- **Name.** Phone only nickname, 1 to 32 characters, in its own field (`nickname`, `setupDone` on
  `KnownDevice`, `KnownRecord`, `KnownSensor`) because the advertised name overwrites `name`. It is
  never sent in a frame, patch or log. Display name is `nickname ?: name`.
- **Location.** `DeviceLocationForm(state, onAction)` extracted from `DeviceLocationCard`, shared by
  the wizard and settings. Old firmware shows a one line note and Next.
- **Wi-Fi.** Join through `SET_CONFIG` over Bluetooth, then "Turn on Wi-Fi sync", which asks for
  local network and fetches the token on iOS (Android already holds one after pairing).
- **Done.** The Station summary, "Warming up" until the first live reading.

**Known record timing.** Android currently inserts the known row before the link opens
(`DeviceLinkOwner.kt:373`), so a failed pairing leaves a ghost entry. It is removed, and the row is
written only after INFO and the identity check, as iOS does.

Permissions. Bluetooth from Find, approximate location from Use phone location, local network
from Turn on Wi-Fi sync.

## Slice 4. Device settings

One shared `DeviceSettingsScreen` for both apps, opened from the gear. Android `DeviceScreen`,
`DeviceInfoCard` and the old `DeviceConfigScreen` are deleted. An attention banner replaces the
pairing card at Simple (default PIN, restart pending).

| Group | Simple | Detailed | Developer |
| --- | --- | --- | --- |
| Location | H3 area with Use phone location and Save, country | cell id, resolution | |
| Connection | Wi-Fi join, Sync over Wi-Fi switch (fetches token when missing), Auto-sync, wake when nearby (Android) | IP, signal, token line, reachability | AP security, channel, mDNS host, port |
| Sensor | nickname | sticker and batch estimate, firmware and protocol | IDs, MAC, endpoint, read age |
| Advanced | | pairing mode, Restart, Replace token, Forget paired phones | |
| Footer | Forget this sensor | | |

Every confirmation stays in `DeviceConfigController.confirm()` (reboot, token rotation, forget
bonds, pairing mode none, Finalize, forget sensor), which refuses while a sync runs. Forget lives
only here, not on cards. Phone side items (nickname, Auto-sync, wake, Forget) work offline. Device
items show "Connect to change this" with Reconnect. Settings use authenticated LAN when present
on both apps, else Bluetooth. Getting or rotating the token stays Bluetooth only. The Android only
Auto-sync card and iOS `AutoSyncSwitch` merge into a shared `AutoSyncSection`.

## Slice 5. Legacy and unused code

Code the redesign replaces is deleted inside slices 1 to 4. This slice removes the rest.

**Remove.**
- Shims. The `DeviceConnectionRepository` typealias (done 2026-10-12), old
  `AutoSyncRunner` names, `advancedMode`, `setAdvancedMode`, the `advanced_mode` key, the iOS
  `UserDefaultsSettingsMigration`, `RuntimeAccess` re-export.
- Clock sync mode setting end to end, keeping only automatic UTC.
- Plaintext LAN token migration (`lan-token-encryption-v1`).
- Room migrations 1 to 10. Fresh schema with destructive fallback, and the archive index rebuilt
  from files on disk by `StorageJanitor` adoption. Archive files are never deleted.
- Cleanup of files from older app builds (`.derived-*`, UUID temps, iOS `LegacyPartial`, the
  receipt fallback without content hash).
- Pre proto 3 branches behind `ProtocolGate` (whole second SET_TIME retry, older firmware comments).
- Unused declarations with zero references (list in the inventory, including `MoodText.kt`,
  `LanFrameSplitter`, the unused `mood_*`, `who_step_*`, `pms_*`, `clock_*` strings). Test only
  helpers in `:core:parquet` stay, so the reader remains complete.
- `ios/App/SensorArchive.swift` write path. Done 2026-10-12, only the station ID check remains.
- Stale docs. Done 2026-10-12, the parity audits and the long-form evaluation are deleted.

**Keep (wire contract).** `legacy-parquet/` path and its grouping, GET_CONFIG page 0, 17 byte
TimeSet, JSON keys, reading old Parquet statistics, `ProtocolGate`, the iOS `SensorArchive` root
until iOS moves to the shared layout, Android application ID.

## Rule and doc changes

- `AGENTS.md`. Near me may also be tapped on Stations when no sensor is owned, same conversion and
  no storage. The owned sensor's retained H3 cell may be stored on the phone. The
  `DeviceConnectionRepository` mention is gone (done 2026-10-12).
- `docs/devices-and-discovery.md`. The peacock may sit beside the number at Simple. The WHO legend
  text applies at Detailed.
- `docs/screens.md`, `ui/AGENTS.md`. New navigation, Stations, Station page, wizard, settings
  table, permission triggers. The reboot rule names `DeviceConfigController.confirm()`.
- `docs/store-compliance.md`. Permission timing rows, nickname stays on the phone.
- The Show public sensors note says nearby reads reveal the rough area to the feed host.

## Tests (host and iOS simulator through `verify`)

- `StationSummaryTest`, `PublicSummaryTest`, `SimpleCopyTest`, `MoodStrip` semantics.
- `StationOrderTest`, `NearbyCellsTest`, `StationsControllerTest`, `ParquetIndexerTest` (cell
  present, mixed, missing), `StationGlanceSourceTest`, `AppNavConfigTest`, `TabNavigatorTest`.
- `SetupControllerTest` (order, skip, close confirm, re-run, failures, nickname never in a patch).
- `DeviceConfigControllerTest` (new confirmations, refuse during sync), `DeviceSettingsLevelsTest`.
- `CopyLintTest` and `TranslationsTest` cover new strings.

## Phone checks (Android and iPhone, dated in `docs/status.md`)

First pair with random and fixed PIN, wrong PIN, kill during setup then Finish setup, location
denied then granted with readback, old firmware, wrong Wi-Fi password, iOS local network prompt
only at the Wi-Fi tap. A cell appears after sync without opening settings. Nearby with airplane
mode and with Show public sensors off (no feed request). Each confirmation. Arabic right to left,
TalkBack and VoiceOver on the strip. Simple settings fits about one screen.

## Out of scope

Uploading a device's data. It needs an `AGENTS.md` change, a server and firmware contract, and its
own design. The settings page has no upload row until then.
