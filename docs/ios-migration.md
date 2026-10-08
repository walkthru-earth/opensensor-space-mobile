# iOS migration — first live-reading app

**Scope:** Kotlin Multiplatform source sets, the Compose iOS shell, Core Bluetooth,
macOS builds and remaining feature migration.
**Code:** `core/{protocol,history}/src/commonMain`, `ios/shared`, `ios/App`,
`ios/OpenSensor.xcodeproj`.
**Related:** [architecture.md](architecture.md), [transports.md](transports.md),
[build-and-release.md](build-and-release.md), [status.md](status.md).

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
Discovered service names are shown without opening TCP sockets or obtaining tokens.
Bluetooth and local discovery states are independent; declining local access does
not disable the BLE path. A later transfer stage must authenticate through bonded BLE.
See [Apple's local network privacy guidance](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy).

This stage is foreground-only: entering the background stops scanning and closes
the connection. It does not declare background BLE capability or promise Android
WorkManager/foreground-service parity. No token or Wi-Fi secret is requested,
stored or logged. Native BLE uses Core Bluetooth's peripheral identifier, not an
Android MAC address; station/hardware continuity must be ported before archives.

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
```

`verify` includes Android lint/APK/unit tests, existing JVM core tests, shared core
tests and the iOS presentation state's JVM tests. It works without Xcode on Linux.
`verifyIos` runs common tests on the simulator and links the shared framework;
it needs an installed simulator runtime. The Xcode build compiles the Swift BLE
adapter and app too. Neither a simulator build nor shared tests qualify radio behavior.

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

1. Port Parquet byte/file access, compression, CRC/SHA verification and fixtures.
2. Extract sync/session logic behind platform transport and archive interfaces;
   preserve verified immutable commits, resume policy and identity/epoch rules.
3. Port Room storage, offline history/exports and retained station generations.
4. Share existing Android screens, resources and navigation incrementally.
5. Add authenticated LAN transfer and encrypted token storage; only use discovered
   devices and tokens acquired through authenticated BLE.
6. Design and qualify iOS background collection under Apple's execution rules.

Compilation, automated tests and device observations for this change are recorded
in [status.md](status.md); features listed above are not implemented on iOS yet.
