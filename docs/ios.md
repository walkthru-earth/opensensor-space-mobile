# iOS app

**Scope:** what the iOS app runs, where each part lives, and how to build and check it.
**Code:** `core/*/src/commonMain`, `ui/`, `feature/`, `ios/shared`, `ios/App`, `ios/OpenSensor.xcodeproj`.
**Related:** [cross-platform-guide.md](cross-platform-guide.md), [architecture.md](architecture.md),
[transports.md](transports.md), [sync-engine.md](sync-engine.md), [background-sync.md](background-sync.md),
[build-and-release.md](build-and-release.md), [status.md](status.md), [`../ios/AGENTS.md`](../ios/AGENTS.md).

## Shape

Every `:core` module compiles for the JVM, `iosArm64` and `iosSimulatorArm64` from `commonMain`, with
platform adapters in `jvmMain` and `iosMain`. On iOS, `core:parquet` reads with positional `pread`
and inflates GZIP with the system zlib, and H3 is the vendored C library through cinterop.

The UI is Compose Multiplatform. `ios/shared` (iOS only) holds `AppRoot.kt`, the bridges and the
iOS sync runner, and builds the static `OpenSensorShared` framework that Xcode links directly. The
screens are the same shared feature modules Android draws: Discover, My devices, Today, History,
Files, Device settings, Debug and Settings. `AppRoot` has one bottom bar with Discover, My devices
and Settings, and always starts on Discover. Swift in `ios/App` keeps Core Bluetooth, Network
framework sockets, the Keychain, background tasks and system sheets.

## Bluetooth

`BluetoothCompanion` keeps UI state on the main actor. `BlePhysicalLink` runs Core Bluetooth on one
serial queue, so discovery, GATT operations, notifications and write completions keep their order.
The central manager is created on the first My devices Scan tap, or at launch when iOS restores
Bluetooth state or an Auto-sync switch is on and access is already held, so launch shows no
prompt. Core Bluetooth owns pairing prompts. A refused bond ends the connection without a retry
and asks the user to pair again from iOS Settings. A known sensor connects through its saved
identifier even when it is not advertising.

## Wi-Fi

`LanDiscovery` browses `_ossync._tcp` in `local.` with `NWBrowser`. Scan on My devices starts a
bounded browse with the Bluetooth search, and the real browse is what shows the Local Network
prompt. A denial is reported only for `kDNSServiceErr_PolicyDenied`
([TN3179](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy)),
and the browse keeps going so allowing access resumes it.

A phone first connects over bonded Bluetooth and taps Save Wi-Fi access. `GET_TOKEN` goes over the
encrypted BLE characteristics, and the token is saved in the Keychain with
`AfterFirstUnlockThisDeviceOnly`. It never enters Kotlin, the UI or a log. The `OSS1` HELLO must
match the enrolled thing and full hardware ID, so discovery alone cannot enroll a replacement.
A LAN `AUTH_FAILED` drops the saved token. Each `LanSession` is an actor on its own serial queue,
keeps one receive of up to 64 KiB outstanding, splits `len` u16 frames and forbids cellular
interfaces. Frames cross into Kotlin as `NSData` (`SyncWireData.kt`).

## Sync and archive

File sync runs the shared engine from `:core:sync`. Each connection holds one `IosLink` over
`LinkSession`, and each sync, command or request takes a lease of it. `SensorCollections` holds one
`CollectionController` per device over `IosLinkProvider`. Sync, one-file sync, Collect all steps
and Auto-sync steps all call `runs.collect`, and a retry with no open link asks Swift to dial
again. `SensorSync` reports progress at most four times a second and finishes with one result.

The archive is `Application Support/SensorArchive/<thing>/<Hive path>`, with partials under
`archive-partial/<thing>/`, through `IosArchiveStore` in `:core:archive`. The index and pruned marks
live in the shared Room database `Application Support/database/opensensor.db` (`IosArchiveDatabase` in
`:core:database`), which uses the same protection class and is excluded from backup. A final name
appears only after size, CRC-32 and `PAR1` pass, and its record also holds SHA-256. On its first open
the database imports the `.verified.json` receipts and `pruned.v1` marks an older build left. Only a
receipt whose file passes size, CRC-32 and `PAR1` becomes a record, and a receipt that fails or
throws is skipped. The rows are checkpointed into the file before the `receipts-imported` marker is
written. The next launch deletes the old files without touching Room, then writes `receipts-settled`,
after which the import does nothing. The root uses
`completeUntilFirstUserAuthentication`, so collection keeps writing after the phone locks, and is excluded from backup. `IosArchive` builds History tiers through the shared
indexer and `DayPass`, persists them with `IosFileTierStore` and repairs the index at launch.
`SensorArchive.swift` holds only the thing ID check Swift applies before saving a credential.

Sync messages use `:ui:strings`. Link and Wi-Fi access events outside a run reach `CompanionState`
as a typed `CompanionNotice`, so iOS shows Android's words in English and Arabic.

## Off screen

| Work | How it continues |
| --- | --- |
| A run the user started | A UIKit background task. When iOS ends it, the run stops as interrupted, keeps its bytes, and starts again over the same transport when the app is back |
| Collect all | One `BGContinuedProcessingTask` per pass on iOS 26 and later, with system progress and Cancel. Older iOS ends the pass when the app leaves |
| Auto-sync | `BGProcessingTask` and a pending Bluetooth connect, see [background-sync.md](background-sync.md#ios-auto-sync) |
| Idle links | Discovery and idle links close when the app leaves. An open page reconnects on return |

A force quit stops everything. Downloaded bytes and index records stay for the next sync.

## Screens over iOS services

- **My devices.** `DevicesBridge` feeds the shared list from `CompanionState`, known records in
  UserDefaults (never a credential), the Keychain credential list (ids only) and the Room
  index.
- **Today.** `CompanionTodaySource` reads INFO, STATUS and LIVE for the open link. Forget, in
  the Today menu after confirmation, drops the record, the Keychain access and Auto-sync
  (`DevicesBridge.forgetNow`), and says the bond is removed in iOS Settings. Today so far
  reads the hourly tier. Sync and Finalize use a ready LAN session first, then Bluetooth. The
  engine sets the device clock automatically from phone UTC.
- **Device settings.** `IosDeviceConfigSource` borrows a short LAN or Bluetooth link per request.
  Get Wi-Fi token and Replace token reuse Save Wi-Fi access, so the token stays in Swift.
  `GET_TOKEN` and rotation need a live bonded Bluetooth link.
- **Files.** Share one, share all, share zip and save zip stage copies under
  `tmp/opensensor-export/<run>` and open `UIActivityViewController` or the document picker. Only
  verified files are exported and the run is removed when the sheet closes. Runs coexist, and a
  run the screen could not remove ages out after one hour (`ExportRuns`).
- **Debug.** Borrows a short link for raw commands, `LOG_TAIL`, `GET_CONFIG` and `STATUS`, with the
  shared denylist and redaction.
- **Sticker scan.** VisionKit `DataScannerViewController` (`StickerScanner.swift`), shown only where
  `isSupported`. The camera is asked on the Scan tap, and only the decoded string reaches Kotlin.

## Build and run

Use an Apple Silicon Mac with Xcode. The app needs iOS 18.5 or later.

```sh
./gradlew --no-daemon verify            # includes verifyIos on a Mac
xcodebuild -project ios/OpenSensor.xcodeproj -scheme OpenSensor \
  -configuration Debug -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath ios/build/DerivedDataSim build
sh ios/tests/verify-native.sh
```

`verifyIos` runs every shared module's iOS simulator tests, including `ios/shared`, and links the
framework. Skip it with `-Popensensor.skipIos=true`. The Xcode build compiles Swift 6 with complete
concurrency checking and no warnings. `verify-native.sh` runs the Swift checks for LAN claims,
framing, keepalive, policy, cancellation and the GATT queue on the Mac host, and the hostless
`OpenSensorTests` target runs them on a simulator. CI details are in
[build-and-release.md](build-and-release.md#github-actions-ios-validation-researched-2026-10-08).

To run on an iPhone, open `ios/OpenSensor.xcodeproj`, pick your team in Signing and Capabilities and
choose the phone. Team and provisioning stay local and are never committed. Keep
`CADisableMinimumFrameDurationOnPhone = true` in `Info.plist`, Compose needs it on high refresh
iPhones.

A simulator cannot exercise Bluetooth, Local Network prompts, background time or real transfer
reliability. Record iPhone results with dates in [status.md](status.md).

## KMP and Apple guidance

- Android services stay in `:app`, Apple services in `ios/App` or the iOS source sets.
- Keep the Kotlin framework API small and documented for Swift. Check
  [source sets](https://kotlinlang.org/docs/multiplatform/multiplatform-discover-project.html) and
  [Objective-C interop](https://kotlinlang.org/docs/native-objc-interop.html) before changing the boundary.
- The iOS screens are Compose. Apply the
  [SwiftUI Agent Skill](https://github.com/twostraws/swiftui-agent-skill) only to native SwiftUI code,
  and check its version assumptions against the iOS 18.5 target.
- Plist keys do not show the Local Network prompt, a real browse does. Keep waiting states apart
  from a denial.
