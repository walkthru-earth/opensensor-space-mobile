# iOS app and Apple platform adapters

The iOS app entry point is the Xcode project in this directory. Read the root
`AGENTS.md`, then the relevant section of `../docs/README.md` and
`../docs/ios-migration.md` before changing code.

## Ownership and boundaries

- `shared/` builds the Kotlin framework used by the app. Keep cross-platform
  protocol and sensor-domain rules in `core/*/src/commonMain`; Android/JVM
  adapters do not belong here.
- `App/` owns Apple platform integration such as Core Bluetooth, Bonjour/Network,
  application lifecycle, permissions, and Xcode configuration. Do not move OS
  behavior into common Kotlin or UI composables.
- The current live screen is Compose Multiplatform in
  `shared/src/commonMain`. Swift files under `App/` are native adapters. Do not
  assume the UI is SwiftUI or duplicate a screen in SwiftUI unless a task explicitly
  calls for that architecture change.
- Preserve the generated framework name, Xcode build phase, bundle identity,
  deployment target, privacy strings, and Gradle/Xcode integration unless the task
  requires a reviewed change. Check generated framework API changes from Swift.
- For CI or release work, read `../docs/build-and-release.md`. Keep the unsigned
  simulator/device validation job independent of signing secrets. Check the pinned
  Kotlin/Xcode compatibility and hosted image inventory together; do not inherit
  a moving Xcode default or add release tooling before distribution requires it.
- Core Bluetooth and Bonjour behavior needs iOS simulator/build checks plus real
  iPhone evidence where radio or privacy behavior is involved. Simulator success
  does not establish BLE, Local Network permission, background, or transfer behavior.

## Swift and SwiftUI work

- Apply SwiftUI guidance only to native SwiftUI views. When working there, check
  state ownership and data flow, value-based navigation when restoration matters,
  descriptive accessibility labels, Dynamic Type, Reduce Motion, API availability,
  and work performed from view bodies. Prefer SwiftUI-native APIs; keep UIKit at
  explicit integration boundaries.
- The [SwiftUI Agent Skill](https://github.com/twostraws/swiftui-agent-skill) is
  useful review material, not a dependency. Its guidance can assume newer iOS and
  Swift versions than this app. Check APIs against this project's deployment target
  (currently iOS 18.5) and installed Xcode before using them.
- For Bonjour, follow Apple's [Local Network privacy guidance](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy).
  Keep `NSLocalNetworkUsageDescription` and the exact `NSBonjourServices` entries in
  sync with actual browse types. Treat `NWBrowser` waiting as ambiguous unless the
  error identifies a policy denial. A real browse is what triggers the privacy prompt.
- For Core Bluetooth, wait for the central manager to become powered on before
  starting operations, serialize connection/session work, and keep PINs, tokens,
  and Wi-Fi credentials out of logs and screenshots.

## Concurrency model

- The app target builds in Swift 5 mode with `SWIFT_STRICT_CONCURRENCY = complete` and must stay
  free of concurrency warnings. The test target and `tests/verify-native.sh` use the same level,
  and the host harness treats warnings as errors.
- UI-facing adapters (`BluetoothCompanion`, `LanDiscovery`, scene delegates) are `@MainActor`.
  Core Bluetooth, `NWBrowser` and `Timer` callbacks are delivered on the main queue and enter
  with `MainActor.assumeIsolated`. Do not wrap each callback in `Task { @MainActor in }`, which
  gives up delivery order.
- `LanSession` is an actor whose executor is its own serial queue. `NWConnection` callbacks and
  `DispatchSourceTimer` handlers run on that queue and enter with `assumeIsolated`. Owners learn
  about pushes and closure through the ordered `events` stream. `close()` is nonisolated.
- File sync runs in the shared Kotlin engine (`SensorSync`). Swift only moves bytes through a
  `SensorLink` and receives progress and the typed result on the main thread. Values that cross
  domains (`LanReply`, `LanFailure`, `LanCredential`) are Sendable Swift values. Kotlin framework
  objects are not Sendable. Keep each one inside a single isolation domain, and wrap one in an
  `@unchecked Sendable` box only to hand it across a continuation.

### Remaining for the Swift 6 language mode

Checked against the [Swift 6 migration guide](https://www.swift.org/migration/documentation/migrationguide/)
and Xcode 27.0. Complete checking already reports nothing, so the switch itself is
`SWIFT_VERSION = 6` for both targets plus `-swift-version 6` in `verify-native.sh`. Before that,
decide these explicitly.

- Swift 6 turns the dynamic isolation checks behind `assumeIsolated` into hard runtime traps.
  Keep every delegate, browser and timer on the queue that matches its isolation.
- `BluetoothCompanion` uses `@preconcurrency` delegate conformances. Swift 6 keeps them but
  checks isolation at run time. Inferred isolated conformances (SE-0470) could replace them.
- Approachable Concurrency (`SWIFT_APPROACHABLE_CONCURRENCY`) enables
  `NonisolatedNonsendingByDefault` (SE-0461). Nonisolated async functions would then run on the
  caller's actor. Review the `@Sendable` closure parameters before enabling it.
- Default MainActor isolation (`SWIFT_DEFAULT_ACTOR_ISOLATION = MainActor`, SE-0466) would move
  `SensorArchive`, `LanFrameBuffer` and `LanPolicy` onto the main actor. Do not adopt it without
  marking that code `nonisolated`, or socket framing returns to the UI thread.
- The Keychain record JSON-encodes `LanCredential`, which creates a base64 String copy of the
  token at save and load. Storing the token as a separate raw item needs a migration.

## Tests

`OpenSensorTests` is a hostless Swift Testing bundle. It compiles `App/SensorArchive.swift`,
`App/LanFraming.swift` and `App/LanPolicy.swift` with `tests/ArchiveChecks.swift` and
`tests/TransferChecks.swift`, so it needs neither the app host nor the Kotlin framework. Run it
with `xcodebuild ... -destination 'platform=iOS Simulator,name=<installed iPhone>'
CODE_SIGNING_ALLOWED=NO test`. `tests/verify-native.sh` runs the same checks on the Mac host.
Add new socket or archive cases to the shared check files so both runners exercise them. File
sync cases belong in `shared/src/iosTest` (`SensorSyncTest`) and `core/sync` tests.
Code that imports `OpenSensorShared` cannot join the hostless bundle and still needs
simulator builds and iPhone runs.

When reviewing iOS changes, report only concrete findings with file and line,
the relevant platform rule, and a focused fix. Separate build/test results from
simulator and real-device observations.
