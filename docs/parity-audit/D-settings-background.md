# Audit D. Settings, background sync, permissions, theme, strings, store compliance

Repo `opensensor-space-mobile`, `main` at 180d1ee, audited 2026-10-09. Read only. Paths are
relative to the repo root. "A" is Android, "i" is iOS.

## 0. Headline findings

1. **iOS has two of Android's four settings.** Units and Show public sensors exist on both.
   Detail level and Smart Sync clock consent are Android only, and iOS has no About, privacy
   or font licence line. Every key and file name differs between the platforms.
2. **No shared settings model.** `DetailLevel` and the store live in `app/` (DataStore, Hilt),
   `ClockSyncMode` lives in `app/data/sync/SmartSyncPolicy.kt` although it is pure Kotlin. iOS writes
   `NSUserDefaults` directly from a composable (`AppRoot.kt`). Only `UnitSystem` is shared (`core/units`).
3. **Android backup rule misses the settings it claims to keep.** `data_extraction_rules.xml:18-20`
   includes only `sharedpref`, but settings and Discover state are Preferences DataStore files
   (`files/datastore/*.preferences_pb`). The comment at line 8 says preferences are backed up. What
   is actually backed up is operational throttle state (`SharedPreferences` "sync_triggers" and
   "advert_scan"). iOS `UserDefaults` is backed up by default, so the two platforms differ.
4. **Background sync is Android only.** iOS has no Auto-sync, no triggers, no notifications, no
   `UIBackgroundModes`, no `BGTaskScheduler`. It has only a `beginBackgroundTask` lease for an
   already running LAN collection, and it disconnects BLE on background (`AppDelegate.swift:47-50`,
   `BluetoothCompanion.swift:111-116`). The decision logic (`AutoSyncRunner`, `AutoSyncPolicy`) is
   already Android free but sits in `app/` and depends on the Room `KnownDevice` entity.
5. **Permission policy is not shared and is not uniform even on Android.** `PermissionFlow` is pure
   but in `app/ui/permissions`. Near me (both platforms) and notifications (Android) bypass it, so
   there is no settings link after a permanent denial for location or notifications, which
   `store-compliance.md:174` claims for all runtime permissions. iOS has no settings deep link at
   all, only text hints, and Near me goes silent after one denial (`NearMe.ios.kt:44-47`).
6. **Stale privacy copy.** Android `info_privacy` (`strings.xml:433`) says "no location and no
   uploads" although `ACCESS_COARSE_LOCATION` is declared (`AndroidManifest.xml:73`) and asked on Near
   me. `docs/screens.md:120` still says location is "Not declared today".
7. **Strings.** Android has 731 `<string>` and 43 `<plurals>` in one `values/strings.xml`, no other
   locale. iOS has about 123 hardcoded user facing literals (61 in Kotlin `ios/shared`, about 55 in
   Swift adapters, 3 Info.plist purpose strings, plus 36 in `DiscoverStrings.English`). Three settings
   strings are exact duplicates of Android ones. No copy rule violations found on either side
   (no em dash, colon or semicolon in any user visible string, checked by script).
8. **Theme is in good shape.** Tokens, fonts, shapes and dark mode come from `:ui:designsystem` on
   both. Remaining drift is in settings controls (switch colours, segment shapes, row a11y),
   `AqSyncTheme` duplicating `OpenSensorTheme`, and the iOS launch screen not using brand paper.
9. **Upload opt-in is not implemented on either platform**, and iOS has no per-device record to
   hang it on (Android would use the `known_device` row).

## 1. Parity matrix

Legend. yes = present and equivalent, partial = present but different, no = absent.

### 1.1 App settings keys and defaults

| Setting | Android key, default (file:line) | iOS key, default (file:line) | Parity |
| --- | --- | --- | --- |
| Detail level | DataStore "settings" `detail_level` (enum name), default SIMPLE, legacy `advanced_mode=true` reads as DEVELOPER (`app/.../data/settings/AppSettings.kt:50-53,101-102`) | none | no |
| Smart Sync clock consent | `clock_sync_mode`, default ASK (`AppSettings.kt:56-58,103`). Enum in `app/.../data/sync/SmartSyncPolicy.kt:6` | none, iOS has no SET_TIME flow | no |
| Show public sensors (Discover network switch) | `show_public_sensors`, default true (`AppSettings.kt:65,104`) | `NSUserDefaults` `discover.showPublicSensors`, default true when missing (`ios/shared/src/iosMain/.../AppRoot.kt:73,87,119`) | partial (same default and effect, different key and store) |
| Units | `unit_system`, missing = AUTO via `UnitSystem.parse` (`AppSettings.kt:71,105`) | `settings.unitSystem`, same parse (`AppRoot.kt:76,88,125`) | partial (same values, different key) |
| Units "Automatic" live follow | `resolveUnits` from Android locale (`SettingsScreen.kt:185`) | `NSCurrentLocaleDidChangeNotification` observer (`AppRoot.kt:139-154`) | yes |
| Discover camera, property, window, first run | DataStore "discover" `camera_lat`, `camera_lng`, `camera_zoom`, `property`, `window` (default DAY), `first_run_done` (`DiscoverStore.kt:54-61`) | `discover.camera.lat`, `.lng`, `.zoom`, `discover.property`, `discover.window`, `discover.firstRunDone` (`feature/discover/src/iosMain/.../Platform.ios.kt:41-46`) | partial (same `DiscoverPrefs` port, two adapters, different keys) |
| About, version, privacy line, font licence | `SettingsScreen.kt:152-156` | none | no |
| Settings info button | `SettingsScreen.kt:59` | none | no |
| Backup of settings | not backed up (DataStore outside `sharedpref`, `data_extraction_rules.xml:18-20`) despite comment line 8 | backed up (UserDefaults default) | partial, and Android contradicts its own comment |

### 1.2 Detail levels

| Item | Android | iOS | Parity |
| --- | --- | --- | --- |
| Model | `DetailLevel` enum (`AppSettings.kt:24-37`) | none | no |
| Settings control | segmented row (`SettingsScreen.kt:71-105`) | none | no |
| Gating in screens | `detailed` / `advancedMode` flows across screens (`docs/screens.md:56-78`) | none. iOS always shows RSSI dBm (Developer on Android) and firmware (Detailed on Android), bytes in current file and KiB/s (`ios/shared/.../CompanionScreen.kt:91,122,183-184`) | no, and iOS violates "Simple by default" (`docs/cross-platform-guide.md` "Simple by default") |

### 1.3 Network use and uploads

| Item | Android | iOS | Parity |
| --- | --- | --- | --- |
| Discover feed | `bundledSampleFeed()` (`ui/discover/DiscoverRoute.kt:38`) | `bundledSampleFeed()` (`AppRoot.kt:96`) | yes (both offline sample, no live feed yet) |
| Basemap gated by switch | `networkAllowed = showPublic` (`DiscoverRoute.kt:63`) into shared `DiscoverScreen.kt:126-127` | same (`AppRoot.kt:110`) | yes |
| Switch control style | stock `Switch` default colours (ink in light theme), only the switch is the touch target (`SettingsScreen.kt:146-150`) | Signal blue switch, whole row `toggleable` with `Role.Switch`, 44 dp min (`AppRoot.kt:271-297`) | partial |
| Per-device upload opt-in, CC BY confirmation | not implemented (no code hits) | not implemented, and no per-device store exists on iOS | no / no |

### 1.4 Background and auto sync

| Item | Android (file:line) | iOS | Parity |
| --- | --- | --- | --- |
| Per-device Auto-sync switch | `known_device.autoSync` (`data/local/database/KnownDevice.kt:39`), `AutoSyncCard.kt:64` | none | no |
| Periodic run | WorkManager 15 min, battery not low, backoff exp 5 min (`AutoSyncScheduler.kt:65-67,138-139`) | none | no |
| Retry budget | `MAX_RETRIES` 2 (`SyncWorker.kt:139,153`) | LAN resume on foreground only (`LanDiscovery.swift:255-266`) | partial |
| Trigger, companion presence | `CompanionPresence.kt`, `CompanionPresenceService.kt` | none (iOS analogue would be CoreBluetooth background scan with state restoration) | no |
| Trigger, advertising flags | `AdvertFlagScan.kt`, `MIN_GAP_MS` 5 min (`:169`) | none | no |
| Trigger, Wi-Fi arrival | `SyncTriggers.kt`, `WIFI_THROTTLE_MS` 10 min (`:130`) | none (no iOS equivalent with the app suspended) | no |
| Boot re-arm | `BootReceiver` (`AndroidManifest.xml` receiver) | n/a | n/a |
| Foreground transfer protection | `dataSync` FGS (`SyncForegroundService.kt`), WifiLock | `beginBackgroundTask` lease, pause on expiry, resume on foreground (`LanDiscovery.swift:268-299`) | partial |
| BLE on background | session continues under FGS during a transfer | disconnects immediately (`BluetoothCompanion.swift:111-116`) | no |
| Collection budget | `CollectionBudget` two transfers | two collection slots in Swift (`LanDiscovery.swift:163-164`) | partial (same number, implemented twice) |
| Background modes declared | FGS dataSync, companion permissions | none (`Info.plist`, `store-compliance.md:197`) | n/a by design today |

### 1.5 Notifications

| Item | Android | iOS | Parity |
| --- | --- | --- | --- |
| Progress notification | channel `sync_progress`, ongoing, Stop action (`SyncNotifications.kt:42,95`) | none | no |
| Summary when new files or failures | channel `sync_summary`, plurals `notif_synced`, `notif_failures` (`SyncNotifications.kt:105-108`) | none | no |
| Permission | `POST_NOTIFICATIONS` API 33+, asked once per screen from Sync taps, no settings path (`NotificationPermission.kt:39-61`, call sites `ScanScreen.kt:101`, `FilesScreen.kt:111`, `TodayRoute.kt:59`, `DeviceRoute.kt:86`) | not requested (`docs/screens.md:119`) | no |

### 1.6 Permission timing

| Permission | Android | iOS | Parity |
| --- | --- | --- | --- |
| Bluetooth | `rememberBluetoothAccess` through `PermissionFlow`, rationale, settings link, turn-on intent (`RuntimeAccess.kt:139-159`) | `CBCentralManager` created on first Find sensors tap. Denied shows text "Allow Bluetooth ... in Settings." with no button (`BluetoothCompanion.swift:125`) | partial (same timing, no settings link on iOS) |
| Local network | `ACCESS_LOCAL_NETWORK` API 37+, from Wi-Fi actions (`RuntimeAccess.kt:163-173`, `LanPermission.kt`) | `NWBrowser` on first Find Wi-Fi sensors tap, denial inferred from `PolicyDenied` (`LanPolicy.swift:21-26`), text hint (`CompanionState.kt:136`) | partial |
| Notifications | see 1.5 | none | no |
| Location, Near me only | shared `rememberNearMe` (`feature/discover/.../NearMe.kt:56-75`). Android actual asks COARSE only, no rationale, no settings link (`NearMe.android.kt:14-31`) | When In Use, reduced accuracy (`Info.plist` line with `NSLocationDefaultAccuracyReduced`), silent after first denial (`NearMe.ios.kt:44-47`) | yes for timing, partial for denial handling |
| No prompt at launch | yes, phone evidence 2026-10-08 (`docs/status.md:7-14`) | yes by construction, no dated iPhone fresh-install check recorded | partial evidence |

### 1.7 App lifecycle

| Item | Android | iOS |
| --- | --- | --- |
| Process start work | `AqSyncApplication.onCreate` runs token migration, scheduler, triggers, history repair in its own `appScope` (`AqSyncApplication.kt:49-73`), separate from the injected `@ApplicationScope` (`data/di/CoroutinesModule.kt`). Two process scopes | `SceneDelegate` builds adapters, no start work (`AppDelegate.swift:17-44`) |
| Background | FGS keeps transfers. UI state in ViewModels | BLE disconnect, LAN browse stop, collection lease (`AppDelegate.swift:47-50`) |
| Foreground return | `LifecycleResumeEffect` re-checks permissions (`RuntimeAccess.kt:193-197`) | resume pending LAN, present export (`AppDelegate.swift:51-55`) |
| Launch screen | brand paper splash, dark variant (`values-night/colors.xml`, `themes.xml:4`) | `UILaunchScreen` empty dict, system default colour (`Info.plist`) |

### 1.8 Theme, tokens, fonts, dark mode

| Item | Android | iOS | Parity |
| --- | --- | --- | --- |
| Colour scheme | `AqSyncTheme` uses `BrandLightScheme/BrandDarkScheme`, plus an unused `dynamicColor` branch (`ui/theme/Theme.kt:28-48`) | `OpenSensorTheme` (`ui/designsystem/.../Theme.kt:127-138`, `AppRoot.kt:103`) | yes (duplicate wrapper) |
| Dark mode | follows system | follows system | yes. Neither has an in-app override, consistent |
| Fonts | Quicksand and Cairo from Compose resources | same | yes |
| Arabic script switch | `LocalConfiguration.locales[0]`, live (`Typography.android.kt`) | `NSLocale.preferredLanguages` read once (`Typography.ios.kt:8-12`) | yes (iOS restarts on language change) |
| Settings segmented control | `FloatingControl.segmentShape` and `segmentedColors()` (`SettingsScreen.kt:78-79`) | `SegmentedButtonDefaults.itemShape(..., shapes.small)` and inline colours (`AppRoot.kt:247-255`) | partial |
| Switch colours | Material default | inline Signal blue | partial, no `FloatingControl.switchColors()` exists |
| WHO step colours helpers | `app/ui/theme/Color.kt` (Android only) | none in iOS companion (mood title text only) | partial, screens agent scope |

### 1.9 Strings and localisation

| Item | Android | iOS |
| --- | --- | --- |
| Catalogue | `app/src/main/res/values/strings.xml`, 731 strings, 43 plurals | none. Literals in `AppRoot.kt`, `CompanionScreen.kt`, `CompanionState.kt`, Swift adapters, `DiscoverStrings.English` |
| Discover copy | 64 `discover_*` keys mapped into `DiscoverStrings` (`DiscoverRoute.kt:72-143`) | `DiscoverStrings.English` (`feature/discover/.../DiscoverStrings.kt:101`), 36 literals, all match Android text except plural forms written for n=1 |
| Exact duplicates | `tab_my_devices`, `settings_public_sensors`, `settings_public_sensors_body` | `AppRoot.kt:160,279,294` |
| Near duplicates | `units_auto/metric/imperial`, `settings_units_auto` | `AppRoot.kt:263,315-318` |
| Locales | none besides default. `supportsRtl=true`. No `localeConfig` | `knownRegions = (en, Base)` (`project.pbxproj:25`), no `.lproj`, no `InfoPlist.strings` |
| Copy rule (no em dash, colon, semicolon) | clean | clean |

### 1.10 Privacy and store

| Item | Android | iOS | Notes |
| --- | --- | --- | --- |
| Purpose strings | in-app rationale strings `perm_bt_*`, `perm_lan_*` | `NSBluetoothAlwaysUsageDescription`, `NSLocalNetworkUsageDescription`, `NSLocationWhenInUseUsageDescription`, `NSBonjourServices` | equivalent wording, written twice |
| Privacy manifest / Data safety | manifest comment, `ManifestPermissionsTest` | `PrivacyInfo.xcprivacy` bundled (`project.pbxproj:22`), UserDefaults `CA92.1` correct for current use | if iOS moves settings off UserDefaults, keep `CA92.1` only while anything still reads it (check maplibre and Compose) |
| Privacy line in app | `info_privacy` stale ("no location") | none | fix copy, then share |

## 2. Divergent decisions and duplicated copy

1. **Storage technology and key naming.** Android DataStore with snake_case keys in two files
   ("settings", "discover"). iOS `NSUserDefaults` with dotted keys. Same semantics, two
   implementations, no shared key list, no test that they agree.
2. **Where settings state lives.** Android ViewModels over a Hilt singleton. iOS composable
   `remember` state in `AppRoot`, written straight to `NSUserDefaults` (`AppRoot.kt:85-127`). A
   shared settings screen cannot exist until the state moves behind a common port.
3. **Settings layout.** Android plain column with `TopAppBar` and an info button, iOS cards
   (`SettingsCard`) with an in-content title. Order differs (Android Detail, Clock, Units, Public,
   About. iOS Units, Public).
4. **Switch and segmented styling.** See 1.8. iOS follows the floating control rule more closely
   (Signal blue only for selection, whole row toggleable). Android's stock switch renders ink in
   light theme and has a smaller target.
5. **Detail level.** Android gates technical fields, iOS shows RSSI and firmware ungated.
6. **Denied permission UX.** Android offers settings after permanent denial for Bluetooth and
   local network only. iOS never offers a settings button. Location and notifications on Android
   just stop.
7. **Notification ask scope.** Android asks "once per screen" (`asked` is composition local,
   `NotificationPermission.kt:42`), so leaving and returning asks again. iOS never asks.
8. **Background model.** Android "WorkManager plus dataSync FGS, never a bare thread". iOS "bounded
   lease, pause and resume on return". Both reasonable today, but the budget (two transfers), the
   retry idea and the "notify only when something changed" rule are implemented independently.
9. **Backup.** Android excludes settings unintentionally and backs up throttle state. iOS backs up
   settings and excludes the archive. Decide once (proposal: settings yes, throttle and trigger
   state no, archive no, secrets never) and encode it on both.
10. **Theme wrapper.** `AqSyncTheme` keeps a `dynamicColor` path the product rules forbid
    ("Wallpaper dynamic colour stays off"). `OpenSensorTheme` already does the job.
11. **Launch screen.** Brand paper on Android, system colour on iOS.

Hardcoded copy duplicated on both platforms today. "My devices", "Show public sensors", "When
off, the map uses no internet and shows only sample data.", "Automatic", "Metric", "Imperial",
"Follows your phone's region, now %s", "Settings", "Discover", the whole Discover catalogue
(`DiscoverStrings.English` vs `discover_*`), and the permission purpose wording (Info.plist vs
`perm_*`). Copy rule check found no violations on either side. Stale copy is
`info_privacy` (Android) which contradicts the Near me feature.

## 3. Proposal

### 3.1 Shared settings model (`:core:settings`, commonMain)

Pinned versions (`gradle/libs.versions.toml`): Kotlin 2.4.20, Compose Multiplatform 1.12.1,
`androidxDatastore = "1.2.1"` (only `datastore-preferences` declared today, line 77),
`components-resources` 1.12.1 already used by `:ui:designsystem` and `:feature:discover`.

- Add `androidx.datastore:datastore-preferences-core` 1.2.1 (KMP, has iOS targets since 1.1) to
  a new `:core:settings` module with `commonMain`, `androidMain` (path from `Context.filesDir`),
  `iosMain` (path in Application Support via `NSFileManager`). No Compose, no Hilt.
- Move `DetailLevel` (from `AppSettings.kt`) and `ClockSyncMode` (from `SmartSyncPolicy.kt`, which
  imports only `core/protocol`) into it. `UnitSystem` stays in `core/units`.
- `AppPrefs` data class with defaults in one place (SIMPLE, ASK, true, AUTO) and the legacy
  `advanced_mode` rule. `SettingsStore` interface with `prefs: Flow<AppPrefs>` and typed setters.
  Implement `DiscoverPrefs` on the same store so the two Discover adapters go away.
- Keep Android's file names ("settings", "discover") and snake_case keys, so Android needs no
  migration. iOS imports the six dotted UserDefaults keys once, or drops them under the POC no
  back-compat rule.
- Alternative if DataStore on iOS is unwanted, a `SettingsStore` port with an `NSUserDefaults`
  actual. Cheaper, but two persistence paths remain and key parity needs a test. Recommend
  DataStore core on both.
- Per-device settings (Auto-sync, wake when nearby, future upload opt-in) are not app prefs. They
  belong in a shared known-device port (storage agent). Flag the dependency, do not put them here.

### 3.2 Shared strings (Compose Multiplatform resources)

- New `:ui:strings` (or `composeResources/values/strings.xml` in `:ui:designsystem`) with
  `publicResClass = true` so app, `ios/shared` and `feature/*` read `Res.string.*`.
- First batch, the strings both platforms show today: tabs, Settings, units, public sensors,
  privacy line, Discover catalogue (then delete `DiscoverStrings` and `discoverStrings()`),
  permission rationale and settings dialogs, iOS companion messages from `CompanionState.kt`.
- Non composable Android sites (notifications, services) use the suspend `getString` and
  `getPluralString` from compose resources, or keep their `R.string` until moved. Manifest label
  and channel names can stay Android resources.
- Swift adapter messages should become enum outcomes passed to Kotlin, which picks the string, so
  no copy lives in Swift. Info.plist purpose strings stay native (`InfoPlist.strings` per locale).
- Add a host test that parses every `strings.xml` (Android and compose resources) and fails on em
  dash, colon or semicolon in values, so the CLAUDE.md rule is enforced.
- Localisation. `values-ar` in compose resources, Android `android:localeConfig`, iOS
  `knownRegions` add `ar` and `ar.lproj/InfoPlist.strings`. Cairo is already wired.

### 3.3 Shared permission policy with expect/actual seams

- Move `AccessStep` and `PermissionFlow` to commonMain (`:ui:permissions` KMP module). Add
  `AccessKind { BLUETOOTH, LOCAL_NETWORK, NOTIFICATIONS, APPROX_LOCATION }` and a pure
  `PermissionTriggers` table, action to kinds (Sync now, Auto-sync on, wake when nearby, Near me,
  Find Wi-Fi sensors), so both apps ask from the same taps.
- Add an iOS mapping. notDetermined to ASK, denied to OPEN_SETTINGS
  (`UIApplicationOpenSettingsURLString`), restricted to DECLINED, no rationale step. Local network
  on iOS has no status API, so model `UNKNOWN` and treat `PolicyDenied` from the browser as denied.
- `expect fun rememberAccess(kind): RuntimeAccess` with a common `RuntimeAccess` (run, ready,
  dialogs). Android actual wraps today's launcher code. iOS actual uses `CBManager.authorization`,
  `UNUserNotificationCenter`, `CLLocationManager`, and a shared settings dialog.
- Move Near me and notifications onto it. This fixes the missing settings path for location and
  notifications on Android and the silent denial on iOS, and makes `store-compliance.md:174` true.
- `PermissionFlowTest` moves to commonTest and gains the iOS mapping cases.

### 3.4 Shared background-sync policy, platform schedulers

- commonMain `BackgroundSyncPolicy` holding what both must agree on. Period floor 15 min, retry
  max 2 with backoff 5 min, collection budget 2, LAN browse 10 s, BLE connect 15 s, Wi-Fi trigger
  throttle 10 min, advert min gap 5 min plus change key dedupe, `lanOnly` rule, link order
  (`AutoSyncPolicy.linksFor`), and the summary decision (post only for new files or failures)
  with its text from shared strings.
- Move `AutoSyncRunner`, `AutoSyncGateway`, `AutoSyncPolicy` out of `app/` once a common
  `SyncTarget` replaces the Room `KnownDevice` and `LanDevice` in the signatures. `AutoSyncRunnerTest`
  becomes commonTest and runs under `verifyIos`. Coordinate with the sync engine agent on module.
- Android scheduler unchanged (WorkManager, FGS, CDM, PendingIntent scans, Wi-Fi callback).
- iOS scheduler, new and opt-in per device.
  - `BGProcessingTask` for the periodic floor (`UIBackgroundModes` processing,
    `BGTaskSchedulerPermittedIdentifiers`). The system picks the time, often on charge.
  - `bluetooth-central` background mode with CoreBluetooth state restoration and a service UUID
    scan as the analogue of "wake when nearby" and advert flags. Requires App Review justification.
  - `BGContinuedProcessingTask` (iOS 26 and later, verify in current Apple docs, deployment target
    is 18.5 so behind an availability check) for a user-started "Sync now in background".
  - Wi-Fi arrival has no suspended-app equivalent. Leave it Android only and say so in docs.
  - `UNUserNotificationCenter` for the summary, asked from the Auto-sync switch tap.
  - Store impact. Update `store-compliance.md` "Background modes", privacy manifest unaffected.

### 3.5 Theme and settings UI

- Add `FloatingControl.switchColors()` to `:ui:designsystem`. Make `AqSyncTheme` a thin alias of
  `OpenSensorTheme` (drop `dynamicColor`), move `pm25Band` next to the colour helpers.
- One `SettingsScreen` in a new `:feature:settings` commonMain taking `SettingsState` and
  callbacks plus a `capabilities` set (iOS hides clock consent until it has Smart Sync). Whole row
  toggles, segment shapes from `FloatingControl`, About with version from an expect.
- iOS launch screen colour from an asset catalog colour with a dark variant (brand paper).

## 4. Ordered migration steps

Sizes S under a day, M one to three days, L more. Waves can run in parallel, file sets listed so
agents do not overlap.

| # | Step | Size | Files owned | Depends | Wave |
| --- | --- | --- | --- | --- | --- |
| 1 | Fix stale copy and docs. `info_privacy` mentions Near me, `docs/screens.md:120` location row, `store-compliance.md:174` claim, backup rule includes `file` `datastore/` and excludes throttle prefs (or move them to `noBackupFilesDir`) | S | `res/values/strings.xml` (one key), `res/xml/data_extraction_rules.xml`, `docs/screens.md`, `docs/store-compliance.md` | none | A |
| 2 | `:core:settings` module, `AppPrefs`, `SettingsStore`, DataStore core impl both targets, move `DetailLevel`, `ClockSyncMode` | M | new module, `settings.gradle.kts`, `libs.versions.toml` | none | A |
| 3 | Shared permission policy module, `AccessKind`, `PermissionTriggers`, iOS mapping, commonTest | M | new `:ui:permissions`, `app/ui/permissions/PermissionFlow.kt` (moved), `PermissionFlowTest` | none | A |
| 4 | Theme cleanup, `switchColors()`, `AqSyncTheme` alias, iOS launch colour | S | `ui/designsystem/.../FloatingControls.kt`, `app/ui/theme/Theme.kt`, `ios/App/Info.plist`, `ios/App/Assets.xcassets` | none | A |
| 5 | Android adapter on `:core:settings` (same files and keys) | S | `app/data/settings/*`, `app/data/sync/SmartSyncPolicy.kt` import | 2 | B |
| 6 | iOS adapter on `:core:settings`, remove `UserDefaultsDiscoverPrefs`, one-time import | S | `ios/shared/.../AppRoot.kt`, `feature/discover/src/iosMain/.../Platform.ios.kt` | 2 | B |
| 7 | Shared strings module, first batch, delete `DiscoverStrings`, copy lint test | M | new `:ui:strings`, `feature/discover/.../DiscoverStrings.kt`, `app/ui/discover/DiscoverRoute.kt`, moved keys in `strings.xml` | 1 | B (after 1 for strings.xml) |
| 8 | Move Near me and notifications onto shared access, iOS actuals with settings deep link | M | `feature/discover/.../NearMe*.kt`, `app/ui/permissions/NotificationPermission.kt`, `RuntimeAccess.kt`, new iOS actuals | 3 | B |
| 9 | Shared `:feature:settings` screen, replace `SettingsScreen.kt` and `IosSettings` | M | new module, `app/ui/settings/*`, `AppRoot.kt` (settings part only, after 6 merges) | 4, 5, 6, 7 | C |
| 10 | Detail level on iOS companion, gate dBm, firmware, byte counters | S | `ios/shared/.../CompanionScreen.kt`, `MainViewController.kt` | 6 | C (coordinate with screens agent) |
| 11 | `BackgroundSyncPolicy` and runner to commonMain, `SyncTarget` port | L | `app/data/background/AutoSyncRunner.kt`, constants from `AutoSyncScheduler.kt`, `SyncTriggers.kt`, `AdvertFlagScan.kt`, `SyncWorker.kt`, `AutoSyncRunnerTest` | storage agent known-device port | C |
| 12 | iOS background scheduler, per-device Auto-sync, notifications | L | `ios/App/*` new `BackgroundSync.swift`, `Info.plist`, `AppDelegate.swift`, iOS known-device store | 8, 11 | D |
| 13 | Arabic localisation, `localeConfig`, `knownRegions`, `InfoPlist.strings` | M | resources, manifest, pbxproj | 7 | D |

### Test plan

- commonTest. `AppPrefs` defaults and the legacy `advanced_mode` rule. Golden list of storage keys
  so a rename fails. `SettingsStore` round trip on an okio fake file system. `PermissionFlow` and
  the iOS status mapping. `PermissionTriggers` table. `BackgroundSyncPolicy` (throttles, retry
  budget, summary decision, link order) and the moved `AutoSyncRunnerTest`. All under
  `./gradlew --no-daemon verify verifyIos`.
- Host tests. Copy lint over every strings file. `ManifestPermissionsTest` unchanged. A test
  that parses `data_extraction_rules.xml` and asserts `datastore/` is included and secrets are not.
- UI. Screenshot or preview tests of the shared Settings screen, light, dark, Simple, Developer,
  and one RTL Arabic render.
- iOS simulator build and launch, no prompt at start.

### Needs real-phone evidence (record in `docs/status.md`)

- Fresh install on iPhone shows no permission prompt, then Bluetooth, local network, location and
  notification prompts appear only from their taps, and the settings link works after denial.
- Android Near me and notifications after permanent denial now reach system settings.
- Android backup and restore (`bmgr`) keeps settings and drops throttle state.
- iOS settings persist across app upgrade after the store switch.
- Everything in step 12 (BGProcessingTask timing, CoreBluetooth restoration wake, continued task
  progress UI, summary notification) on a real iPhone, screen locked and in Low Power Mode.
- Arabic rendering with Cairo on both phones.
- A host or simulator pass does not qualify any of the above.
