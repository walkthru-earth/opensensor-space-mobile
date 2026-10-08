# iOS migration — first live-reading app

**Scope:** Kotlin Multiplatform source sets, the Compose iOS shell, Core Bluetooth,
macOS builds and remaining feature migration.
**Code:** `core/{protocol,history}/src/commonMain`, `ios/shared`, `ios/App`,
`ios/OpenSensor.xcodeproj`.
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

`core:protocol` and `core:history` retain a Java 17 JVM target for Android and host
consumers, with `iosArm64` and `iosSimulatorArm64` added. Portable source lives
in `commonMain`; the old `src/main/kotlin` and `src/test/kotlin` directories remain
mapped to `jvmMain` and `jvmTest` for platform adapters and existing tests.
`core:parquet` stays JVM-only. The `test` compatibility tasks still run `jvmTest`.

The iOS app uses Compose Multiplatform 1.12.1 (Material 3 1.9.0), the existing
Kotlin 2.4.20 and Xcode direct framework integration. `ios:shared` also has a JVM
target for presentation-state tests; the Android app continues to use its
existing screens. Shared Android/iOS UI extraction is a later stage.

The iOS live screen can discover AQ Sync service advertisements, connect one
sensor, read INFO/STATUS/LIVE and subscribe to live/status updates. Missing,
invalid, disconnected and expired readings remain unavailable. Ambiguous
board temperature and humidity are shown only for reviewed schema/dictionary
profiles. Core Bluetooth owns pairing prompts. Operations are serialized and
scan/connection timers bound attempts. The app sends no firmware-changing
commands and has no accounts, analytics, cloud connection or location permission.

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

Collections mirror immutable files under Application Support as
`SensorArchive/<station>/<relative path>`. Interrupted downloads retain partial bytes keyed
by size/CRC and resume from the phone's own received offset. READ handles, offsets and window
lengths are checked. As the contract requires, a window whose READ_END disagrees with the
phone's own count, or that skipped a chunk, is retried with a new READ from the phone's offset.
Chunks after a gap are drained until READ_END first. Overlapping or duplicate bytes already held
are skipped, and `next_offset` never overrides the local count. Retries mirror Android's
`SyncEngine` (5 retries, 0.3 s linear backoff). A consistent short READ_END continues from the
reported end, a READ_END error status ends the run, and held bytes are kept for resume in
every case. A final filename appears only after size, streamed IEEE
CRC-32 and `PAR1` head/tail verification. Existing finalized files are verified and preserved;
a conflicting file fails the collection rather than replacing data. Paths use the firmware's
safe-name whitelist and 399-byte limit, reject traversal/symlinks, and keep station directories
independent. Collection runs off the main actor. Archive writes hop to the `ArchiveIO` actor
once per READ window (append plus fsync), and progress reaches the main actor at most four
times per second. The archive root is created with
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

Finalized files receive an atomic size/CRC verification receipt. Later collections validate
the local bytes against it, count completed files immediately and skip their remote OPEN/READ.
Legacy files without receipts use OPEN/local verification once to create the receipt. Conflicts
preserve existing files. Partial files still require matching fresh OPENED size/CRC before resume.
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

`verify` includes Android lint/APK/unit tests, existing JVM core tests, shared core
tests and the iOS presentation state's JVM tests. It works without Xcode on Linux.
`verifyIos` runs common tests on the simulator and links the shared framework;
it needs an installed simulator runtime. The Xcode build compiles the Swift BLE
adapter and app too, with `SWIFT_STRICT_CONCURRENCY = complete` and no warnings. The native
checks exercise the production archive and transfer engine with scripted replies: resume, CRC
verification, immutable conflicts, the 399-byte name limit, path/symlink rejection, foreign
handles, window retry (short READ_END, gap, overlap, duplicate, exhaustion, error status), LAN
framing, Local Network denial detection, independent concurrent stations and outbound keepalive
timing. `verify-native.sh` compiles them on the Mac host with complete concurrency checking and
warnings as errors. The hostless `OpenSensorTests` Swift Testing target runs the same checks on an
iOS simulator through the shared scheme's test action, without signing or the Kotlin framework.
Neither a simulator build nor these tests qualifies real radio behavior.

GitHub Actions adds an Apple Silicon macOS job for `verifyIos`, an unsigned Debug
simulator build, and an unsigned Release device build. Its Xcode pin, downloadable
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

1. Port Parquet decoding/compression, history indexing and Android archive fixtures for charts.
2. Port history indexes, offline charts and retained station-generation UI.
3. Share existing Android screens, resources and navigation incrementally.
4. Qualify BLE token acquisition, Keychain persistence, authenticated LAN transfer, multi-phone
   collection, concurrent sensors, cancellation/resume and Local Network permissions on iPhones.
5. Qualify bounded iOS LAN background collection, automatic foreground resume and Files exports on iPhones.
6. Measure LAN throughput with the off-main session on an iPhone, confirm the Local Network
   denial hint after declining and revoking access, and confirm archive writes continue after
   the phone locks during a background collection.

LAN byte transfer and integrity verification use a native Swift platform engine with the shared
Kotlin wire codec. Android's existing session/archive engine still depends on JVM file APIs,
Room and Android lifetime services; it is not imported into the iOS app.

Compilation, automated tests and device observations for this change are recorded
in [status.md](status.md); features listed above are not implemented on iOS yet.

## KMP and Apple contributor guidance

- Keep platform entry points separate. `:core:protocol` and the sensor-domain part
  of `:core:history` share rules through `commonMain`; their JVM adapters and the
  JVM-only Parquet implementation remain platform-specific. Android services stay
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
