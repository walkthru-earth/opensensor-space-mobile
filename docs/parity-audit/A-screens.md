# Audit A. Screens, navigation, UI components, view models, presentation state

Date 2026-10-09. Read only. Repo `opensensor-space-mobile` at `main` (180d1ee).
Paths below are relative to the repo root. `A/` means `app/src/main/java/earth/walkthru/aqsync/ui/`.

## 0. Headline findings

1. iOS has exactly three tabs and one hand-written device screen. `ios/shared/src/commonMain/.../CompanionScreen.kt` (228 lines) is the whole My devices tab. Android has 9 destinations, about 20 cards, 10 dialogs, 2 menus and 3 detail levels. Only Discover (`:feature:discover`) and the design tokens (`:ui:designsystem`) are truly shared.
2. iOS has no detail level. `CompanionScreen` always shows things that Android's Simple level forbids (dBm, firmware, file counts, bytes, KiB/s, board temperature). See section 3.
3. The same copy lives in two or three places and has already drifted (WHO step names, sensor state hints, waiting text, number formatting, temperature label).
4. Android's `TodayScreen`, `DeviceScreen` and `TabNavigator` are already stateless or Compose-free. They are the cheapest to move. The blockers are concentrated in ViewModels (Hilt, `Context.getString`, Room DAOs, `Intent`) and in types that still live in `:app` (`DetailLevel`, `ConnectionState`, `TransportKind`, `SyncProgress`, `ManifestEntry`, `ClockSyncMode`, Room entities).
5. Build constraint. `:ios:shared` commonMain also compiles for the JVM, so it cannot depend on `:ui:designsystem` (comment at `CompanionScreen.kt:38-45`). That is why iOS reimplements `BrandCard` with a different radius and border. New shared screens must live in Android+iOS KMP modules shaped like `:feature:discover`, not in `:ios:shared`.
6. The established precedent for shared presentation state is a plain controller (`DiscoverController`, `feature/discover/.../DiscoverController.kt:91`) with a `CoroutineScope` and a `StateFlow`. Android holds it in a thin Hilt ViewModel (`A/discover/DiscoverRoute.kt:34-45`), and iOS holds it in `remember` (`ios/shared/src/iosMain/.../AppRoot.kt:93-102`). Reuse this pattern for every feature. It keeps Hilt at the Android edge.

## 1. Screen and feature parity matrix

Legend. yes, partial, no. iOS refs point to `ios/shared/src/{commonMain,iosMain}/kotlin/earth/walkthru/opensensor/`.

### 1.1 Shell and navigation

| Android feature | Android ref | iOS | iOS ref / note |
| --- | --- | --- | --- |
| Bottom bar, Discover, My devices, Settings, Discover start tab | `A/Navigation.kt:53-66,162-194`, `A/TabNavigator.kt:6-10` | yes (reimplemented) | `AppRoot.kt:71,84-133,156-217`. Custom `TabBar`, not M3 `NavigationBar` |
| Per-tab back stacks, reselect pops to root, Back returns to Discover | `A/TabNavigator.kt:23-95` | no | `AppRoot.kt:86` single `tab` var, no stacks |
| Bar hidden for full-screen inspectors | `A/TabNavigator.kt:39` | n/a | no inspectors on iOS |
| Device sections as siblings (Today, History, Files, Device tabs) | `A/TabNavigator.kt:58-64`, `A/components/Common.kt:265` (`DeviceSectionTabs`) | no | |
| Per-entry ViewModel scope survives tab switch | `A/Navigation.kt:136-144` | no | |
| LAN mDNS browse while app started and permission held | `A/Navigation.kt:196-208`, `A/LanDiscoveryViewModel.kt` | partial | manual "Find Wi-Fi sensors" button, `CompanionScreen.kt:139-141` |
| Units provided once to every screen | `A/MainActivity.kt:41-43`, `A/components/Units.kt:13-25` | yes | `AppRoot.kt:88-91,139-154`, passed as a parameter |
| Brand theme | `A/theme/Theme.kt:27-48` (`AqSyncTheme`) | yes | `AppRoot.kt:103` (`OpenSensorTheme`) |

### 1.2 Discover tab

| Feature | Android | iOS | Note |
| --- | --- | --- | --- |
| Map, cells, charts, window, search, Near me | `A/discover/DiscoverRoute.kt:54-70` hosts `:feature:discover` | yes | `AppRoot.kt:107-113` |
| Copy | Android XML, mapped at `DiscoverRoute.kt:73-143` | `DiscoverStrings.English` | two copies of the same strings, see 3.6 |
| Host info button (bottom sheet) | `DiscoverRoute.kt:66` uses app `InfoButton` | partial | falls back to `DefaultInfoButton` (`DiscoverScreen.kt:104`) |
| Back collapses the sheet first | `DiscoverRoute.kt:59` `BackHandler` | no | iOS has no equivalent wired |
| "Add your sensor" goes to My devices | `Navigation.kt:70` (`toMyDevicesRoot`) | yes | `AppRoot.kt:111` |

### 1.3 My devices tab, root (Android `ScanKey`, iOS `CompanionScreen`)

| Feature | Android ref | iOS | iOS ref |
| --- | --- | --- | --- |
| Large title + info sheet (connect, privacy) | `A/scan/ScanScreen.kt:110-127` | partial | plain header `CompanionScreen.kt:58-63`, no info |
| Auto-scan while visible once Bluetooth is held | `ScanScreen.kt:91-95` | no | manual `Find sensors` `CompanionScreen.kt:109-111` |
| Scan / Stop floating button | `ScanScreen.kt:146-153` | partial | one button, no stop |
| Active connections list with banner and background progress | `ScanScreen.kt:163-178` | no | |
| Nearby list, bonded/known tags, archive line, signal bars | `ScanScreen.kt:209-221,269-300` | partial | name + raw dBm only, `CompanionScreen.kt:113-127` |
| Known devices not nearby | `ScanScreen.kt:222-235,341-370` | no | |
| Forget device (menu + confirm dialog) | `ScanScreen.kt:254-266,372-380` | no | |
| Mascot empty states (welcome, none nearby) | `ScanScreen.kt:192-206` | no | text only, `CompanionState.kt:167` |
| Collect all (2+ known), info sheet | `ScanScreen.kt:236-252` | no | |
| Fake BLE / fake LAN entries (debug menu) | `ScanScreen.kt:128-143` | no | |
| Scan error card | `ScanScreen.kt:184-190` | partial | message line `CompanionScreen.kt:64` |
| Wi-Fi sensors section, per-sensor Connect/Sync/Cancel, progress | Android puts LAN in Device section (`A/device/cards/LanCard.kt:40`) and an "on LAN" tag | yes (different UX) | `CompanionScreen.kt:129-201` |
| Save Wi-Fi access (GET_TOKEN) | `LanCard.kt` "Get Wi-Fi token" | yes | `CompanionScreen.kt:97-102` |
| Export Parquet files | per device in Files menu, `A/files/FilesScreen.kt:149-200` | partial | one global button `CompanionScreen.kt:202-209`, `ios/App/AppDelegate.swift:62-114` |

### 1.4 Device sections (Android only today)

| Android destination / feature | Android ref | iOS |
| --- | --- | --- |
| Today, stateless screen + route | `A/device/TodayScreen.kt:77-215`, `A/device/TodayRoute.kt:40-124` | partial. A single "Now" card, `CompanionScreen.kt:65-94` |
| Hero card, mood face, mood pill, advice, comparison, freshness | `A/device/cards/MetricHeroCard.kt:74-145` | partial. Number + "PM2.5 · title" only, no colour, face, advice |
| Today so far (local day mean, coverage) | `cards/TodaySoFarCard.kt:32` | no |
| Environment card (hero for no-PM boards) | `cards/EnvironmentCard.kt:35` | partial, gated on PM validity (see 3.4) |
| Smart Sync card | `cards/SmartSyncCard.kt:47` | no (iOS sync is LAN only) |
| Clock card, Set time from phone (confirm), Finalize buffered rows (Detailed, confirm), Smart Sync clock consent dialog | `cards/ClockCard.kt:35`, `TodayRoute.kt:89-120` | no |
| Batch estimate, Readings, Particles, Board, Storage, Device info (Detailed) | `TodayScreen.kt:188-212` | partial. Firmware and fin count shown unconditionally `CompanionScreen.kt:87-91` |
| Reconnect button and connection banner | `TodayScreen.kt:140-149`, `Common.kt:207-262` | no |
| Top bar actions (Devices, Debug, Settings, Disconnect) | `TodayScreen.kt:116-123` | partial. Disconnect only `CompanionScreen.kt:105` |
| History (range, metric chips, station chips, chart, calendar, day ring, archive summary, Developer source card) | `A/history/HistoryScreen.kt:83-418`, `BandColumnChart.kt:64`, `CalendarHeatmap.kt:38`, `DayRing.kt:48` | no |
| Files (manifest by local day, sync all, stop, per-file sync/share/inspect, menu with background sync, auto-sync, share/save zip, share all, share daily, disconnect, chart card) | `A/files/FilesScreen.kt:89-508`, `FileChartCard.kt:57`, `FileGrouping.kt` | no (only global export) |
| Metrics inspector (Detailed) | `A/history/FileMetricsScreen.kt:64-230` | no |
| Device config. Info, Sensor identity with barcode scan, Auto-sync, v1 card, Pairing, Wi-Fi scan/join, LAN, Bonds | `A/device/DeviceScreen.kt:99-238`, `cards/*` | no |
| Confirm dialogs. Reboot, Rotate token, Forget bonds, Pairing none | `A/device/DeviceRoute.kt:145-187` | no |
| Debug (Developer). Transport, sync trace, status JSON, config, frames, device log, raw command | `A/debug/DebugScreen.kt:85-359` | no |

### 1.5 Settings tab

| Feature | Android ref | iOS | iOS ref |
| --- | --- | --- | --- |
| Detail level Simple / Detailed / Developer + one-line explanation | `A/settings/SettingsScreen.kt:71-105` | no | |
| Smart Sync clock consent Ask / Automatic / Never | `SettingsScreen.kt:107-141` | no | |
| Units Automatic / Metric / Imperial + "Follows your phone's region" | `SettingsScreen.kt:161-190` | yes | `AppRoot.kt:239-268` |
| Show public sensors switch + body | `SettingsScreen.kt:146-150` | yes | `AppRoot.kt:271-298` |
| Info sheet for Settings | `SettingsScreen.kt:59` | no | |
| About (version, privacy line, font licences) | `SettingsScreen.kt:152-156` | no | |

### 1.6 Detail levels

Defined in `app/src/main/java/earth/walkthru/aqsync/data/settings/AppSettings.kt:24-36` (`:app`, not shared). Rules in `docs/screens.md:56-78`. iOS has no level at all. Android itself is inconsistent in naming. `advancedMode` means Developer in Today, Device, History and Debug (`TodayViewModel.kt:136`, `DeviceViewModel.kt:98`, `AppSettings.kt:85`), but Detailed in Files (`FilesViewModel.kt:199`, which matches the docs for the Metrics inspector). The `DetailLevel.DETAILED` KDoc (`AppSettings.kt:28`) says it shows "the History source card" while History gates that card on Developer (`HistoryScreen.kt:218`, docs `screens.md:78`). `NavigationKeys.kt:38` still says "Advanced mode only (Detailed level later)".

### 1.7 Shared UI components

| Component | Android | iOS | Shared? |
| --- | --- | --- | --- |
| Tokens, schemes, shapes, typography, fonts | `:ui:designsystem` | same | yes |
| FloatingControl, FloatingSurface, FloatingIconButton, FloatingTextButton | `:ui:designsystem/FloatingControls.kt` | used in `AppRoot` | yes |
| BrandCard + CardTone, PillButton, OutlinePillButton, Peacock, MascotGuide, TiltedBadge, MoodPill | `A/components/Brand.kt` | own `BrandCard` `CompanionScreen.kt:214-226`, `SettingsCard` `AppRoot.kt:303-313` | no |
| InfoButton, InfoSheet, CardHeader | `A/components/InfoButton.kt` | `DefaultInfoButton` inside discover | no |
| Eyebrow, StatusDot, ConnectionBanner, DeviceSectionTabs, TransportChip, SectionCard, WarningNote, InfoNote, StatLine, TickingText | `A/components/Common.kt` | none | no |
| MoodFace, mood/PMS/WHO copy | `A/components/MoodFace.kt`, `MoodText.kt` | uses `AirMood.title`, `NowReading.hint` from core | no |
| SignalBars | `A/components/SignalBars.kt` | raw "dBm" text | no |
| WHO step colour, contrast helpers | `A/theme/Color.kt` | none on My devices | no |
| Icons | ~38 Material icons (`material-icons-extended`) | `DiscoverIcons` ImageVectors | partial |

## 2. What blocks moving each Android screen and ViewModel to commonMain

Types that live in `:app` and are used by screens (owned by other audits, listed because screens depend on them) are `DetailLevel`, `ClockSyncMode`, `ClockQuality` (`data/sync/SmartSyncPolicy.kt`), `ConnectionState`, `TransportKind` (`data/ble/AqTransport.kt`), `ScannedDevice` (`data/ble/BleScanner.kt`), `ManifestEntry`, `FileSyncState`, `HivePartition`, `SyncPlan` (`data/sync/SyncPlanner.kt`), `SyncProgress`, `ArchiveTimeSummary`, `ReconnectState`, `LogTailState`, `WifiScanState`, `TimedLive`, `TimedStatus` (`data/DeviceConnectionRepository.kt`), `DeviceMessage`, `DeviceEvent`, `SensorLabel`, `LanFailure`, `BluetoothStatus`, and the Room entities `KnownDevice`, `SensorInstallation`, `ArchivedFile`. Already common are `AirMood`, `NowReading`, `TodaySoFar`, `Metrics`, `MetricSpec`, `HistoryColumn`, `HistoryRange`, `HistorySeries`, `HistoryWindow`, `ChartAxes`, `FileSummary`, `DeviceCapabilities` (`:core:history`), `PairMode`, `ConfigPatch`, `WifiState`, `PmsStatus`, `LiveSample`, `LiveProfiles`, `PlantowerBatchCandidate`, `ConfigProblem`, `Frame` (`:core:protocol`), `WhoStep`/`WhoGuidelines` (`:core:airquality`), `UnitFormatter`/`UnitSystem` (`:core:units`).

Cross-cutting seams (every screen) are 743 `R.string`/`R.plurals` references, 38 Material icons, `java.time` formatting (`DateTimeFormatter`, `ZoneId`, `Locale`), `String.format(Locale.US)` in `cards/CardBits.kt:29-36`, `hiltViewModel()` and `collectAsStateWithLifecycle`.

| File | Android-only dependencies | Class |
| --- | --- | --- |
| `TabNavigator.kt`, `NavigationKeys.kt` | `androidx.navigation3.runtime.NavKey` only | move as-is (replace `NavKey` with an own `@Serializable sealed interface`, or KMP nav3). Test `TabNavigatorTest.kt` moves to commonTest |
| `Navigation.kt` | nav3 `NavDisplay`, `rememberViewModelStoreNavEntryDecorator`, `hiltViewModel`, `LifecycleStartEffect`, Material icons, `R.string` | move after small seam (nav library choice, icons, strings, route slots) |
| `MainActivity.kt` | Activity, Hilt, edge to edge | stays platform (entry point) |
| `LanDiscoveryViewModel.kt` | Hilt, `DeviceConnectionManager` | stays platform (OS service). iOS equivalent is `ios/App/LanDiscovery.swift` |
| `settings/SettingsScreen.kt` | `R.string`, `BuildConfig.VERSION_NAME`, `DetailLevel`, `ClockSyncMode`, `hiltViewModel` | move after small seam |
| `settings/SettingsViewModel.kt` | Hilt `ViewModel`, `AppSettings` (DataStore) | move after small seam (common `SettingsStore` interface, Android DataStore and iOS NSUserDefaults actuals) |
| `discover/DiscoverRoute.kt` | `R.string` (56 refs), Hilt VM, `java.util.TimeZone`, `DiscoverStore`, `BackHandler` | move after small seam (strings to compose resources, prefs injected) |
| `scan/ScanScreen.kt` | `R`, Material icons, `ScannedDevice`, `KnownDevice` (Room), `FakeTransport`, permission composables, `java.time` | needs data port first |
| `scan/ScanViewModel.kt` | Hilt, `Context` (`BleScanner.scan(context)`, `getString`), `KnownDeviceDao`, `ArchivedFileDao`, `MetricBucketDao`, `AutoSyncScheduler`, `DeviceConnectionManager`, `android.util.Log` | needs data port first |
| `device/TodayScreen.kt` | `R`, icons, `ConnectionState`, `TemperatureSource` (common) | move after small seam (stateless `TodayUiState` + `TodayAction` already) |
| `device/TodayRoute.kt` | `hiltViewModel`, `R`, icons, `java.time` `phoneClock()` (`TodayRoute.kt:123`) | move after small seam |
| `device/TodayViewModel.kt` | Hilt assisted, `DeviceConnectionManager`, `HistoryRepository`, `KnownDeviceDao`, `FakeTransport`, `java.time.LocalDate/ZoneId` | needs data port first |
| `device/DeviceScreen.kt` | `R`, icons, `ConnectionState`, `SensorInstallation` (Room) | move after small seam (UI model for installation) |
| `device/DeviceRoute.kt` | `android.app.Activity`, `IntentSenderRequest` (CompanionDeviceManager), `LocalContext`, Hilt, `R` | move after small seam, with a platform `WakeWhenNearby` slot hidden on iOS |
| `device/DeviceViewModel.kt` | Hilt assisted, `@ApplicationContext Context` (16 uses), `IntentSender`, `Log`, 13 `R.string`, `KnownDeviceDao`, `SensorInstallationDao`, `CompanionPresence`, `AutoSyncScheduler`, `TransportFactory` | needs data port first (heaviest VM) |
| `device/cards/*` (19 files) | `R`, a few icons, `java.time` in `ClockCard.kt`, `java.util.Locale` in `CardBits.kt`, `WifiScanState`, `ConfigSnapshot`, `AutoSyncScheduler.PERIOD_MINUTES` | most move after small seam. `SensorIdentityCard` needs a barcode scanner seam (Google Code Scanner) |
| `history/HistoryScreen.kt` | `R`, icons, `java.time` | move after small seam |
| `history/BandColumnChart.kt`, `CalendarHeatmap.kt`, `DayRing.kt` | `java.time`, `R` (labels) | move after small seam (pure Canvas over common types) |
| `history/HistoryPresentation.kt` | `ArchivedFile` (Room), `HivePartition`, `java.time` | needs data port first (or a small UI model) |
| `history/HistoryViewModel.kt` | Hilt assisted, 4 DAOs (14 refs), `HistoryRepository`, `java.time.ZoneId` | needs data port first |
| `history/FileMetricsScreen.kt` | `R`, icons, `java.time` | move after small seam |
| `history/FileMetricsViewModel.kt` | Hilt, `FileArchive`, `ParquetIndexer` (app) but `ParquetFile` is common in `:core:parquet` | needs data port first (small, file read port) |
| `files/FilesScreen.kt` | `R`, 13 icons, `LocalContext` (launch intents), `ManifestEntry`, `FileSyncState`, `SyncProgress`, `AutoSyncScheduler` | move after small seam once sync types are common |
| `files/FileGrouping.kt` | `java.time`, `ManifestEntry`, `HivePartition` | move after small seam (pure logic, belongs in commonMain with commonTest) |
| `files/FileChartCard.kt` | `R`, icons, `java.time` | move after small seam |
| `files/FilesViewModel.kt` | Hilt, `Context`, `Intent`, `Uri`, `java.io.File`, `ExportManager`, 2 DAOs | needs data port first (export/share seam) |
| `debug/DebugScreen.kt` | `R`, icons, `java.time`, `LogTailState`, `FrameDirection` | move after small seam |
| `debug/DebugViewModel.kt` | Hilt, `DeviceConnectionManager`, `RawFrameRecord`, `SyncDiagnostic` | needs data port first |
| `components/Brand.kt` | `R.drawable` peacock (`painterResource`), one icon | move after small seam (webp to composeResources) |
| `components/Common.kt` | `Intent(Settings.ACTION_BLUETOOTH_SETTINGS)` (`Common.kt:248`), `LocalLifecycleOwner` in `TickingText`, `ConnectionState`, `TransportKind`, `ReconnectState`, `java.util.Locale` | move after small seam (open-settings `expect`, KMP lifecycle) |
| `components/DeviceMessages.kt` | `android.content.res.Resources`, `@StringRes Int` mapping, `DeviceMessage` (app) | needs data port first, then `StringResource` based `UiText` |
| `components/InfoButton.kt`, `MoodFace.kt`, `MoodText.kt`, `SignalBars.kt` | `R`, one icon | move after small seam (resources only) |
| `components/Units.kt` | `android.icu.util.LocaleData` | move after small seam. Give `:core:units` an Android-accurate actual (today its jvm actual is region only, `core/units/src/jvmMain/.../SystemUnits.jvm.kt:6`) |
| `components/Previews.kt`, `device/ScreenPreviews.kt` | Android tooling | stays androidMain |
| `theme/Theme.kt` | `LocalContext` for dynamic colour (never enabled) | move as-is by deleting it in favour of `OpenSensorTheme` |
| `theme/Color.kt` | none beyond common core types | move as-is |
| `permissions/*` | Android permissions | stays platform. Screens call through a common `AccessGate` interface |

## 3. Same decision, different implementation

| # | Decision | Android | iOS (or second Android copy) | Effect |
| --- | --- | --- | --- | --- |
| 3.1 | WHO step names | `app/src/main/res/values/strings.xml:115-120` "within the WHO guideline", "within WHO interim target 4", "above every WHO target" (used by `MoodText.kt:74-83`) | `strings.xml:928-933` and `feature/discover/.../DiscoverStrings.kt:61-67` "Meets WHO guideline", "Interim target 4", "Above all targets" | Two wordings for one scale inside Android itself. Pick one set and put it in one resource |
| 3.2 | Sensor state hint (warming) | `strings.xml:126` "This takes about a minute after power on." | `core/history/.../AirMood.kt:71-76` (`NowReading.hint`, shown by iOS at `CompanionScreen.kt:75,177`) "It takes about a minute after power-on." | Copy fork. `docs/screens.md:212` already notes "iOS still reads the copy in AirMood". Mood titles are also duplicated (`AirMood.kt:27-32` vs `strings.xml:96-111`), equal today |
| 3.3 | Waiting text | `strings.xml:87` "Waiting for the first reading…" | `CompanionScreen.kt:76` "Waiting for a particle reading…", `:178` "Waiting for a fresh live reading…", `CompanionState.kt:213` "Waiting for a fresh reading…" | Three iOS variants, one Android |
| 3.4 | Temperature on the live card | `EnvironmentCard.kt:26-27` uses `Metrics.AMBIENT_TEMP.label` ("Temperature") or `BOARD_TEMP`. Board temperature only at Detailed (`TodayScreen.kt:188-199`, `BoardCard`). Shown regardless of PM validity | `CompanionScreen.kt:80-85` uses `TemperatureSource.label` ("Ambient temperature" / "Board temperature", `core/protocol/.../LiveProfile.kt:6-7`), always, and only when `pmValid` is true | Different label, wrong gate (a no-PM board never shows temperature on iOS), Simple rule broken |
| 3.5 | PM number formatting | `cards/CardBits.kt:36` `fmtPm`. Integer at or above 100, one decimal otherwise, trailing ".0" removed | `CompanionScreen.kt:228` `round(v*10)/10).toString()` gives "172.0", "9.0" | Different digits for the same reading. Put one formatter in common |
| 3.6 | Discover copy source | XML + 70-line mapping `DiscoverRoute.kt:73-143` | `DiscoverStrings.English` literals (`DiscoverStrings.kt:101-140`) with a "keep in step" comment | Manual sync of about 50 strings. Compose resources remove both |
| 3.7 | Signal strength | `SignalBars`, dBm only at Developer (`ScanScreen.kt:291`) | `CompanionScreen.kt:122` raw "-60 dBm" always | Simple rule broken on iOS (`screens.md:60` "no RSSI numbers") |
| 3.8 | Internals at Simple | firmware in Device card (Detailed), storage counters (Detailed), rate (Detailed), bytes (Developer) | `CompanionScreen.kt:89` fin count, `:91` firmware, `:183-184` bytes and KiB/s, always | iOS behaves like Developer |
| 3.9 | Mood presentation | face, WHO step colour, pill, advice, comparison (`MetricHeroCard.kt:98-137`) | "PM2.5 · Fresh air", no colour (`CompanionScreen.kt:72-73,174-175`) | Health scale not shown on iOS My devices at all |
| 3.10 | Content card | `Brand.kt:58-78` 32 dp (`shapes.extraLarge`), PLAIN on `surfaceContainerLow`, 1 dp border, HERO on `primaryContainer` | `CompanionScreen.kt:214-226` 18 dp (`shapes.medium`), `surfaceContainerHigh`, 0.5 dp. `AppRoot.kt:303-313` `SettingsCard` 16 dp (`FloatingControl.Shape`) | Three radii for "a card" |
| 3.11 | Buttons | `PillButton` 16 dp `FloatingControl.Shape` + `primaryButtonColors()` + arrow (`Brand.kt:85-107`) | `shapes.small` 12 dp, default M3 colours (`CompanionScreen.kt:98,105,109,124`) | Floating control rule applied differently |
| 3.12 | Bottom bar | M3 `NavigationBar` + `navigationItemColors()`, Material Icons, `R.string.tab_*`/`settings_title` (`Navigation.kt:162-194`) | custom `TabBar`, 56x32 SmallShape indicator, `DiscoverIcons`, literals (`AppRoot.kt:156-217`) | Two bar implementations, two icon sets |
| 3.13 | Settings controls | segments `FloatingControl.segmentShape` + `segmentedColors()` (`SettingsScreen.kt:78-79`). Switch with default colours (`:148`). Flat column under a TopAppBar | segments `SegmentedButtonDefaults.itemShape(..., shapes.small)` + inline colours (`AppRoot.kt:247-255`). Switch with Signal blue track (`AppRoot.kt:280-291`). Cards, no top bar | Same row, different look |
| 3.14 | Automatic units | ICU measurement system (`A/components/Units.kt:20-25`), re-resolved on activity recreate | `NSLocaleMeasurementSystem` via `:core:units` `systemUnitSystem()` (`core/units/src/iosMain/.../SystemUnits.ios.kt:13-20`), live notification | Android bypasses the common `expect`. Its jvm actual (region only) is unused by the app |
| 3.15 | LAN status wording | `ConnectionState.describe()` (`Common.kt:189-205`), `TransportChip` | "Wi-Fi · Connected / Connecting… / Discovered / Not connected / Sync paused" (`CompanionScreen.kt:155-161`) | Different vocabulary for connection state |
| 3.16 | Empty states | `MascotGuide` with `scan_welcome_*` / `scan_none_nearby_*` | "No sensors found. Keep your sensor nearby and try again." (`CompanionState.kt:167`) | No mascot on iOS, different copy |
| 3.17 | Copy ownership | strings in resources, typed messages, but VMs still call `context.getString` (`ScanViewModel.kt:163,172`, `DeviceViewModel.kt:233-407`, `FilesViewModel.kt:226-275`) | English in Kotlin state (`CompanionState.kt:111-223`) and Swift (`AppDelegate.swift:59-113`, `BluetoothCompanion.swift:281`) | Neither side meets the W10 rule "no English in ViewModels". iOS copy is not localisable |
| 3.18 | Detail flag naming | `advancedMode` means Developer in 4 VMs and Detailed in Files | none | see 1.6 |

## 4. Target module layout and ordered migration

### 4.1 Layout

All new UI modules follow `feature/discover/build.gradle.kts` (KMP, `android.kotlin.multiplatform.library`, `iosArm64`, `iosSimulatorArm64`, compose resources with `generateResClass = always`). None target the plain JVM.

```
:ui:designsystem        tokens, theme, FloatingControls (exists)
                        + BrandCard/CardTone, PillButton, OutlinePillButton, Eyebrow, SectionCard,
                          StatLine, WarningNote, InfoNote, InfoButton/InfoSheet/CardHeader,
                          MascotGuide/Peacock (webp in composeResources/drawable), TiltedBadge,
                          AppIcons (vendored ImageVectors, DiscoverIcons pattern)
:ui:health   (new)      WhoStep colours, contrast, MoodFace, MoodPill, mood/PMS/WHO copy as
                          compose resources. Depends on :core:airquality, :core:history
:ui:presentation (new)  DetailLevel, LocalDetailLevel, LocalUnits, UiText(StringResource),
                          ScreenMessage, number formatters, expect date/time formatter,
                          AccessGate and Share/OpenSettings seams (expect or interfaces)
:feature:shell  (new)   TopTab, TabNavigator, keys, shared bottom bar, AppShell(slots).
                          Android MainActivity and iOS MainViewController both host it
:feature:settings       SettingsController + screen, SettingsStore interface
:feature:devices        My devices list (today's ScanScreen + iOS CompanionScreen merged)
:feature:today          TodayScreen, cards, dialogs, TodayController
:feature:history        HistoryScreen, charts, HistoryController
:feature:files          FilesScreen, FileGrouping, FileChartCard, FileMetrics, FilesController
:feature:device         DeviceScreen, config cards, dialogs, DeviceController
:feature:debug          DebugScreen, DebugController
:feature:discover       exists. Switch DiscoverStrings to compose resources
```

Presentation state pattern. A plain `XController(scope, ports...)` exposing `StateFlow<XUiState>` and `onAction(XAction)` in commonMain, tested in commonTest. Android wraps it in a 5-line Hilt ViewModel (as `DiscoverViewModel` does). iOS creates it in a retained holder from `iosMain`. Ports are Kotlin interfaces that the transport, sync and storage audits deliver (device session, known devices, archive queries, export). Swift implements those ports, never UI state. `CompanionState` shrinks to an adapter, then goes.

### 4.2 Ordered steps

| Step | Work | Size | Depends on | Parallel with (no shared files) |
| --- | --- | --- | --- | --- |
| 0 | Spike and ADR. Confirm a multiplatform Navigation 3 artifact (JetBrains `org.jetbrains.androidx.navigation3`) and KMP lifecycle that match CMP 1.12.1, Kotlin 2.4.20 and androidx nav3 1.1.7. Confirm `org.jetbrains.compose.material3` 1.9.0 and BOM 2026.09.00 resolve to one material3 on Android. Decide the fallback (own `when(key)` display over `TabNavigator` + `SaveableStateHolder`) | S | none | 1 |
| 1 | Fix drift without moving code. One WHO step wording, one PMS hint set, one waiting line, a common `formatPm` (in `:core:history` or `:core:units`), iOS hides dBm/firmware/bytes/fin and fixes the temperature gate and label. Make `advancedMode` names honest. Files `CompanionScreen.kt`, `AirMood.kt`, `strings.xml`, `CardBits.kt` | S | none | 0, 2 |
| 2 | `:ui:designsystem` components + `AppIcons` + peacock resource. Move `Brand.kt`, `Common.kt` (minus the Settings intent and lifecycle seams), `InfoButton.kt`, `SignalBars.kt`. Android re-imports. Delete `AqSyncTheme` in favour of `OpenSensorTheme` | M | none | 1, 3 |
| 3 | `:ui:presentation` + `:ui:health`. Move `DetailLevel` and `ClockSyncMode` enums out of `:app`, `theme/Color.kt`, `MoodFace`, `MoodText` to resources, `UiText`, formatters, expect date formatter (NSDateFormatter / java.time), Android units actual | M | none | 1, 2 |
| 4 | `:feature:settings`. `SettingsStore` interface, Android `AppSettings` and iOS `NSUserDefaults` implementations, one screen with detail level, clock consent, units, public sensors, About (version passed in). Deletes `SettingsScreen.kt` body and `IosSettings` | M | 2, 3 | 5 |
| 5 | Discover strings to compose resources. Remove `DiscoverStrings.English` and the 70-line Android mapping. Host info button and back handling inside the module | S | 2 | 4 |
| 6 | `:feature:shell`. Move `TabNavigator`, keys, bottom bar. Android and iOS host one `AppShell` with route slots so unmigrated Android screens keep working | M | 0, 4 | 7a |
| 7a | History charts (`BandColumnChart`, `CalendarHeatmap`, `DayRing`) and `FileGrouping` to commonMain with kotlinx-datetime, plus commonTest | M | 3 | 6 |
| 7b | `:feature:today`. Move stateless `TodayScreen`, cards, dialogs. `TodayController` behind device session and archive ports. iOS Now card replaced | L | 6, transport/sync ports | 7c |
| 7c | `:feature:devices`. Merge `ScanScreen` and `CompanionScreen` into one list (nearby, known, Wi-Fi, forget, collect all). Needs known device and scanner ports | L | 6, transport/storage ports | 7b |
| 8 | `:feature:history` screen + controller | L | 7a, storage port (DAOs) | 9 |
| 9 | `:feature:files` + Metrics inspector, export/share seam (Android intents, iOS document picker) | L | 7a, sync types in common | 8 |
| 10 | `:feature:device` config cards and dialogs, barcode and CompanionDeviceManager as Android-only slots | L | 7b, config/command port | 11 |
| 11 | `:feature:debug` | M | frame recorder port | 10 |
| 12 | Delete `CompanionScreen.kt`, `CompanionState` UI fields, `AppRoot` duplicate pieces. Record Android phone and iPhone runs in `docs/status.md` | S | all | none |

Lanes. Steps 1, 2, 3 run together on disjoint files. Steps 4 and 5 run together. 7a can run beside 6. 7b and 7c run together once ports land. 8 and 9 run together. 10 and 11 run together.

## 5. Risks and pinned versions

From `gradle/libs.versions.toml`. Kotlin 2.4.20, Compose Multiplatform 1.12.1, CMP material3 1.9.0 (separate pin), androidx Compose BOM 2026.09.00 (material3 1.4.0 per `theme/Theme.kt:23-25`), androidx Navigation 3 1.1.7, `lifecycle-viewmodel-navigation3` 2.11.0, lifecycle 2.11.0, Hilt 2.60.1, androidx Hilt 1.4.0, Room 2.8.5, kotlinx-datetime 0.8.0, coroutines 1.11.0, maplibre-compose 0.19.0 (pins Kotlin 2.4.20 and CMP 1.12.1), iOS deployment target 18.5.

1. Navigation library. Android uses androidx nav3 artifacts and `rememberViewModelStoreNavEntryDecorator`, which are Android builds. A KMP nav3 needs explicit polymorphic serializers for keys on iOS (no reflection). Verify the JetBrains artifact version for CMP 1.12.1 before committing. `TabNavigator` is already Compose-free, so a minimal own host is a safe fallback.
2. Version lockstep. maplibre-compose ties Kotlin and CMP. Any CMP upgrade for navigation or resources moves Discover too. The two material3 pins (CMP 1.9.0 vs BOM) must resolve to one artifact on Android, and Material3 experimental opt-ins differ.
3. Resources. About 774 Android strings and plurals plus one webp in several density folders. CMP resources support `%1$s` and plurals, but VMs that call `context.getString` must switch to typed `UiText` and the suspend `getString`. Localisation tooling for Android XML stops covering moved strings.
4. Icons. 38 Material icons. `material-icons-extended` for CMP is no longer published past 1.7.x, so vendor ImageVectors (as `DiscoverIcons.kt` does) or vector XML in compose resources.
5. Date and number formatting. kotlinx-datetime 0.8.0 has no localised month names. History, Files, Clock and Debug use `DateTimeFormatter` patterns. Needs an `expect` formatter, otherwise the UI loses locale formatting.
6. Hilt. Do not remove it. Keep it at the Android edge wrapping shared controllers. Adding Koin or KMP ViewModel to common code is optional and should follow the step 0 ADR.
7. ViewModel lifetime. Android keeps device VMs alive while another tab is shown (`Navigation.kt:134-144`). Shared controllers need the same retained scope on iOS or device state resets on tab switch.
8. Room entities in UI. `KnownDevice`, `SensorInstallation` and `ArchivedFile` appear in composables. Introduce UI models first. Whether Room KMP serves iOS belongs to the storage audit, since iOS archives with FileManager receipts.
9. Platform seams that must stay native. Permissions (`ui/permissions/*`), share/save intents, Google Code Scanner, CompanionDeviceManager, Bluetooth settings intent, iOS document picker, back handling. Each becomes an interface or slot, never an `if (android)` in common.
10. Build shape. `:ios:shared` commonMain compiles for JVM and cannot see `:ui:designsystem`. Shared screens must not be added there. They go in Android+iOS feature modules that `:ios:shared` iosMain and `:app` consume.
11. Evidence. Moving a screen changes both apps. Each step needs `./gradlew --no-daemon verify`, the iOS simulator build, and for radio, permission and background paths a dated phone run on each platform (`AGENTS.md` Gate and Boundaries).
