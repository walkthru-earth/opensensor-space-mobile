# Audit F. User-visible feature inventory (Android to iOS)

Date 2026-10-09. Read only. Branch `feat/platform-parity` at `0527c00` (after Wave 0 and Wave 1 merged). Builds on `docs/platform-parity-plan.md` and `docs/parity-audit/A` to `E`. Where an earlier audit already covers the detail, this one gives the feature id and links back instead of repeating it.

Paths. `A/` is `app/src/main/java/earth/walkthru/aqsync/`. `R/` is `app/src/main/res/`. `iS/` is `ios/shared/src/` (`commonMain` or `iosMain` under `kotlin/earth/walkthru/opensensor/`). `iA/` is `ios/App/`. The iOS deployment target is 18.5 (`ios/OpenSensor.xcodeproj/project.pbxproj:27-28`).

Decision legend
- **SHARE** means build it once in commonMain, in the named module, and both apps host it.
- **SEAM** means IOS-NATIVE-SEAM. The logic and UI are shared, and a thin Swift or iosMain adapter calls the named Apple API.
- **ANDROID-ONLY** means there is a concrete reason iOS cannot or should not have it.
- **DROP** means it is not worth building.

Waves are the ones in `platform-parity-plan.md` (0 to 4). New items are marked **W5 (new)**.

## 0. Headline findings

1. Android has **89 user-visible features** in this inventory. iOS has **9 present**, **19 partial** and **52 missing**. 9 are Android only by design.
2. Wave 1 already gated technical fields on iOS (`iS/commonMain/.../CompanionScreen.kt:62` `showTechnical`, default false). But no setting drives that flag, so iOS is stuck at Simple. That is correct for Simple but leaves no way to reach Detailed or Developer (F62).
3. Android has **no** home screen widget, Quick Settings tile, app shortcut, deep link, share target, App Actions or per-app language picker. There is nothing to port for those, and the inventory does not invent iOS-only extras (section 3).
4. The heaviest iOS gaps are all behind the shared engine (Wave 3). They are BLE file sync, History, Files, device configuration, Debug and all background work. Most screen work can only land after the `:core:sync` and `:core:archive` ports.
5. Some iOS seams need decisions now because they change the store listing or add a permission. These are the camera for the sticker scan (F52), the `bluetooth-central` and `processing` background modes (F53, F54, F68), notifications (F67, F76) and `BGContinuedProcessingTask`, which only exists on iOS 26 and later (F48, F66). Each is listed as an assumption below.
6. The iOS archive already sits in Application Support with `isExcludedFromBackup` (`iA/SensorArchive.swift:32,59-61`). The Keychain token uses `AfterFirstUnlockThisDeviceOnly` (`iA/LanCredentials.swift:22`), so a background LAN sync can read it while the phone is locked. Neither needs work for background sync.

## 1. Feature table

Sizes. S is under a day, M is one to three days, L is more.

| id | Feature | What the user sees | Android evidence | iOS today | Decision | Size | Wave |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **App shell** | | | | | | | |
| F01 | Launcher entry and single window | One activity, `singleTask`, edge to edge, no permission wall | `A/ui/MainActivity.kt:26-48`, `AndroidManifest.xml` activity | present (`iA/AppDelegate.swift:16-44`, one scene) | SHARE `:feature:shell` hosts both | S | 3 (plan step 6) |
| F02 | Brand launch screen | Paper splash, dark variant | `R/values/themes.xml:4`, `R/values-night/colors.xml` | partial. `UILaunchScreen` is empty, so iOS shows the system colour (`iA/Info.plist`) | SEAM. `UILaunchScreen` > `UIColorName` from an asset catalog colour with a dark appearance | S | 2 (design system) |
| F03 | Bottom bar, Discover, My devices, Settings, Discover first | Three tabs | `A/ui/Navigation.kt:53-66,162-194` | partial. Custom `TabBar` (`iS/iosMain/AppRoot.kt:156-217`) | SHARE `:feature:shell` | M | 3 |
| F04 | Per-tab back stacks, reselect pops to root, Back returns to Discover | Navigation memory per tab | `A/ui/TabNavigator.kt:23-95` | missing (one `tab` var, `AppRoot.kt:86`) | SHARE `:feature:shell`. iOS edge swipe through the CMP back handler | M | 3 |
| F05 | Device section tabs Today, History, Files, Device | Sibling tabs under the device top bar | `A/ui/components/Common.kt:265` `DeviceSectionTabs` | missing | SHARE `:ui:designsystem` + `:feature:shell` | S | 3 |
| F06 | Brand theme and system dark mode | Colours, Quicksand and Cairo fonts | `A/ui/theme/Theme.kt:27-48` | present (`OpenSensorTheme`, `AppRoot.kt:103`) | SHARE (done). Delete `AqSyncTheme` | S | 2 |
| F07 | Info buttons and sheets | An "i" on cards opens plain-language help | `A/ui/components/InfoButton.kt` | partial (Discover `DefaultInfoButton` only) | SHARE `:ui:designsystem` | S | 2 |
| F08 | Snackbar messages for device events | One-line feedback after actions | `A/ui/components/DeviceMessages.kt`, `CollectEvents` in each route | partial (`state.message` line, `CompanionScreen.kt:78`) | SHARE `:ui:presentation` `UiText` | S | 2 |
| **Discover** | | | | | | | |
| F09 | Public map, H3 cells, charts, window, search | Discover tab | `A/ui/discover/DiscoverRoute.kt:54-70` | present (`AppRoot.kt:107-113`) | SHARE (done, `:feature:discover`) | none | none |
| F10 | Near me, approximate location only | Map moves near the user | `feature/discover/.../NearMe.android.kt:14-31` | present, but goes silent after one denial (`NearMe.ios.kt:44-47`) | SEAM. `CLLocationManager.requestWhenInUseAuthorization` with `NSLocationDefaultAccuracyReduced` (already set), then `UIApplication.openSettingsURLString` after a denial | S | 2 (D audit step 8) |
| F11 | "Add your sensor" goes to My devices | CTA on the Discover sheet | `A/ui/Navigation.kt:70` | present (`AppRoot.kt:111`) | SHARE (done) | none | none |
| F12 | Back collapses the Discover sheet first | Back gesture behaviour | `DiscoverRoute.kt:59` `BackHandler` | missing | SHARE inside `:feature:discover` (CMP back handler) | S | 2 (plan step 5) |
| F13 | Discover remembers camera, property, window, first run | Map reopens where the user left it | `A/data/settings/DiscoverStore.kt:54-61` | partial (other keys, `feature/discover/src/iosMain/.../Platform.ios.kt:41-46`) | SHARE `:core:settings` | S | 2 |
| **My devices** | | | | | | | |
| F14 | Bluetooth scan, auto while visible, Scan and Stop | Finds loggers nearby | `A/ui/scan/ScanScreen.kt:91-95,146-153`, `A/data/ble/BleScanner.kt:46` | partial. Manual Find, no Stop (`CompanionScreen.kt:132`, `iA/BluetoothCompanion.swift:43-66`) | SHARE `:feature:devices` + SEAM `CBCentralManager.scanForPeripherals(withServices:)` (exists) | M | 3 (step 7c) |
| F15 | Nearby list with bonded and known tags, signal bars, archive line | Each logger with how much the phone holds | `ScanScreen.kt:209-221,269-340`, `A/ui/components/SignalBars.kt` | partial. Name, and dBm only when technical (`CompanionScreen.kt:145`) | SHARE `:feature:devices` | M | 3 |
| F16 | Known devices not nearby | Loggers seen before, from the archive | `ScanScreen.kt:222-235,341-370` | missing. iOS has no known-device store | SHARE `:feature:devices` over a shared known-device port (`:core:archive` or `:data:store`) | M | 3 |
| F17 | Forget a device, with confirmation | Removes the phone's record | `ScanScreen.kt:254-266,372-380` | missing | SHARE. iOS also deletes the Keychain credential. The iOS bond stays in system Settings (see F24) | S | 3 |
| F18 | Active connections list with progress | Banner per running sync | `ScanScreen.kt:163-178` | missing | SHARE `:feature:devices` | S | 3 |
| F19 | Collect all (2+ known loggers) | One tap syncs every known logger | `ScanScreen.kt:236-252`, `A/data/DeviceIdentityClaims.kt:24-28` (budget 2) | missing | SHARE `:core:sync` + `:feature:devices` | M | 3 |
| F20 | Mascot empty states | Welcome and none nearby, with the peacock | `ScanScreen.kt:192-206` | missing, text only (`CompanionState.kt` message) | SHARE `:ui:designsystem` `MascotGuide` | S | 2 |
| F21 | Fake BLE and fake LAN devices, debug builds only | Menu entries to test without hardware | `ScanScreen.kt:128-143`, `A/ui/scan/ScanViewModel.kt:61` (`BuildConfig.DEBUG`), `A/data/fake/FakeTransport.kt` | missing (tests only, `ios/tests/TransferChecks.swift:20-40`) | SHARE fake `FrameLink` in `:core:sync` commonMain. Debug builds only on both | M | 3 |
| F22 | Wi-Fi (mDNS `_aqsync._tcp`) browse while the app is open | LAN loggers appear on their own | `A/ui/Navigation.kt:196-208`, `A/ui/LanDiscoveryViewModel.kt`, `A/data/lan/LanDiscovery.kt` | partial. Manual "Find Wi-Fi sensors" (`CompanionScreen.kt:162`, `iA/LanDiscovery.swift:34`) | SEAM `NWBrowser` (exists). Start it from the shared screen once local network was granted | S | 3 |
| F23 | Bluetooth off, ask to turn it on | System enable dialog | `A/ui/permissions/RuntimeAccess.kt:153` (`ACTION_REQUEST_ENABLE`), `A/data/ble/BluetoothStatus.kt` | partial (`centralManagerDidUpdateState` text, `BluetoothCompanion.swift:120-140`) | SEAM. iOS cannot turn Bluetooth on. Use the system power alert (`CBCentralManagerOptionShowPowerAlertKey`) plus shared copy | S | 3 |
| F24 | Pairing repair shortcut to Bluetooth settings | "Open Bluetooth settings" after a bond fails | `A/ui/components/Common.kt:246-250` | missing | SEAM. iOS has no public Bluetooth settings URL. Show shared repair copy and an app settings link (`openSettingsURLString`) | S | 3 |
| **Today (live)** | | | | | | | |
| F25 | Hero PM card with mood face, WHO step colour, pill, advice, freshness | Main reading | `A/ui/device/cards/MetricHeroCard.kt:74-145` | partial. Number plus "PM2.5 · title", no colour (`CompanionScreen.kt:89-95`) | SHARE `:feature:today` + `:ui:health` | M | 3 (7b) |
| F26 | Environment card (temperature, humidity) | Hero card for boards without PM | `cards/EnvironmentCard.kt:35` | partial (gate fixed in Wave 1, `CompanionScreen.kt:99-109`) | SHARE `:feature:today` | S | 3 |
| F27 | Today so far (local day mean and coverage) | Card with the day's average | `cards/TodaySoFarCard.kt:32` | missing | SHARE `:feature:today` over the archive port | S | 3 |
| F28 | Smart Sync card, the one primary sync action | "Sync" over the best link | `cards/SmartSyncCard.kt:47`, `A/ui/device/TodayRoute.kt` | partial. LAN Sync button only (`CompanionScreen.kt:214`) | SHARE `:core:sync` + `:feature:today` | M | 3 |
| F29 | Set time from phone, with confirmation | Fixes the logger clock (SET_TIME) | `cards/ClockCard.kt:35`, `TodayRoute.kt:89-100` | missing | SHARE `:feature:today`, command through the `:core:sync` session | S | 3 |
| F30 | Smart Sync clock consent, Ask, Automatic or Never, plus the dialog | Lets sync set the clock first | `A/data/sync/SmartSyncPolicy.kt:6-40`, `TodayRoute.kt:101-110`, `A/ui/settings/SettingsScreen.kt:107-141` | missing | SHARE `:core:settings` (`ClockSyncMode`) + `:core:sync` policy | S | 2 (setting), 3 (dialog) |
| F31 | Finalize buffered rows (FLUSH), Detailed, with confirmation | Writes the open file now | `cards/ClockCard.kt`, `TodayRoute.kt:111-120` | missing | SHARE `:feature:today` | S | 3 |
| F32 | Detailed cards. Batch estimate, readings, particles, board, storage, device info | Extra cards at Detailed and Developer | `A/ui/device/TodayScreen.kt:188-212`, `cards/{BatchEstimate,Readings,Particles,Board,Storage,DeviceInfo}Card.kt` | partial (firmware and finalized count behind `showTechnical`, `CompanionScreen.kt:112-114`) | SHARE `:feature:today` | M | 3 |
| F33 | Connection banner, Reconnect button, automatic reconnect (3 tries, LAN then BLE) | Recovers a dropped link | `TodayScreen.kt:140-149`, `Common.kt:207-262`, `A/data/DeviceConnectionRepository.kt:770-800` | missing (LAN resume on foreground only, `LanDiscovery.swift:257-266`) | SHARE policy in `:core:sync` + SEAM `CBConnectPeripheralOptionEnableAutoReconnect` (iOS 17 and later) for BLE | M | 3 |
| F34 | Device top bar. Devices, Debug (Developer), Settings, Disconnect | Navigation inside a device | `TodayScreen.kt:116-123` | partial. Disconnect only (`CompanionScreen.kt:128`) | SHARE `:feature:shell` | S | 3 |
| **History** | | | | | | | |
| F35 | History. Day, Week, Month, Year, paging, metric chips, band column chart | Charts from the phone archive, no session needed | `A/ui/history/HistoryScreen.kt:144-180`, `BandColumnChart.kt:64` | missing | SHARE `:feature:history` | L | 3 (step 8) |
| F36 | Calendar heatmap, day ring, tap a column or day to read it | Visual history | `CalendarHeatmap.kt:38`, `DayRing.kt:48`, `HistoryScreen.kt:247-360` | missing | SHARE `:feature:history` (Canvas, kotlinx-datetime) | M | 3 (step 7a) |
| F37 | Station epoch chips | Pick one sensor install when the station changed | `HistoryScreen.kt:122-140` | missing | SHARE `:feature:history` | S | 3 |
| F38 | Archive summary card and Developer source card | Days, files with time, waiting, failed | `HistoryScreen.kt:184-240` | missing | SHARE `:feature:history` | S | 3 |
| F39 | History index repair on start | Old files get indexed once per launch | `A/AqSyncApplication.kt:56-71`, `A/data/history/HistoryIndexer.kt`, `HistoryDeriver.kt` | missing | SHARE `:core:archive`. iOS runs it from `SceneDelegate` start | S | 3 |
| **Files** | | | | | | | |
| F40 | Manifest grouped by local day, per-file sync state | Files on the logger and on the phone | `A/ui/files/FilesScreen.kt:89-508`, `FileGrouping.kt` | missing | SHARE `:feature:files` | L | 3 (step 9) |
| F41 | Sync all, Stop, progress card, per-file sync | Download control | `FilesScreen.kt:205-222,374,423` | partial (whole LAN run, `CompanionScreen.kt:207-221`) | SHARE `:core:sync` + `:feature:files` | M | 3 |
| F42 | Share one file | System share sheet for one Parquet file | `FilesScreen.kt:475`, `A/ui/files/FilesViewModel.kt:245`, `A/data/export/ExportManager.kt:47` | missing | SEAM `UIActivityViewController` with a file URL. Shared `Share` port in `:ui:presentation` | S | 3 |
| F43 | Share archive zip and Save archive zip | Whole archive as one zip, share or save | `FilesScreen.kt:168-178`, `ExportManager.kt:111-143`, `R/xml/file_paths.xml` | partial. Folder copy through the document picker (`iA/AppDelegate.swift:62-114`) | SHARE zip builder in `:core:archive` (okio) + SEAM `UIActivityViewController` (share) and `UIDocumentPickerViewController(forExporting:asCopy:)` (save) | M | 3 |
| F44 | Share all files loose | Multi-file share | `FilesScreen.kt:180-183`, `ExportManager.kt:58` | missing | SEAM `UIActivityViewController` with several URLs | S | 3 |
| F45 | Share daily derived zip | Daily summaries as a zip | `FilesScreen.kt:185-189`, `ExportManager.kt:78-110` | missing | SHARE builder in `:core:archive` + share seam | S | 3 |
| F46 | File chart card (Detailed) | One column per synced file, archive only | `FilesScreen.kt:236`, `A/ui/files/FileChartCard.kt:57` | missing | SHARE `:feature:files` | S | 3 |
| F47 | Metrics inspector (Detailed) | One file decoded. Columns, time, metadata | `A/ui/history/FileMetricsScreen.kt:64-230` | missing | SHARE `:feature:files` (`:core:parquet` is already common) | M | 3 |
| F48 | Sync now in background (one-time) | Leave the app and watch progress | `FilesScreen.kt:153-158`, `A/data/background/SyncWorker.kt` | missing | SEAM `BGContinuedProcessingTask` on iOS 26 and later (system progress UI with Cancel). On 18.5 to 25.x, fall back to a `beginBackgroundTask` lease and say "keep the app open" | M | 4 |
| F49 | Refresh manifest | Re-LIST | `FilesScreen.kt:145` | partial (implicit per run) | SHARE `:feature:files` | S | 3 |
| **Device configuration** | | | | | | | |
| F50 | Device info card | Name, link, firmware (Detailed), ids (Developer) | `cards/DeviceInfoCard.kt:31` | partial | SHARE `:feature:device` | S | 3 (step 10) |
| F51 | Refresh config | Re-read GET_CONFIG | `A/ui/device/DeviceScreen.kt:124-126` | missing | SHARE | S | 3 |
| F52 | Sensor identity, sticker entry and barcode scan, Detailed | Records the fitted sensor module | `cards/SensorIdentityCard.kt:51`, `A/ui/device/DeviceRoute.kt:120-131` (Google Code Scanner, no camera permission), `A/data/SensorLabel.kt` | missing | SHARE card + parser. Scanner is a SEAM with VisionKit `DataScannerViewController` (`.barcode`), checking `isSupported` and `isAvailable`. Needs `NSCameraUsageDescription`, asked on tap, with manual entry as the fallback | M | 3 |
| F53 | Auto-sync switch per device | Unattended syncs for this logger | `cards/AutoSyncCard.kt:33`, `A/data/local/database/KnownDevice.kt:39`, `DeviceRoute.kt:110` | missing | SHARE switch and policy (`:core:sync` `BackgroundSyncPolicy`). Scheduling through SEAM F68 | M | 4 |
| F54 | Wake when nearby (Companion Device Manager) | The system wakes the app when the logger is in range | `A/data/background/CompanionPresence.kt`, `CompanionPresenceService.kt`, `AutoSyncCard` setup and remove | missing | SEAM. A pending `CBCentralManager.connect` to the known peripheral (it does not time out), `bluetooth-central` background mode, state restoration through `CBCentralManagerOptionRestoreIdentifierKey` and `centralManager(_:willRestoreState:)` | L | 4 |
| F55 | Protocol v1 notice | v1 loggers have no config surface | `cards/ProtocolV1Card.kt:17` | missing | SHARE | S | 3 |
| F56 | Pairing mode and PIN, reboot with confirmation, mode `none` with confirmation | Bluetooth security of the logger | `cards/PairingCard.kt:46`, `DeviceRoute.kt:145-187` | missing | SHARE `:feature:device` (SET_CONFIG and REBOOT over a bonded session) | M | 3 |
| F57 | Wi-Fi provisioning. On or off, scan, pick, passphrase, join | Puts the logger on home Wi-Fi | `cards/WifiCard.kt:68` | missing | SHARE `:feature:device`. The PSK is never echoed or logged | M | 3 |
| F58 | LAN card. Server on or off, fetch token, rotate token (confirmation), reconnect over Bluetooth | Wi-Fi sync access | `cards/LanCard.kt:40`, `DeviceRoute.kt:136-142` | partial. "Save Wi-Fi access" only (`CompanionScreen.kt:121`, `BluetoothCompanion.swift:209`) | SHARE `:feature:device` + Keychain seam (exists) | M | 3 |
| F59 | Bonds card, forget all bonds, with confirmation | Clears the logger's bond table | `cards/BondsCard.kt:22`, `DeviceRoute.kt:168-176` | missing | SHARE `:feature:device` | S | 3 |
| F60 | Per-device upload opt-in, CC BY 4.0 confirmation | Planned, off by default | not implemented (D audit 1.3, `docs/devices-and-discovery.md:406-447,585-589`) | missing on both | SHARE `:feature:device` when built. Not a parity gap today | L | later |
| **Debug (Developer level)** | | | | | | | |
| F61 | Debug screen. Transport, sync trace, status JSON, config, frame log with Clear, device log tail, raw command with a denylist for REBOOT and SET_CONFIG | Developer tools | `A/ui/debug/DebugScreen.kt:85-359`, `A/data/ble/FrameRecorder.kt`, `A/data/ble/DeviceSession.kt:366-384` | missing | SHARE `:feature:debug`. The denylist lives in `:core:sync` so both platforms share one list | M | 3 (step 11) |
| **Settings** | | | | | | | |
| F62 | Detail level Simple, Detailed, Developer | Controls how much is shown | `A/ui/settings/SettingsScreen.kt:71-105`, `A/data/settings/AppSettings.kt:24-37` | partial. `showTechnical` exists but nothing sets it, so iOS is fixed at Simple | SHARE `:core:settings` + `:feature:settings` | S | 2 |
| F63 | Units Automatic, Metric, Imperial | Display units | `SettingsScreen.kt:161-190` | present (`AppRoot.kt:239-268`) | SHARE (move into `:feature:settings`) | S | 2 |
| F64 | Show public sensors switch | Turns off every non-device network use | `SettingsScreen.kt:146-150` | present (`AppRoot.kt:271-298`) | SHARE | S | 2 |
| F65 | About. Version, privacy line, font licences | Store and licence obligations | `SettingsScreen.kt:152-156` | missing | SHARE `:feature:settings`, version through an expect (`CFBundleShortVersionString`) | S | 2 |
| **Background and notifications** | | | | | | | |
| F66 | Transfer protection with a progress notification and Stop | Sync keeps going with the screen off | `A/data/background/SyncForegroundService.kt`, `SyncNotifications.kt:63-99`, manifest `dataSync` | partial. `beginBackgroundTask` lease, pause and resume (`LanDiscovery.swift:245-310`). BLE disconnects on background (`BluetoothCompanion.swift:111-116`) | SEAM. iOS 26 and later uses `BGContinuedProcessingTask` (system Live Activity with Cancel). Below that, keep the lease. Shared progress model from `:core:sync` | M | 4 |
| F67 | Summary notification after an unattended sync (new files or failures only) | "3 new files from Kitchen" | `SyncNotifications.kt:101-124`, plurals `notif_synced`, `notif_failures` | missing | SEAM `UNUserNotificationCenter` local request. Decision and text shared | S | 4 |
| F68 | Periodic auto-sync every 15 min, battery not low, retry 2, backoff 5 min | Unattended collection | `A/data/background/AutoSyncScheduler.kt:65-67,138-139`, `SyncWorker.kt:139,153`, `AutoSyncRunner.kt` | missing | SEAM `BGProcessingTaskRequest` (`UIBackgroundModes` `processing`, `BGTaskSchedulerPermittedIdentifiers`). The OS picks the time. Policy shared in `:core:sync` | L | 4 |
| F69 | Advertising-flag trigger (offloaded scan for `new_files` and `no_utc`) | Wakes when the logger says it has new files | `A/data/background/AdvertFlagScan.kt`, `AdvertScanReceiver` | missing | SEAM, folded into F54. A background scan with a service UUID filter is possible, but iOS coalesces duplicate adverts, so a flag change is not reliable. Use the pending connect from F54 and read status after connecting | M | 4 |
| F70 | Wi-Fi arrival trigger | Sync when the phone joins home Wi-Fi | `A/data/background/SyncTriggers.kt`, `WifiArrivalReceiver` | missing | ANDROID-ONLY. iOS has no public wake on network change for a suspended app (`NEHotspotHelper` is a restricted entitlement) | none | none |
| F71 | Re-arm triggers after reboot or update | Auto-sync survives a restart | `A/data/background/BootReceiver.kt`, manifest `BOOT_COMPLETED` | n/a | ANDROID-ONLY. `BGTaskScheduler` requests and state restoration already persist across launches | none | none |
| F72 | Wi-Fi lock during LAN transfer | Full throughput with the screen off | `A/data/lan/LanWifiLock.kt` | n/a | ANDROID-ONLY. No iOS API, and none needed | none | none |
| F73 | Power and data exemptions through Companion Device Manager | Background data allowed while the logger is present | manifest `REQUEST_COMPANION_*`, `CompanionPresence.kt` | n/a | ANDROID-ONLY. No iOS equivalent | none | none |
| F74 | Notification tap opens the app | Tapping progress or summary opens opensensor | `SyncNotifications.kt:126-132` | missing | SEAM `UNUserNotificationCenterDelegate` `didReceive` opens My devices | S | 4 |
| **Permissions** | | | | | | | |
| F75 | Bluetooth on tap, rationale, settings after denial | Asked from Scan, Reconnect, Sync | `RuntimeAccess.kt:139-159,188`, `A/ui/permissions/PermissionFlow.kt` | partial. Prompt on first Find, text only on denial (`BluetoothCompanion.swift:125`) | SHARE policy (`:ui:permissions`) + SEAM `CBManager.authorization`, `UIApplication.openSettingsURLString` | S | 2 (D step 3, 8) |
| F76 | Local network on Wi-Fi actions (API 37 and later) | Asked from Wi-Fi actions only | `A/ui/permissions/LanPermission.kt`, `RuntimeAccess.kt:163-173` | partial. Denial inferred from `PolicyDenied` (`iA/LanPolicy.swift:21-26`) | SEAM. There is no status API on iOS. Model UNKNOWN and treat `PolicyDenied` as denied. Settings link | S | 2 |
| F77 | Notifications asked from the first Sync or the Auto-sync switch | Progress and summary visible | `A/ui/permissions/NotificationPermission.kt:39-61` | missing | SEAM `UNUserNotificationCenter.requestAuthorization(options:)` from the Auto-sync switch tap only | S | 4 |
| F78 | No permission wall at launch | Nothing asked on first open | `MainActivity.kt:47`, `docs/status.md` 2026-10-08 | present by construction, no dated iPhone check | SHARE policy table. Needs an iPhone fresh-install run | S | 2 |
| **Data, privacy and platform** | | | | | | | |
| F79 | Backup. Settings to cloud, archive and DB only on device-to-device, secrets never | Settings survive a new phone | `R/xml/data_extraction_rules.xml` (fixed in Wave 1) | partial. UserDefaults backed up, archive excluded (`SensorArchive.swift:59-61`), Keychain `ThisDeviceOnly` | SEAM. Keep `isExcludedFromBackup` on the archive. When `:core:settings` writes a DataStore file in Application Support, leave it backed up | S | 2 |
| F80 | LAN token encrypted at rest, plaintext migration | Token never in plain storage | `A/data/local/security/LanTokenCipher.kt`, `AqSyncApplication.kt:57` | present (Keychain `AfterFirstUnlockThisDeviceOnly`, `LanCredentials.swift:22`) | SEAM (done). Shared `CredentialStore` port | S | 3 |
| F81 | Archive handed out read-only through FileProvider | Files go only through share or save | `R/xml/file_paths.xml`, manifest provider | partial (export copies to temp) | DROP the iOS Files app exposure (`UIFileSharingEnabled` would expose a writable Documents folder that users could rename or delete). Keep export through share and save (F42 to F45) | none | none |
| F82 | Strings, RTL, Arabic font switch | One English catalogue, `supportsRtl`, Cairo for Arabic | `R/values/strings.xml` (731 strings, 43 plurals), manifest `supportsRtl`, `ui/designsystem` `Typography.android.kt` | partial (literals in Kotlin and Swift, `Typography.ios.kt:8-12`) | SHARE Compose resources (`:ui:strings`). SEAM for `Info.plist` purpose strings in a String Catalog (`InfoPlist.xcstrings`) | L | 2 (catalogue), 4 (translations) |
| F83 | Accessibility. Content descriptions, headings, roles, 48 dp targets | TalkBack reads cards and sheets | `A/ui/components/InfoButton.kt:79`, `cards/MetricHeroCard.kt:122-125`, 42 semantics sites in `A/ui` | partial (`Role.Tab`, `Role.Switch` in `AppRoot.kt:196,276`, none on My devices) | SHARE semantics in the shared screens. CMP maps them to VoiceOver. Needs a VoiceOver check on iPhone | M | 3 |
| **Engine features the user feels** | | | | | | | |
| F84 | BLE file sync | Download files without Wi-Fi | `A/data/sync/SyncEngine.kt`, `A/data/ble/BleTransport.kt` | missing (iOS syncs over LAN only) | SHARE `:core:sync` + SEAM Core Bluetooth `FrameLink` | L | 3, 4 |
| F85 | Prefer LAN, fall back to BLE | Fastest link chosen automatically | `DeviceConnectionRepository.kt:377-501` | missing (two separate UIs) | SHARE `:core:sync` | M | 3 |
| F86 | Unattended BLE with no prompt (bonded only) | Background BLE never pops a pairing dialog | `A/data/TransportFactory.kt:52-59` | missing | SHARE policy + SEAM (connect only to a known `CBPeripheral` identifier) | S | 4 |
| F87 | Two loggers at once, budget 2, third waits | Parallel collections | `DeviceIdentityClaims.kt:24-28`, `DeviceConnectionManager.kt:30-34` | partial. Third is refused (`LanDiscovery.swift:161-166`) | SHARE `:core:sync` | S | 3 |
| F88 | Station change and identity continuity | Warns when a different board answers | `A/data/DeviceContinuityPolicy.kt:31-57` | partial (HELLO must match the credential, `LanSession.swift:92-94`) | SHARE `:core:sync` | S | 3 |
| F89 | Token renewal after auth failure (ERROR 14) | LAN access heals itself over BLE | `DeviceConnectionRepository.kt:474-478,1492-1517` | partial (deletes the credential, `LanDiscovery.swift:132`) | SHARE `:core:sync` | S | 3 |

Status count. present F01, F06, F09, F11, F63, F64, F78, F80 and F10 (with a denial gap) gives 9. Android only F70 to F73 plus the four not-present families in section 3. DROP F81.

## 2. Apple APIs checked (2026-10-09)

| API | Availability against an 18.5 target | Used by | Doc |
| --- | --- | --- | --- |
| `BGProcessingTaskRequest`, `BGTaskScheduler`, `UIBackgroundModes` `processing`, `BGTaskSchedulerPermittedIdentifiers` | iOS 13 and later | F68 | https://developer.apple.com/documentation/backgroundtasks/bgprocessingtaskrequest |
| `BGContinuedProcessingTask` and `BGContinuedProcessingTaskRequest` | **iOS 26.0 and later** only, behind `if #available(iOS 26, *)`. The system shows progress in a Live Activity with Cancel and reports through `ProgressReporting`. Forums report a lock-screen pause bug fixed in 26.1, so test on 26.1 or later | F48, F66 | https://developer.apple.com/documentation/backgroundtasks/bgcontinuedprocessingtask , https://developer.apple.com/forums/thread/796138 |
| `beginBackgroundTask(withName:expirationHandler:)` | all | F48 and F66 fallback (exists) | https://developer.apple.com/documentation/uikit/uiapplication/beginbackgroundtask(withname:expirationhandler:) |
| Core Bluetooth state restoration, `CBCentralManagerOptionRestoreIdentifierKey`, `centralManager(_:willRestoreState:)`, `bluetooth-central` mode | all. In scene apps `launchOptions` is nil, so rely on `willRestoreState` | F54, F69, F86 | https://developer.apple.com/documentation/corebluetooth/cbcentralmanageroptionrestoreidentifierkey , https://developer.apple.com/documentation/corebluetooth/cbcentralmanagerdelegate/centralmanager(_:willrestorestate:) |
| `CBConnectPeripheralOptionEnableAutoReconnect` | **iOS 17.0 and later**, fine on 18.5 | F33 | https://developer.apple.com/documentation/corebluetooth/cbconnectperipheraloptionenableautoreconnect |
| AccessorySetupKit (`ASAccessorySession`) | iOS 18.0 and later. One-tap pairing with no Bluetooth prompt, and the app can remove the bond | optional for F17 and F24, see assumption A7 | https://developer.apple.com/documentation/accessorysetupkit |
| `UNUserNotificationCenter` | all | F67, F74, F77 | https://developer.apple.com/documentation/usernotifications/unusernotificationcenter |
| `UIActivityViewController` | all | F42 to F45 | https://developer.apple.com/documentation/uikit/uiactivityviewcontroller |
| `UIDocumentPickerViewController(forExporting:asCopy:)` | iOS 14 and later (in use) | F43 | https://developer.apple.com/documentation/uikit/uidocumentpickerviewcontroller |
| VisionKit `DataScannerViewController` (`isSupported`, `isAvailable`) | iOS 16 and later, A12 and later hardware | F52 | https://developer.apple.com/documentation/visionkit/datascannerviewcontroller |
| `UIApplication.openSettingsURLString` | all | F10, F24, F75, F76 | https://developer.apple.com/documentation/uikit/uiapplication/opensettingsurlstring |
| `UIFileSharingEnabled`, `LSSupportsOpeningDocumentsInPlace` | all | F81 (rejected) | https://developer.apple.com/documentation/bundleresources/information-property-list/uifilesharingenabled |
| String Catalogs (`.xcstrings`) for `InfoPlist` | Xcode 15 and later | F82 | https://developer.apple.com/documentation/xcode/localizing-and-varying-text-with-a-string-catalog |
| `URLResourceValues.isExcludedFromBackup` | all (in use) | F79 | https://developer.apple.com/documentation/foundation/urlresourcevalues/isexcludedfrombackup |

## 3. Android families that do not exist (nothing to port)

| Family | Android | Proposal |
| --- | --- | --- |
| Home screen widget | none (no `appwidget-provider`) | DROP for parity. A WidgetKit widget would need a shared reading cache. Revisit after F25 ships |
| Quick Settings tile | none | DROP |
| App shortcuts or App Actions | none (no `shortcuts.xml`) | DROP. No App Intents |
| Deep links or app links | none (only the MAIN and LAUNCHER filter) | DROP. A notification tap (F74) opens the app root |
| Share target (receiving files) | none | DROP |
| Per-app language picker | no `android:localeConfig` | Add with translations (W5 item 13), not before |
| Custom Live Activity or Dynamic Island | n/a | DROP. iOS 26 gives one for free through `BGContinuedProcessingTask` |

## 4. Work items (GitHub-issue sized)

Each item merges into `feat/platform-parity` and passes `./gradlew --no-daemon verify` plus `verifyIos`. Phone checks are dated runs in `docs/status.md`.

**1. Shared settings screen with detail level and About** (F13, F30 setting, F62, F63, F64, F65)
- Modules. `:core:settings`, new `:feature:settings`, `A/ui/settings/*`, `iS/iosMain/AppRoot.kt` settings part, `feature/discover` iosMain prefs.
- Depends on. Wave 0 `:core:settings` (merged).
- Acceptance. One `SettingsScreen` on both apps with detail level, clock consent (hidden on iOS until item 6 lands), units, public sensors and About with the version. iOS `showTechnical` comes from the detail level. Android keeps its DataStore keys. Key golden test in commonTest.
- Phone checks. Settings survive an app upgrade on both phones.

**2. Shell and navigation** (F01, F03, F04, F05, F12, F34)
- Modules. New `:feature:shell`, `A/ui/Navigation.kt`, `TabNavigator.kt`, `NavigationKeys.kt`, `iS/iosMain/AppRoot.kt`.
- Depends on. Plan step 0 ADR (nav library).
- Acceptance. One bottom bar and per-tab stacks on both. Reselect pops to root. Back or swipe collapses the Discover sheet, then returns to Discover. `TabNavigatorTest` in commonTest.
- Phone checks. iPhone edge swipe back, Android predictive back.

**3. Design system and health components** (F02, F06, F07, F08, F20, F25 visuals, F83 base semantics)
- Modules. `:ui:designsystem`, new `:ui:health`, `:ui:presentation`, `ios/App/Assets.xcassets`, `Info.plist`.
- Depends on. None.
- Acceptance. BrandCard, PillButton, InfoSheet, MascotGuide, MoodFace and WHO colours shared. `AqSyncTheme` removed. The iOS launch screen uses brand paper in light and dark.
- Phone checks. Cold launch colour on iPhone in light and dark.

**4. Shared permission policy and settings links** (F10, F75, F76, F78, plus the policy rows of F77)
- Modules. New `:ui:permissions`, `A/ui/permissions/*`, `feature/discover/.../NearMe*.kt`, iOS actuals.
- Depends on. None.
- Acceptance. One trigger table from action to permission. Every denial offers the system settings link on both platforms. Nothing is asked at launch. `PermissionFlowTest` in commonTest with the iOS status mapping.
- Phone checks. iPhone fresh install with no prompt at start, then each prompt from its own tap, then the settings link after a denial. Android Near me after a permanent denial.

**5. Strings to Compose resources and copy lint** (F82 catalogue)
- Modules. New `:ui:strings`, `feature/discover/.../DiscoverStrings.kt`, `A/ui/discover/DiscoverRoute.kt`, `R/values/strings.xml`, `iS/commonMain/CompanionState.kt`, Swift messages become enum outcomes.
- Depends on. None.
- Acceptance. No English literals in Swift or in `CompanionState`. A host test fails on em dash, colon or semicolon in any user-facing string.

**6. iOS adopts the shared engine with BLE file sync** (F28, F33, F41 engine, F84, F85, F86 policy, F87, F88, F89, F21)
- Modules. `:core:sync`, `:core:archive`, `iA/BluetoothCompanion.swift`, `LanSession.swift`, `LanDiscovery.swift`. `SensorTransfer.swift` and `SyncWire.kt` are deleted.
- Depends on. Wave 2 `:core:sync` and `:core:archive` pure moves.
- Acceptance. iOS syncs over BLE and LAN through the shared engine. LAN is preferred with BLE fallback. Automatic reconnect uses the shared policy plus `CBConnectPeripheralOptionEnableAutoReconnect`. Fake links live in commonMain and appear only in debug builds. Size, CRC-32 and PAR1 checks come before "synced". The firmware is never asked to delete, rename or rewrite.
- Phone checks. Two loggers at once, resume after a window loss, cancel, OPEN on a large file, BLE only with Wi-Fi off, on both phones.

**7. My devices merged screen** (F14, F15, F16, F17, F18, F19, F22, F23, F24)
- Modules. New `:feature:devices`, `A/ui/scan/*`, `iS/commonMain/CompanionScreen.kt` (replaced), a known-device port.
- Depends on. Items 2, 3, 6.
- Acceptance. Nearby, known and Wi-Fi loggers in one list, with Forget and Collect all on both. Forget on iOS removes the Keychain credential and explains the system Bluetooth bond. Auto-scan while visible once Bluetooth is held.
- Phone checks. Scan, forget and re-pair on iPhone. Collect all with two loggers.

**8. Today on both** (F25, F26, F27, F29, F30 dialog, F31, F32)
- Modules. New `:feature:today`, `A/ui/device/Today*`, `cards/*`.
- Depends on. Items 3 and 6.
- Acceptance. Same cards per detail level on both. SET_TIME and FLUSH need confirmation. The clock consent dialog follows the setting from item 1.
- Phone checks. SET_TIME on a logger without UTC from an iPhone.

**9. History on both** (F35, F36, F37, F38, F39)
- Modules. New `:feature:history`, `A/ui/history/*`, `:core:archive` queries.
- Depends on. Item 6 (archive port), plan step 7a.
- Acceptance. The same charts render from each phone's own archive offline. The index repair runs at start on iOS.

**10. Files, inspector and export on both** (F40 to F47, F49)
- Modules. New `:feature:files`, `A/ui/files/*`, `A/ui/history/FileMetrics*`, `A/data/export/ExportManager.kt` into `:core:archive`, iOS share and save adapter replacing `AppDelegate.swift:62-114`.
- Depends on. Item 6.
- Acceptance. Per-file share, share all, share zip, save zip and daily zip on both. The iOS share uses `UIActivityViewController` and the save uses the document picker. Temp copies are deleted after the sheet closes.
- Phone checks. Share to Files and AirDrop from an iPhone. Save the zip on Android.

**11. Device configuration on both** (F50, F51, F55, F56, F57, F58, F59, F52 card with manual entry)
- Modules. New `:feature:device`, `A/ui/device/Device*`, `cards/*`.
- Depends on. Items 6 and 8.
- Acceptance. Reboot, rotate token, forget bonds and pairing mode `none` each need confirmation. The PSK, PIN and token never show up in logs or state dumps. Sticker manual entry works on both.
- Phone checks. Wi-Fi join and token rotate from an iPhone. Pairing change, then reboot.

**12. iOS sticker barcode scan** (F52 scanner)
- Modules. iOS adapter (`DataScannerViewController`), `Info.plist` `NSCameraUsageDescription`, `docs/store-compliance.md`, `PrivacyInfo.xcprivacy` review.
- Depends on. Item 11.
- Acceptance. Camera asked only on the Scan tap. Frames are never stored. Unsupported devices hide the button. Manual entry still works.
- Phone checks. Scan a real sticker on an iPhone.

**13. Debug screen on both** (F61)
- Modules. New `:feature:debug`, `A/ui/debug/*`, frame recorder port in `:core:sync`.
- Depends on. Item 6.
- Acceptance. Developer level only. The raw command denylist rejects REBOOT and SET_CONFIG on both, tested in commonTest.

**14. iOS background sync** (F48, F53, F54, F66, F67, F68, F69, F74, F77)
- Modules. `:core:sync` `BackgroundSyncPolicy` and runner (moved from `A/data/background/AutoSyncRunner.kt`), new `iA/BackgroundSync.swift`, `Info.plist` (`UIBackgroundModes` `processing` and `bluetooth-central`, `BGTaskSchedulerPermittedIdentifiers`), `AppDelegate.swift`, `docs/store-compliance.md`, `docs/background-sync.md`.
- Depends on. Items 4 and 6, and the known-device port from item 7.
- Acceptance. The Auto-sync switch per device on iOS asks for notifications on the tap. `BGProcessingTask` runs the shared runner over LAN, or over BLE to a known bonded peripheral only. A pending connect plus state restoration wakes the app near the logger. "Sync in background" uses `BGContinuedProcessingTask` on iOS 26 and later, with the lease below that. A summary notification only appears for new files or failures, and tapping it opens My devices.
- Phone checks. iPhone locked, in Low Power Mode, and after the app is killed by the system. Restoration wake near the logger. iOS 26 continued task with Cancel. iOS 18 fallback.

**15. Accessibility pass** (F83)
- Modules. Shared screens' semantics.
- Depends on. Items 7 to 11.
- Acceptance. Headings, roles, content descriptions and 48 dp targets on every shared screen.
- Phone checks. A VoiceOver walk on an iPhone and a TalkBack walk on Android.

**16. Translations** (F82 locales, section 3 language picker) **W5 (new)**
- Modules. Compose resources `values-ar`, `android:localeConfig`, `InfoPlist.xcstrings`, `knownRegions`.
- Depends on. Item 5.
- Acceptance. Arabic RTL with Cairo on both apps. Purpose strings are translated.
- Phone checks. Arabic rendering on both phones.

Item map to plan waves. Items 1, 3, 4 and 5 go in Wave 2. Items 2, 6, 7, 8, 9, 10, 11, 12, 13 and 15 go in Wave 3. Item 14 goes in Wave 4. Item 16 goes in Wave 4 or a new W5.

## 5. Assumptions (owner asleep, sensible defaults picked)

1. **A1. Camera for the sticker scan.** iOS gets a VisionKit scanner behind a new camera permission asked on tap, with manual entry always available. This adds one Info.plist key and a store form answer. If you would rather avoid a camera permission, iOS stays manual entry only and F52's scanner becomes ANDROID-ONLY.
2. **A2. Background modes.** iOS declares `processing` and `bluetooth-central` for opt-in Auto-sync only, with an App Review note. `fetch` (app refresh) is not used.
3. **A3. Continued processing.** `BGContinuedProcessingTask` is used behind `#available(iOS 26, *)`. The 18.5 target stays, and older iOS gets today's lease with a "keep the app open" hint.
4. **A4. Wi-Fi arrival, boot re-arm, Wi-Fi lock and CDM exemptions** stay Android only. Docs will say so.
5. **A5. No Files app exposure** of the live archive on iOS. Export through share and save covers the need without letting users break the archive.
6. **A6. No custom Live Activity, widget, App Intents, shortcuts or deep links.** Android has none, and the iOS 26 system Live Activity covers progress.
7. **A7. AccessorySetupKit is not adopted now.** It would change the pairing UX and the Info.plist discovery declarations. Forget on iOS removes the app's own record and Keychain token and tells the user how to remove the bond in Settings. Revisit if pairing support issues come in.
8. **A8. iOS notification permission** is asked from the Auto-sync switch only, not from a foreground Sync tap. Foreground syncs need no notification on iOS.
9. **A9. Background BLE on iOS** only connects to peripherals already bonded and known to the app, the same as Android's unattended rule. It never triggers a pairing prompt.
10. **A10. Upload opt-in (F60)** is out of parity scope until the upload design is built. It is listed so it is not forgotten.
11. **A11.** iOS gets Detailed and Developer levels through the shared Settings item. Developer on iOS exposes the same denylisted raw command as Android.
12. **A12.** The fake devices (F21) ship only in debug builds on both platforms.
