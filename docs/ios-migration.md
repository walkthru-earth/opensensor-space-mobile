# iOS migration, first live-reading app

**Scope:** Kotlin Multiplatform source sets, the Compose iOS shell, Core Bluetooth,
macOS builds and remaining feature migration.
**Code:** `core/*/src/commonMain`, `ui/designsystem`, `feature/discover`, `ios/shared`,
`ios/App`, `ios/OpenSensor.xcodeproj`.
**Related:** [architecture.md](architecture.md), [transports.md](transports.md),
[build-and-release.md](build-and-release.md), [status.md](status.md).

For code changes, also follow [`../ios/AGENTS.md`](../ios/AGENTS.md) and the root
[`../AGENTS.md`](../AGENTS.md). This guide describes the current implementation and
its migration gaps; it is not a claim of Android/iOS feature parity.

## First stage

Android and iOS compile the same wire frame codec, JSON/configuration models,
advertisement decoder, LAN envelope/handshake constants, reviewed live profiles, Plantower candidate equation,
metric registry, sensor validity/mood rules and monotonic-time estimates.
The firmware protocol and all existing wire values are preserved.

Every `:core` module (`protocol`, `parquet`, `history`, `publicfeed`, `units`, `airquality`,
`archive`, and the empty `sync` and `settings` skeletons) has a Java 17 JVM target for Android and
host consumers, plus `iosArm64` and `iosSimulatorArm64`. Portable source lives in `commonMain`,
platform adapters in `jvmMain` and `iosMain`. The W2 source move is complete. No module maps
`src/main/kotlin` any more.
`core:parquet` reads mapped `NSData` and inflates GZIP with the system zlib on iOS (W8,
2026-10-08), and its oracle tests decode the real fixtures on the simulator. Discover reads its
bundled sample through this reader on iOS. The iOS archive does not use the reader or the index
yet. The `test` compatibility tasks run `jvmTest`.

The iOS app uses Compose Multiplatform 1.12.1 (Material 3 1.9.0), the existing
Kotlin 2.4.20 and Xcode direct framework integration. `ios:shared` targets iOS only. Its
presentation-state tests run on the simulator in `verifyIos`, and its `commonMain` depends on
`:feature:discover` and `:ui:designsystem`. The Android app continues to use its existing screens.
Shared Android and iOS screens beyond Discover are planned in
[platform-parity-plan.md](platform-parity-plan.md).

The iOS live screen can discover AQ Sync service advertisements, connect one
sensor, read INFO/STATUS/LIVE and subscribe to live/status updates. Missing,
invalid, disconnected and expired readings remain unavailable. Ambiguous
board temperature and humidity are shown only for reviewed schema/dictionary
profiles. Core Bluetooth owns pairing prompts. Operations are serialized and
scan/connection timers bound attempts. The app sends no firmware-changing
commands and has no accounts, analytics or cloud connection. Location is planned as approximate
only, asked on a "Near me" tap (`NSLocationDefaultAccuracyReduced`). The
discover map adds only anonymous, read-only feed and basemap GETs, and uploads stay a per-device
opt-in ([devices-and-discovery.md](devices-and-discovery.md#network-boundary)).

Since W12 (2026-10-08) the Compose root (`ios/shared/src/iosMain/.../AppRoot.kt`) has one bottom
bar with three tabs, Discover, My devices (the screen above) and Settings ("Show public sensors").
No other iOS screen draws a bar. Discover is always the start tab (owner decision 2026-10-08). It is the shared
`:feature:discover` screen, reading the bundled sample through the common Parquet reader and the
vendored H3 C library through cinterop. The Xcode target links `-lc++ -lz` and CoreFoundation,
CoreGraphics, CoreText, Foundation, ImageIO, Metal and QuartzCore for maplibre-compose. Checked in
the iPhone 18 Pro simulator (PMTiles basemap and sample cells drawn) and, on 2026-10-08, on an
iPhone 16 Pro, which opened on Discover with no prompt. On that iPhone, Arabic place names drew
unjoined, because the Apple maplibre-native-ffi build has no bidi or Arabic shaping. iOS now uses
the English or Latin label styles (`devices-and-discovery.md`, "Right-to-left labels on iOS").

Since #16 (2026-10-09) the My devices tab is the shared `:feature:devices` screen, the same as
Android. `DevicesBridge` (`ios/shared`) feeds its three ports from `CompanionState`, the known
records saved in UserDefaults (peripheral id, name, station, hardware id, last opened, never a
credential), the Keychain credential list (`LanCredentials.list`, ids only, never the token) and
the receipt index. Bluetooth access is asked from My devices through `:ui:permissions`. When it is
already allowed, `BluetoothCompanion` creates the central manager at launch with no prompt, and
the list scans while it is visible. Its combined scan also starts a bounded Bonjour browse,
which can trigger the Local Network prompt on tab entry when Bluetooth access was already held.
Opening an enrolled sensor attempts authenticated Wi-Fi from a matching discovery result and
saved Keychain access, then opens shared Today. Bluetooth can connect through its saved
identifier (`retrievePeripherals`) even when it is absent from the scan. With Bluetooth denied,
a known sensor can still open through an authenticated Wi-Fi session.
Forget, after confirmation, drops the record and the Keychain Wi-Fi access and closes any open
link. The system bond stays until the user removes it in iOS Settings, which the dialog says.
A forgotten sensor comes back only when the user opens it again. Collect all runs one sensor at a
time while the app is open, over Wi-Fi when the sensor was found there with access, else over
Bluetooth, and stops when the app leaves the foreground. A step never takes over a link the user
holds. It skips a sensor the user has open on Bluetooth or Wi-Fi (a Wi-Fi session maps to its
known sensor through the TXT station), fails while another sensor holds the Bluetooth link, closes
only what it opened, and fails when its sync has not started within a minute. Opening a sensor
cancels Collect all first. My devices **Scan** starts both the Bluetooth search and a bounded
Wi-Fi browse, and the list merges their results. **Find Wi-Fi sensors** remains available on the
device page.

Wi-Fi discovery is also available through `NWBrowser`, restricted to
`_aqsync._tcp` in `local.`. Tapping **Scan** on My devices or **Find Wi-Fi sensors** on
the device page starts a bounded browse and lets iOS request Local Network access. `Info.plist` declares
`NSLocalNetworkUsageDescription` and `NSBonjourServices`. Adding the description
alone does not request access; the real browse triggers the system decision.
Browser waiting states retain the browse while the user answers; they can also
mean unavailable Wi-Fi, so the UI never falsely labels them as a permission denial.
Discovery retains Bonjour service endpoints and station/hardware TXT hints. Each phone
must first connect over Bluetooth and tap **Save Wi-Fi access**; `GET_TOKEN` is sent through
the encrypted BLE control/response characteristics. The token and BLE-established identity
are saved in device-only Keychain storage and never displayed or logged. A matching discovered
service can then connect with the existing `AQS1` handshake. HELLO must match the enrolled
station and full hardware identity; discovery alone cannot enroll a replacement.

The Wi-Fi cards support independent connections, live particle readings and **Sync**. Two
sensors can collect files concurrently on one phone. Each `LanSession` is an actor whose
executor is the session's own serial dispatch queue. Network callbacks, timers and framing run
on that queue, so they enter actor state in delivery order without a task per callback. The
session keeps one receive of up to 64 KiB outstanding and splits `len` u16 frames from an
accumulating buffer, with bounded reply buffering (32 frames) and push demultiplexing. STATUS
and LIVE pushes and the final close reason reach `LanDiscovery` through one ordered
`AsyncStream` per session. A keepalive is measured from outgoing phone traffic, and request,
connect and 180 s idle waits are bounded. LAN sockets prohibit cellular interfaces and use a
10 s TCP connection timeout. Cancellation closes the socket and fails pending waits; old task
callbacks cannot update a newer session. Alias services cannot open two controllers for the
same hardware on this phone.

Frames cross the Kotlin and Swift boundary as `NSData` (Swift `Data`). `iosMain/SyncWireData.kt`
copies with `usePinned` and `memcpy`; no hex strings remain on the transfer path. The LAN
handshake takes the token as `Data` and zeroes the Kotlin copies after building the frame.

File sync on iOS runs the shared engine, the same `SyncEngine` and `ArchiveCollector` from
`:core:sync` that Android runs. Each connection holds one `IosLink` (`ios/shared` iosMain) over `LinkSession`, and each sync,
command or request takes a lease of it.
`LanSession` passes LAN frame bodies through it, and `BluetoothCompanion` passes the `control`
writes and `response` notifications. `SensorSync` owns the `IosArchiveStore` and
`ReceiptArchiveIndex` from `:core:archive` iosMain, runs off the main thread, reports progress at
most four times a second and finishes with one `SyncResult`. A station syncs over one link at a
time. The Swift transfer engine was removed. Window retry, resume, verify and conflict rules are
therefore Android's rules, described in [sync-engine.md](sync-engine.md), which also lists what
still differs between the phones.

Collections mirror immutable files under Application Support as
`SensorArchive/<station>/<relative path>`, the same place earlier builds used. Partials live
under `Application Support/archive-partial/<station>/`, written with `pwrite` and `fsync`.
Partials and receipts left by the old Swift engine are adopted when their size and CRC match.
A final filename appears only after size, CRC-32, `PAR1` head/tail and SHA-256 pass in one pass.
Existing finalized files are verified and preserved, and a conflicting file is reported rather
than replaced. Names use the shared safe-name rule, which is stricter than the old Swift
whitelist. The archive root is created with
`FileProtectionType.completeUntilFirstUserAuthentication`, matching the Keychain token's
`AfterFirstUnlockThisDeviceOnly`, so collection can keep writing after the phone locks. Partials
and receipts inherit or set the same class. Backup exclusion is set when the root is created
(and repaired once for older installs) instead of on every sync.

Bonjour discovery reports a Local Network denial only when `NWBrowser` waits with
`kDNSServiceErr_PolicyDenied`, following [TN3179](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy).
The screen then shows a Settings hint, and the browse continues so that allowing access resumes
discovery. Other waiting reasons keep the neutral message.

User-started LAN collection requests a UIKit background-task lease. Switching apps stops
discovery and BLE/idle LAN connections, but active collections continue while iOS grants time.
Expiration cancels the sockets and retains partial bytes/progress; returning reconnects the
paused sensors and resumes collection after old task/archive ownership is released. Recoverable
LAN failures while backgrounded also queue resume. Explicit Cancel removes that intent, and
queued sensors wait for one of the two collection slots. This lease is bounded execution. Periodic
Auto-sync is separate, see [background-sync.md](background-sync.md#ios-auto-sync). Force-quitting/relaunching
requires reconnecting and starting sync again; downloaded bytes and verification receipts persist.
See Apple's [background execution guidance](https://developer.apple.com/documentation/uikit/extending-your-app-s-background-execution-time).

Finalized files receive an atomic verification receipt with size, CRC and SHA-256. Later
collections count completed files immediately and skip their remote OPEN/READ. An older receipt
without a hash gets one on its next verify. Partial files still require matching fresh OPENED
size/CRC before resume.

Messages follow Android. A sync outcome carries a `:ui:strings` key, and link or Wi-Fi access
events outside a collection reach `CompanionState` as a typed `CompanionNotice`, so iOS shows
Android's words for busy, refused token, station or hardware change and the rest. The iOS LAN
path has no Bluetooth fallback, so it uses `msg_busy` and `msg_token_needs_ble` where Android
uses its `msg_lan_*` wording. A LAN `AUTH_FAILED` drops the saved token and asks the user to save
Wi-Fi access over Bluetooth again. The BLE **Sync** button uses Wi-Fi when an idle LAN session
for the same sensor is open, and progress then shows on its LAN card. A Bluetooth sync that loses
its link closes the Bluetooth connection, and held bytes stay for the next sync. A file this
phone cannot store fails alone, and the sync ends with `companion_sync_storage_failed` and keeps
the link. A protocol fault ends it with `msg_sync_aborted` and also keeps the link. A token
refused or a sensor busy in the middle of a sync ends it as `AUTH_FAILED` or `BUSY`. A Stop
that lands before the work starts still finishes once and frees the station. `SensorSync` logs
only the file name and the error class and message, through `NSLog` without format arguments. The Swift
adapters report Bluetooth and Wi-Fi link states as a `CompanionNotice`, so `:ui:strings` words them in
English and Arabic (#47).

Files and the inspector are the shared `:feature:files` screens, opened offline from a Files
button next to History on My devices (`FilesKey("archive")`). Files lists every verified file
in the phone archive by local day. Share one file, share all, share zip and save zip stage
copies through `:core:archive` into a fresh run under `tmp/opensensor-export`, then open
`UIActivityViewController` or `UIDocumentPickerViewController(forExporting:asCopy:)` from
`feature/files/src/iosMain`. The run is removed once the sheet closes. Only verified records
are exported, so partials and receipts stay private, and the archive is never moved or changed.
The daily zip action remains hidden; automatic UTC reconciliation now persists a derived daily
Parquet cache, but that cache is not yet wired to the iOS export picker. The Swift folder export and its
companion button are gone. Background Bluetooth is limited to Auto-sync (#23). The shared History screen draws archive charts.
Native BLE uses Core Bluetooth's
peripheral identifier; the Keychain identity also pins station and full hardware ID. New
LAN/archive behavior has host/simulator/build evidence plus the owner's report of
saved access and collection starting. Completed-file verification and throughput
after the CoreS3 v6.7 upgrade still need iPhone observations; see [status.md](status.md).

## Build and run

Use an Apple Silicon Mac with Xcode and its command-line tools selected. The app
requires iOS 18.5 or later; the installed iOS 26.5 simulator works. A newer simulator
runtime is unnecessary. Intel simulator support is not included in this stage.

From the repository root:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew --no-daemon verify
./gradlew --no-daemon :ios:shared:linkDebugFrameworkIosSimulatorArm64
xcodebuild -project ios/OpenSensor.xcodeproj -scheme OpenSensor \
  -configuration Debug -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath ios/build/DerivedData CODE_SIGNING_ALLOWED=NO build
./gradlew --no-daemon verifyIos
./ios/tests/verify-native.sh
xcodebuild -project ios/OpenSensor.xcodeproj -scheme OpenSensor \
  -configuration Debug -destination 'platform=iOS Simulator,name=iPhone 17' \
  -derivedDataPath ios/build/DerivedData CODE_SIGNING_ALLOWED=NO test
```

Pick any installed iPhone simulator for the `test` destination (`xcrun simctl list devices available`).

`verify` includes Android lint, the debug APK, `:app` unit tests, the JVM tests of every `:core`
module and the Android host tests of `:ui:designsystem` and `:feature:discover`. It works without
Xcode on Linux. On a Mac it also runs `verifyIos` (skip with `-Popensensor.skipIos=true`).
`verifyIos` runs the iOS simulator tests of every shared module, including the `:ios:shared`
presentation tests, and links the shared framework. It needs an installed simulator runtime. The Xcode build compiles the Swift BLE
adapter and app too, with `SWIFT_STRICT_CONCURRENCY = complete` and no warnings. The native
checks cover LAN claims, framing, Local Network denial detection, policy and outbound keepalive
timing. Resume, verification, conflicts, names and window retry are tested once in `:core:sync`
and `:core:archive` `commonTest`, and the iOS runner and notices in `ios/shared`
(`SensorSyncTest`, `SyncOutcomeTest`, `CompanionNoticeTest`), all inside `verifyIos`. `verify-native.sh` compiles them on the Mac host with complete concurrency checking and
warnings as errors. The hostless `OpenSensorTests` Swift Testing target runs the same checks on an
iOS simulator through the shared scheme's test action, without signing or the Kotlin framework.
Neither a simulator build nor these tests qualifies real radio behavior.

GitHub Actions adds an Apple Silicon macOS job that runs `verify-native.sh`, `verifyIos`, an
unsigned Debug simulator build, the `OpenSensorTests` Swift Testing target on a simulator, and an
unsigned Release device build. Its Xcode pin, downloadable
simulator artifact, failure reports and later TestFlight signing plan are documented
in [build-and-release.md](build-and-release.md#github-actions-ios-validation-researched-2026-10-08).
Keep the existing Xcode build phase as the framework integration point; a separate
framework-link command before `xcodebuild` is unnecessary.

Open `ios/OpenSensor.xcodeproj` in Xcode and choose an installed simulator to run
the screen. For the connected iPhone, select your Apple development team in
Signing & Capabilities, choose the phone, enable Developer Mode if required,
and run. The team identifier and provisioning credentials are intentionally local.
The Gradle script locates Android Studio's JBR if `JAVA_HOME` is not already set.
`Info.plist` includes `CADisableMinimumFrameDurationOnPhone = true`, required by
Compose's startup sanity check on high-refresh-rate iPhones. Keep that setting.
The deployment target matches the current native dependency minimum (iOS 18.5).

For a phone check: allow Bluetooth, tap **Scan** on My devices, connect your nearby
sensor, complete the system PIN prompt if shown, compare readings with the
sensor, then verify disconnect, radio-off and background/resume behavior.
Use **Scan** on My devices to test Bluetooth and Bonjour together, including the Local Network
prompt on the phone. The device page also offers **Find Wi-Fi sensors**. Decline/allow/revoke
checks remain phone
qualification work. An iOS simulator cannot exercise the real BLE transport. Keep dated real-phone
results separate from compilation and common-test evidence. On 2026-10-08 the
owner confirmed Bluetooth connection and visible live readings on the connected
iPhone; a supplied screenshot also confirmed Bonjour discovery of `aq-6b40`.
These observations do not qualify pairing prompts, notification cadence, value
accuracy, stale/background handling or authenticated Wi-Fi connections. See
[status.md](status.md) for the dated evidence and remaining checks.

## Remaining migration

1. Done in code (#18, 2026-10-09), not yet seen on an iPhone. The shared reader, indexer and
   aggregator run over the iOS archive through the receipt index (`IosArchive`), with index repair
   at launch. A Room KMP store would replace the receipts and the in-memory tiers later.
2. Done in code (#18), not yet seen on an iPhone. History is the shared `:feature:history`
   screen, opened from History buttons on My devices and the device page, with Day, Week, Month and Year, the ring,
   the calendar and station chips for every archived station. Check on an iPhone that a fresh sync
   shows in History without a relaunch, that index repair fills an archive synced by an older build,
   and how long Week, Month and Year take on a large archive (tiers are computed in memory).
3. Share existing Android screens, resources and navigation incrementally. My devices is shared
   in code (#16, 2026-10-09), not yet seen on an iPhone. Check on an iPhone that the list scans
   only after access is allowed, that a known sensor opens from the list when it is not
   advertising in the scan, that Forget then opening the sensor again pairs as expected (remove
   the bond in iOS Settings first), and that Collect all copies two loggers one after the other.
4. Qualify BLE token acquisition, Keychain persistence, authenticated LAN transfer, multi-phone
   collection, concurrent sensors, cancellation/resume and Local Network permissions on iPhones.
5. Qualify bounded iOS LAN background collection, automatic foreground resume and Files exports on iPhones.
6. Measure LAN throughput with the off-main session on an iPhone, confirm the Local Network
   denial hint after declining and revoking access, and confirm archive writes continue after
   the phone locks during a background collection.
7. Qualify the shared engine on an iPhone. LAN sync, background resume and a second sync that
   reads nothing. Bluetooth sync, and Bluetooth Sync while a LAN session is open. An upgrade from
   a build that used the Swift engine, keeping its receipts and partials.
8. Drop the unused write path in `SensorArchive.swift`, with its English error descriptions. The
   link status lines moved into `:ui:strings` in #47.
9. Give `ByteReader` (`core/parquet`) a `close`, so `ArchiveStore.reader` callers release
   what they open. No descriptor leaks today. The JVM reader closes its file after mapping, and
   the iOS reader is a mapped `NSData` freed with the object. A reader over a plain file handle
   would need it.
10. Debug (`:feature:debug`) can borrow a short authenticated LAN or Bluetooth link for raw
    commands, `LOG_TAIL`, `GET_CONFIG` and `STATUS`. iOS still needs the Smart Sync trace card.
    Qualify on an iPhone that a raw command, log, config read and Refresh status work over each
    link, that `TOKEN` and secrets stay redacted, that a sync is refused while Debug holds the link
    and the reverse, and that leaving Developer level closes the screen.
11. Done in code (#17, 2026-10-09), not yet seen on an iPhone. Today is the shared `:feature:today`
    screen. An enrolled Bluetooth or authenticated Wi-Fi sensor opened from My devices lands on it.
    An available authenticated LAN session is opened first; Bluetooth is the fallback when it
    cannot be reached and Bluetooth permission is already held.
    `CompanionTodaySource` (`ios/shared` commonMain) reads the matching Bluetooth or LAN INFO,
    STATUS and LIVE state from `CompanionState`; Today so far reads the hourly tier of `IosArchive`.
    Smart Sync, Set time and Finalize use a ready LAN session for that sensor when available, then
    Bluetooth if present. Commands are refused while another sync, Debug or Wi-Fi access request
    holds the link. Smart Sync follows the Clock sync setting, now shown on iOS, and a refused
    clock step stops the sync. The Device action opens the companion page, which keeps Wi-Fi
    access, the Wi-Fi session and Debug. Shared Device settings use authenticated LAN when available;
    the sync bar has no byte total. Qualify on an iPhone
    that Set time anchors a logger that has no UTC, that Finalize writes a short file the next sync
    downloads, that Smart Sync under Automatic sets the clock then syncs, that Ask shows the consent
    dialog, and that opening another sensor leaves the first Today closed.
12. Done in code (#20, 2026-10-09), not yet seen on an iPhone. Device settings are the shared
    `:feature:device` cards and confirmations, opened from a Sensor settings button on the companion
    page of a connected enrolled sensor (`DeviceSettingsKey`). `IosDeviceConfigSource` (`ios/shared`
    iosMain) borrows one short authenticated LAN or Bluetooth link per request, using the same
    command guards and settling the link when it is handed back. The page reads the config when
    its selected link turns ready. After a restart the reopen goes only to the same sensor, or to a free link with no
    Collect all running. Frames go through the
    redacting recorder Debug shows. Get Wi-Fi token and Replace token reuse Save Wi-Fi access, so the
    token stays in Swift and the Keychain. `GET_TOKEN` and token rotation require a live bonded
    Bluetooth link; rotation is gated until it can obtain and save the replacement token. Differences
    from Android: no phone installation history, device header or Auto-sync card on the page, and
    no Wi-Fi endpoint line. Qualify on an iPhone that each of the four confirmations sends nothing on
    Cancel, that a Wi-Fi join is polled until connected, that Replace token stores a new token and the
    next Wi-Fi sync uses it, that Forget all paired phones asks for pairing on the next connection,
    that Restart reconnects after about 6 s, and that a sync or Collect all waiting for the link starts
    once a request ends.
13. Done in code (#21, 2026-10-09), not yet seen on an iPhone. The sensor sticker card scans with
    VisionKit `DataScannerViewController` (`ios/App/StickerScanner.swift`), which reads the first
    barcode of any symbology, as Android's scanner does. Swift passes `scanSticker` to
    `mainViewController` only where `DataScannerViewController.isSupported` is true (A12 and later),
    so Scan is hidden elsewhere. The Scan tap runs `UserAction.SCAN_STICKER` through `:ui:permissions`,
    which shows the system camera prompt with no rationale of its own, as for Bluetooth, Wi-Fi and
    location (`StickerScan.kt`, AVFoundation status),
    and only then presents the scanner, which checks `isAvailable` (false until the grant or under a
    Screen Time restriction). Only the decoded string reaches Kotlin, through `StickerScans.read`, the
    same `SensorLabel` parser and model check as typed text. Cancel leaves the card as it was. No frame
    is stored, logged or uploaded. `NSCameraUsageDescription` is in `Info.plist` and
    `InfoPlist.xcstrings`. The privacy manifest needs no change (store-compliance.md). Parity P58,
    P61 to P63. Qualify on an iPhone that launch shows no camera prompt, that the first Scan tap shows
    the system prompt directly, that a refusal leaves the note and manual entry, that a
    later tap offers Settings, that a real PMS5003T sticker fills the field and saves, that Cancel keeps
    the field, and that the scanner stops the camera when it closes.

LAN and Bluetooth file sync use the shared Kotlin engine. Swift keeps the sockets, Core
Bluetooth, LAN framing, the HELLO check, keepalive, the BLE token request and Keychain.
The shared Debug and Device settings screens take a lease of the connection's `IosLink`, over
authenticated LAN when available or Bluetooth, one per request. Ending the lease hands the link
back to Swift, which keeps it open while the connection lives. Token acquisition and replacement still need bonded Bluetooth.
`SensorArchive.swift` remains only for Files export. Android's Room index, WorkManager and
foreground service stay Android code.

Compilation, automated tests and device observations for this change are recorded
in [status.md](status.md). A simulator build does not establish real iPhone LAN or BLE behavior.

## KMP and Apple contributor guidance

- Keep platform entry points separate. `:core:protocol`, `:core:parquet` and `:core:history`
  share rules through `commonMain`. Their JVM and iOS adapters (file mapping, gzip, streams)
  remain platform-specific. Android services stay
  in `:app`; Apple services stay in `ios/App/` or the existing iOS source sets.
- Prefer small shared interfaces instantiated by the app entry point for substantial
  platform behavior. Keep the generated Kotlin framework API narrow and documented
  for Swift callers. Current Xcode integration uses direct Gradle framework
  integration and Objective-C interop; evaluate alternatives against current docs
  and this repository's pinned versions before changing it.
- Kotlin source-set and architecture references: [source sets](https://kotlinlang.org/docs/multiplatform/multiplatform-discover-project.html),
  [project configuration](https://kotlinlang.org/docs/multiplatform/multiplatform-project-configuration.html),
  [recommended structure](https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html),
  [Objective-C interop](https://kotlinlang.org/docs/native-objc-interop.html). Check them
  when changing the boundary; current documentation and tool versions can move.
- The linked [SwiftUI Agent Skill](https://github.com/twostraws/swiftui-agent-skill)
  highlights real review concerns such as state flow, navigation, accessibility,
  API availability and rendering performance. This project currently uses Compose
  Multiplatform for its iOS screen, so apply SwiftUI-specific checks only if native
  SwiftUI code is added. Do not adopt the skill's assumed iOS/Swift versions without
  checking this project's iOS 18.5 deployment target and installed Xcode. Apple
  references: [SwiftUI model data](https://developer.apple.com/documentation/swiftui/model-data),
  [accessible descriptions](https://developer.apple.com/documentation/swiftui/accessible-descriptions),
  and [NavigationStack](https://developer.apple.com/documentation/swiftui/navigationstack).
- Bonjour privacy is behavior-driven: plist declarations do not themselves show the
  prompt; a real browse does. Keep waiting/error states accurate and distinguish
  permission denial from unavailable network conditions. See [Apple's Local Network
  privacy note](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy).
- Keep host/common test results, simulator builds, and dated iPhone behavior separate.
  Simulator success cannot qualify BLE pairing, Local Network prompts, radio behavior,
  background execution, or real transfer reliability.
