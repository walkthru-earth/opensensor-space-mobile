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

- Both targets build in the Swift 6 language mode (`SWIFT_VERSION = 6.0`) with
  `SWIFT_STRICT_CONCURRENCY = complete`, and `tests/verify-native.sh` compiles with
  `-swift-version 6` and warnings as errors. Data race errors are compile errors and failed
  `assumeIsolated` checks trap at run time, so keep every delegate, browser and timer on the queue
  that matches its isolation.
- UI-facing adapters (`BluetoothCompanion`, `LanDiscovery`, scene delegates) are `@MainActor`.
  `NWBrowser` and `Timer` callbacks are delivered on the main queue and enter with
  `MainActor.assumeIsolated`. Do not wrap each callback in `Task { @MainActor in }`, which gives up
  delivery order.
- `BlePhysicalLink` is an actor whose executor is its own serial queue. The central manager runs
  on that queue, and a private `NSObject` proxy forwards each delegate callback with
  `assumeIsolated`, which traps on any other queue. The pure `BleGattQueue` runs one GATT
  operation at a time, refuses writes from a stale sender and completes each write once. RESPONSE
  frames and STATUS go straight to the connection's `IosLink`. Main-side events reach
  `BluetoothCompanion` in order, tagged with a connection number that drops stale ones.
- `BleBondFailure` reads Core Bluetooth's error. `peerRemovedPairingInformation`,
  `encryptionTimedOut` and the ATT `insufficientEncryption` and `insufficientAuthentication` mean
  the sensor refused this phone's pairing keys, as after its bonds were reset. The link then ends as
  `Auth(BOND_MISSING)`, which no run retries, and the notice asks to forget the sensor in iOS
  Settings and pair again (P15, P30).
- A closure formed in an isolated method and handed to a Kotlin or Apple API that is not
  concurrency annotated inherits that isolation, and Swift 6 checks it on entry. Mark such a
  closure `@Sendable` when it can run on another thread (`IosLink.whenClosed`,
  `IosLink.onLeaseChange`, `BGTask.expirationHandler`), then hop or `assumeIsolated` inside it.
- `LanSession` is an actor whose executor is its own serial queue. `NWConnection` callbacks and
  `DispatchSourceTimer` handlers run on that queue and enter with `assumeIsolated`. Owners learn
  about pushes and closure through the ordered `events` stream. `close()` is nonisolated.
- File sync runs in the shared Kotlin engine (`SensorSync`, and `SensorCollections` for the shared
  per-device controller, whose `IosLinkProvider` dials through Swift). Swift only moves bytes through the
  connection's one `IosLink` and receives progress and the typed result on the main thread. Values that cross
  domains (`LanReply`, `LanFailure`, `LanCredential`) are Sendable Swift values. Kotlin framework
  objects are not Sendable. Keep each one inside a single isolation domain. A main-actor
  `RunSlot` keeps the engine result, so it never crosses a continuation.
  `RunSlot.cancel` records an atomic cancellation marker synchronously before hopping to main.
  `set` checks that marker before returning, so an already cancelled caller cancels its new handle.
- Two unchecked conformances remain in `LanSession.swift`, each with a one-line invariant at its
  declaration. `IosLink` is `@retroactive @unchecked Sendable` because its entry points are
  thread safe in Kotlin and the ObjC header cannot say so. `IosLink.make` binds the identity
  before the link is shared, and Kotlin checks the one-time atomic binding in every build.
  `WriteDone` wraps Kotlin's write callback, which runs once from any
  thread, and debug builds trap a second call. The ports (`LanPort`, `BlePort`) are checked
  `Sendable` through `weak let` (SE-0481). `BlePort` holds the physical link and its sender id.
  The private `Delivered` box in `BlePhysicalLink.swift` is unchecked too. It carries Core
  Bluetooth objects into `assumeIsolated` on the queue that delivered them.
- Default MainActor isolation (`SWIFT_DEFAULT_ACTOR_ISOLATION`, SE-0466) stays off. It would move
  `SensorArchive`, `LanFrameBuffer` and `LanPolicy` onto the main actor, and socket framing would
  return to the UI thread.
- Approachable Concurrency (`SWIFT_APPROACHABLE_CONCURRENCY`) stays off. It enables
  `NonisolatedNonsendingByDefault` (SE-0461), so nonisolated async functions would run on the
  caller's actor. Review the `@Sendable` closure parameters before enabling it.
- Out of scope here. The Keychain record JSON-encodes `LanCredential`, which creates a base64
  String copy of the token at save and load. Storing the token as a separate raw item needs a
  migration.

## Tests

`OpenSensorTests` is a hostless Swift Testing bundle. It compiles `App/SensorArchive.swift`,
`App/LanFraming.swift`, `App/LanPolicy.swift`, `App/RunSlot.swift` and `App/BleGattQueue.swift` with
`tests/ArchiveChecks.swift`, `tests/TransferChecks.swift`, `tests/CancellationChecks.swift` and
`tests/BleGattChecks.swift`, so it needs neither the app host
nor the Kotlin framework. Run it
with `xcodebuild ... -destination 'platform=iOS Simulator,name=<installed iPhone>'
CODE_SIGNING_ALLOWED=NO test`. `tests/verify-native.sh` runs the same checks on the Mac host.
Add new socket or archive cases to the shared check files so both runners exercise them. File
sync cases belong in `shared/src/iosTest` (`SensorSyncTest`, `SensorCollectionsTest`) and `core/sync` tests.
Code that imports `OpenSensorShared` cannot join the hostless bundle and still needs
simulator builds and iPhone runs.

When reviewing iOS changes, report only concrete findings with file and line,
the relevant platform rule, and a focused fix. Separate build/test results from
simulator and real-device observations.
