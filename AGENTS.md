# Agent map — opensensor.space mobile (Android + iOS)

Mobile companion for `../opensensor-space-firmware`. Android and iOS apps live in this repository with
shared Kotlin modules. The Gradle project is `opensensor-space-mobile`. The Android
application ID and the iOS bundle ID are `space.opensensor.app`. All Kotlin code lives under
the `space.opensensor` package.
Confirm the checkout and GitHub repository names from Git before recording
them; do not infer them from the directory name. Start with this file, then open only the
concern you are changing in `docs/README.md`.

| Task | Open next | Code |
| --- | --- | --- |
| Current status and real-phone evidence | `docs/status.md`, then `../opensensor-space-firmware/docs/boards/*/bench-verified.md` | — |
| Cross-platform design and sync rules | `docs/cross-platform-guide.md` | `core/`, `app/`, `ios/` |
| Device families, sensor-agnostic model, public H3 discover map | `docs/devices-and-discovery.md` | `core/publicfeed/`, `feature/discover/`, `tools/discover-fixture/` |
| iOS app, shared Kotlin, simulator and iPhone builds | `docs/ios.md`, `ios/AGENTS.md` | `core/`, `ios/` |
| App layers and dependencies | `docs/architecture.md`, `core/AGENTS.md` | `core/`, `app/` |
| BLE, LAN, sync, pairing, background work | `docs/transports.md`, `docs/sync-engine.md`, `docs/background-sync.md`, `app/src/main/java/space/opensensor/data/AGENTS.md` | `data/` |
| Room, history, Parquet | `docs/data-model.md`, `docs/history-pipeline.md`, `docs/parquet.md` | `data/history/`, `data/local/`, `core/` |
| Screens and interaction | `docs/screens.md`, `app/src/main/java/space/opensensor/ui/AGENTS.md` | `ui/` |
| Build, hooks and release | `docs/build-and-release.md` | `tools/`, `.github/` |
| Store forms, permission timing, privacy manifest | `docs/store-compliance.md` | `ui/permissions/`, `ios/App/` |

## Boundaries

- The wire and telemetry contracts belong to `../opensensor-space-firmware/docs/shared/`. Resolve
  ambiguity there before changing this app's mirror. `ble-sync-protocol.md` owns
  the wire contract. Mobile requires `proto >= 3`. Preserve frame layouts,
  opcodes, UUIDs, JSON keys and Hive archive paths across both repositories.
- The phone archive is local and works without internet. BLE is always available;
  LAN connects only to devices found through `_ossync._tcp` mDNS, using a token
  obtained over bonded BLE. The only other network use is anonymous, read-only
  GETs of the public discover feed and basemap, which a Settings switch turns off
  (`docs/devices-and-discovery.md`). Uploading a device's data is off by default
  and is enabled only per device, from that device's settings, with
  confirmation, under CC BY 4.0. No analytics or accounts. Location permission is foreground and approximate only, asked from "Near me" or
  Device settings "Use phone location". Near me only moves the map. Device setup
  converts the fix to the selected H3 cell before UI state or wire use, then sends
  only that cell on explicit Save. Never persist, log or send the original fix.
  Default device resolution is 8; increasing precision requires a fresh fix.
  Public data carries H3 cells, never coordinates. See `docs/device-location.md`.
- A downloaded file becomes synced only after size, CRC-32 and `PAR1` head/tail
  checks pass. Missing measurements stay null. Never ask firmware to delete,
  rename or rewrite archive files.
- Keep PIN, Wi-Fi PSK and LAN token out of logs, frames, screenshots and docs.
  LAN tokens remain encrypted at rest. Device-changing actions are explicit UI
  actions; reboot, token rotation, forgetting bonds and pairing mode `none`
  require confirmation. Debug raw commands cannot invoke `REBOOT` or `SET_CONFIG`.
- `:core:*` stays reusable without Android or Hilt. Room lives only in `:core:database`,
  one Room KMP schema on the bundled SQLite driver for both phones. Every other `:core` module
  (`protocol`, `parquet`, `history`, `publicfeed`, `units`, `airquality`, `archive`,
  `storage`, `net`, `sync`, `settings`, `stac`, `thing`) compiles for JVM and iOS from `commonMain`.
  Platform adapters stay in `jvmMain` and `iosMain` (streams, file mapping, zlib,
  H3). `:ui:designsystem`, `:ui:permissions` and `:feature:discover` are Compose
  for Android and iOS.
  `:ios:shared` is iOS only. Common code cannot import `java.*`; platform
  source sets may depend on common code, never the reverse.
  Transport and storage work stays out of composables. Sensor semantics come
  from the metric registry, never fabricated defaults.
- `:core:sync` owns one `LinkSession` per physical connection, the shared `SyncEngine`,
  `ArchiveCollector` and per-device `CollectionController`. Android `DeviceFacade` routes
  collection and UI through that controller. `DeviceLinkOwner` owns connections, manual
  commands and idle reconnect.
  iOS adopts the link and engine through `IosLink` and `SensorSync`, and Core Bluetooth runs
  on the `BlePhysicalLink` serial queue. `SensorCollections` holds one shared controller per
  device over `IosLinkProvider`, and every native sync entry point runs through it
  ([#81](https://github.com/walkthru-earth/opensensor-space-mobile/issues/81)).
- Platform entry points own their UI and OS services. Android Compose and Android
  framework APIs stay in `app/`; iOS app code and Apple frameworks stay in `ios/`.
  The current iOS screen uses Compose Multiplatform; native Swift files are adapters.
  Apply SwiftUI guidance only to native SwiftUI code. Keep KMP APIs consumed from
  Swift narrow and documented, and use the existing direct Xcode framework setup.
- Keep host/JVM tests, iOS simulator builds, Android phone runs, and iPhone runs
  distinct. A host or simulator result does not establish real-device radio behavior.
  Record app observations in `docs/status.md` and firmware/board observations in
  the relevant `bench-verified.md`; do not present a host fixture as hardware qualification.

## AI investigation and delegation

- For broad investigations or changes spanning independent concerns, use multiple
  subagents when available. Give each a bounded question or file set; ask for source
  links and concrete findings. Avoid overlapping edits. The lead agent reconciles
  findings against this repository, firmware-owned contracts, and dated evidence,
  then owns the integrated changes. Small, single-concern tasks do not need delegation.
- For evolving Kotlin, Apple, or toolchain behavior, check current primary sources
  and this repo's pinned versions. Do not copy version assumptions from a third-party
  skill into this project without checking its deployment target and build.
- The linked [SwiftUI Agent Skill](https://github.com/twostraws/swiftui-agent-skill)
  is a reference for SwiftUI reviews (state ownership, navigation, accessibility,
  performance, and API availability), not an installed dependency. Do not apply its
  SwiftUI rules to the current Compose iOS screen. See `ios/AGENTS.md` for local
  platform boundaries and review guidance.

## Gate

Use the bundled toolchain and run `./gradlew --no-daemon verify` before a commit
(lint, debug APK, `:app` unit tests and every shared module's host tests; on a Mac
also `verifyIos`, the iOS simulator tests and framework link). Each convention
plugin adds its module to the gates, so a new module needs no list edit.
`app/src/androidTest` is not gated. The installed pre-commit hook checks hygiene,
secrets and ktlint; the pre-push hook runs `verify`. For setup and CI details see
`docs/build-and-release.md`. Phone behavior requires a phone run and dated evidence.
