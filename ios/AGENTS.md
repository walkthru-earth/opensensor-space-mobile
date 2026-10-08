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

When reviewing iOS changes, report only concrete findings with file and line,
the relevant platform rule, and a focused fix. Separate build/test results from
simulator and real-device observations.
