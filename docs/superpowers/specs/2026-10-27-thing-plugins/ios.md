# iOS adapters

Part of [Thing plugins](README.md). Proposal, 2026-10-27. Apple documentation pages render with JavaScript and could not be fetched by the investigation, so the Apple facts below are to be checked against current docs before relying on them.

## 1. Today

Swift reaches Kotlin in two ways only.
- One Kotlin interface implemented in Swift, `IosLinkPort` (`ios/shared/src/iosMain/kotlin/space/opensensor/IosLink.kt:65-83`), implemented by `BlePort` and `LanSession`.
- Closures passed at launch. `SensorSync.collections(pick, dial, protect, unprotect)` from `AppServices` (`ios/App/AppDelegate.swift`), and about 20 closures into `mainViewController(...)` (`MainViewController.kt:30-51`).
- Kotlin already calls CoreLocation directly from iosMain (`feature/device/src/iosMain/.../DeviceLocation.ios.kt`) and turns the fix into an H3 cell inside the delegate. That is the template for phone samplers.

Board assumptions. `IosLink.kind` is Wi-Fi or Bluetooth. Link identity is a firmware boot. `DialResult` knows only Bluetooth failures. `KnownRecord` is keyed by peripheral. `SensorSync` only pulls archive files.

## 2. Two paths

- Remote Things keep `LinkProvider`, `IosLinkPort` and Swift owning the radio. A new board is data plus at most a new Swift port.
- Local Things implement `SamplerPort` from [core.md](core.md). `IosLinkPort` and `SensorCollections` stay untouched.

## 3. Where each plugin lives

| Plugin | Where | Why |
| --- | --- | --- |
| CMAltimeter pressure | Kotlin iosMain (`platform.CoreMotion`) | plain ObjC API. Private serial `NSOperationQueue`, copy the value out as a Double |
| CLLocationManager to cell | Kotlin iosMain | already done this way. A background session may need a thin Swift shim for `CLBackgroundActivitySession` |
| AVAudioSession metering | Swift adapter implementing `SamplerPort` | category, interruptions and route changes are safer in Swift 6. Only dBFS crosses |
| HealthKit reader | Swift | entitlements and Swift-first APIs. Returns aggregates to a private view only |
| Core Bluetooth board | Swift, unchanged | `BlePhysicalLink` actor on its serial queue |
| Live Activity | Swift widget extension | ActivityKit is Swift only. Kotlin publishes session state, Swift mirrors it |

## 4. Registration and threading

- Swift calls `ThingKinds` registration once in `AppServices.launch`, before the background task registers. Kotlin samplers come from an iosMain `installPhoneSamplers()`.
- One interface per plugin, never one closure per action. The `mainViewController` closure list does not grow per plugin.
- Samplers deliver on their own serial queue. Kotlin moves samples to `Dispatchers.Default` for writing. UI state leaves through `StateFlow`. Permission UI runs on `@MainActor`.
- Only plain values cross the boundary, since Kotlin framework objects are not Sendable.
- Stop, interruption and memory pressure (`IosMemoryPressure.kt`) flush and close the session file.

## 5. Apple constraints

- `UIBackgroundModes` has `audio`, `location`, `processing` and `bluetooth-central`, and no mode for steady sampling of other sensors. The barometer keeps running in the background only while an audio or location session keeps the app alive.
- Guideline 2.5.4 rejects background modes without a visible matching feature. Using `audio` only to keep the barometer alive will be rejected.
- A res 8 cell needs full accuracy, through `requestTemporaryFullAccuracyAuthorization` and `NSLocationTemporaryUsageDescriptionDictionary` (owner question 5).
- Usage strings per plugin. `NSMotionUsageDescription` (barometer), `NSMicrophoneUsageDescription`, `NSHealthShareUsageDescription`. Location keeps `NSLocationWhenInUseUsageDescription`.
- HealthKit needs `com.apple.developer.healthkit`, and `healthkit.background-delivery` only if needed. Guidelines 2.5.1 and 5.1.3 apply ([guidelines](https://developer.apple.com/app-store/review/guidelines/#health-and-health-research)).
- Live Activities need `NSSupportsLiveActivities` and a widget extension, with a limit of about 8 hours.
- CoreMotion, AVAudio and HealthKit are not required reason APIs. Uploading phone data to the public feed makes it collected, as `docs/store-compliance.md` already records for boards. So `NSPrivacyCollectedDataTypes` and the App Store label change in the release that uploads phone data.

## 6. Keeping one plist and entitlement set honest

- There is no entitlements file today. HealthKit would add the first one.
- Each plugin declares its usage keys, background modes, entitlements and privacy manifest entries in a small table. A gate test checks `Info.plist`, `PrivacyInfo.xcprivacy` and entitlements against the enabled plugins, mirroring Android's `ManifestPermissionsTest`.
- Each mode or entitlement lands in the same release as its feature, with an updated review note. HealthKit can sit behind a build flag so it stays out of the binary until decided.

## 7. First step

1. `SamplerPort`, `SampleSink` and registration in common code.
2. CMAltimeter pressure in Kotlin iosMain, foreground only, inside an explicit session, adding only `NSMotionUsageDescription`. No background mode, no publishing.
3. The plist and privacy manifest gate test.
4. Dated iPhone run in `docs/status.md`. A simulator has no barometer.
