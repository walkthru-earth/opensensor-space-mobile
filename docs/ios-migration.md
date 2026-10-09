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

Wi-Fi discovery is also available through `NWBrowser`, restricted to
`_aqsync._tcp` in `local.`. Tapping **Find Wi-Fi sensors** starts a bounded browse
and lets iOS request Local Network access. `Info.plist` declares
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
`:core:sync` that Android runs. Swift lends each sync a `SensorLink` (`ios/shared` iosMain).
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
queued sensors wait for one of the two collection slots. This is bounded execution, not an
indefinite background service or periodic automatic collection. Force-quitting/relaunching
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
only the file name and the error class and message, through `NSLog` without format arguments. Some iOS-only
status lines (connecting, listing, paused, export) are still plain English in Swift.

The offline **Export Parquet files** button copies only finalized `.parquet` files into a temporary
station/Hive snapshot and opens Apple's Files export picker. Partials and receipts remain private;
the snapshot is removed after export/cancel. Files are retained in Application Support; exporting
does not move the archive. The app has no background BLE capability or archive charts.
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

For a phone check: allow Bluetooth, tap **Find sensors**, connect your nearby
sensor, complete the system PIN prompt if shown, compare readings with the
sensor, then verify disconnect, radio-off and background/resume behavior.
Tap **Find Wi-Fi sensors** separately to test the Local Network prompt and
Bonjour discovery on the phone. Decline/allow/revoke checks remain phone
qualification work. An iOS simulator cannot exercise the real BLE transport. Keep dated real-phone
results separate from compilation and common-test evidence. On 2026-10-08 the
owner confirmed Bluetooth connection and visible live readings on the connected
iPhone; a supplied screenshot also confirmed Bonjour discovery of `aq-6b40`.
These observations do not qualify pairing prompts, notification cadence, value
accuracy, stale/background handling or authenticated Wi-Fi connections. See
[status.md](status.md) for the dated evidence and remaining checks.

## Remaining migration

1. Wire the shared Parquet reader, `ParquetIndexer` and `Aggregator` (common since W8) into the iOS
   archive, with an iOS store for the index in place of Room.
2. Port offline charts and retained station-generation UI.
3. Share existing Android screens, resources and navigation incrementally.
4. Qualify BLE token acquisition, Keychain persistence, authenticated LAN transfer, multi-phone
   collection, concurrent sensors, cancellation/resume and Local Network permissions on iPhones.
5. Qualify bounded iOS LAN background collection, automatic foreground resume and Files exports on iPhones.
6. Measure LAN throughput with the off-main session on an iPhone, confirm the Local Network
   denial hint after declining and revoking access, and confirm archive writes continue after
   the phone locks during a background collection.
7. Qualify the shared engine on an iPhone. LAN sync, background resume and a second sync that
   reads nothing. Bluetooth sync, and Bluetooth Sync while a LAN session is open. An upgrade from
   a build that used the Swift engine, keeping its receipts and partials.
8. Move the remaining plain English Swift status lines into `:ui:strings`, and drop the unused
   write path in `SensorArchive.swift`.
9. Give `ByteReader` (`core/parquet`) a `close`, so `ArchiveStore.reader` callers release
   what they open. No descriptor leaks today. The JVM reader closes its file after mapping, and
   the iOS reader is a mapped `NSData` freed with the object. A reader over a plain file handle
   would need it.
10. Debug (`:feature:debug`) runs on iOS over a lent Bluetooth link only. Raw commands, `LOG_TAIL`,
    `GET_CONFIG` and `STATUS` need a Wi-Fi variant of `openDebugLink` in `LanDiscovery.swift`
    (async `target()` and `beginCollect`), and iOS needs the Smart Sync trace card. Qualify on an
    iPhone that a raw command, the log, the config read and Refresh status work over Bluetooth, that
    `TOKEN` and secrets stay redacted, that a sync is refused while Debug holds the link and the
    reverse, and that leaving Developer level closes the screen.

LAN and Bluetooth file sync use the shared Kotlin engine. Swift keeps the sockets, Core
Bluetooth, LAN framing, the HELLO check, keepalive, the BLE token request and Keychain.
The shared Debug screen borrows the same `SensorLink` from `BluetoothCompanion.openDebugLink`,
one per request. Closing the link (`SensorLink.whenClosed`) hands it back to Swift.
`SensorArchive.swift` remains only for Files export. Android's Room index, WorkManager and
foreground service stay Android code.

Compilation, automated tests and device observations for this change are recorded
in [status.md](status.md); features listed above are not implemented on iOS yet.

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
