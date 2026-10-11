# Production readiness plan

**Scope:** the refactor that takes the Android and iOS apps from a working prototype to a
production, modular codebase that can grow new sensors, transports and screens on both platforms.
**Code:** every module. **Related:** [cross-platform-guide.md](cross-platform-guide.md) (the
lasting rules this plan moves the code towards), [architecture.md](architecture.md),
[status.md](status.md).

Written 2026-10-08 from five parallel reviews (Android data, Android UI, shared KMP core, iOS,
build and release) plus a lead pass. Every P0 item below was re-checked against the code by the
lead before it was listed. Baseline `./gradlew --no-daemon verify verifyIos` passed on `00c64f5`. Since 2026-10-09 `verify`
runs `verifyIos` on a Mac.

## Compatibility stance

The app is still a prototype with no user base to protect, so the refactor may break app-side
compatibility whenever that gives a more robust, faster or simpler design. No migrations for old
app storage, settings, credentials or archive layouts, and no compatibility shims. Reinstalling
the app is an acceptable upgrade path. The firmware contract in `../aq-parquet/docs/shared/`
(frames, opcodes, UUIDs, JSON keys, Hive archive paths on the device) still stays exact, because
devices in the field depend on it.

## Why this refactor

The app worked, but four structural problems blocked growth when this plan was written
(2026-10-08). Status on 2026-10-09. Problem 1 still holds (two engines), but the 512-byte name
example is fixed. Problem 2 still holds and the file is now 1597 lines. Problem 3 is resolved by
W8. Problem 4 is partly resolved by W1, W4, W13 and W14. The follow-up for parity between the two
apps is [platform-parity-plan.md](platform-parity-plan.md).

1. **Sync logic exists twice.** Android owns `data/sync/` in Kotlin. iOS re-implemented the
   same download, resume, CRC-32, `PAR1` and archive layout rules in Swift
   (`ios/App/SensorTransfer.swift`, `SensorArchive.swift`). The copies already disagree. iOS
   aborts a sync when a read window comes back short, while the firmware contract
   (`../aq-parquet/docs/shared/ble-sync-protocol.md`) says a failed window is retried from the
   phone's own offset. Android accepts 512-byte names, while the contract caps names at 399 bytes.
   The partial and receipt layouts also differ.
2. **One class owns the whole device.** `DeviceConnectionRepository.kt` is 1430 lines, holds
   link, reconnect, sync, commands, telemetry and credentials, and has unguarded shared state.
   Every ViewModel depends on it directly.
3. **The shared core stops at the protocol.** `:core:parquet` and the file side of
   `:core:history` are JVM-only (`java.nio`, `java.time`, `java.util.zip`), so iOS can not read
   history or verify files through shared code.
4. **Release and UI are prototype grade.** R8 is off, there are no previews or UI tests, debug
   details are visible to ordinary users, and the visual identity does not match
   opensensor.space.

## P0 defects (fix first, all confirmed in code)

Line numbers are from 2026-10-08. Status checked 2026-10-09 where stated, otherwise not rechecked.

| ID | Defect | Evidence | Status |
| --- | --- | --- | --- |
| P0-1 | iOS aborts on a short `READ_END` or an overlapping chunk instead of retrying the window | `ios/App/SensorTransfer.swift:90-103` versus contract `ble-sync-protocol.md` "a failed window is retried" | Fixed (W5, `TransferChecks`) |
| P0-2 | Name limit 512 on Android, contract says at most 399 | `data/sync/SyncPlanner.kt:56` | Fixed, `SyncPlanner` calls `SafeName.isValid` |
| P0-3 | `CancellationException` swallowed by `runCatching` around suspend calls | `DeviceConnectionRepository.kt:561-565`, `SyncWorker.kt:121,125`, `TcpTransport.kt:256` | Not rechecked |
| P0-4 | Unguarded job handles and a non-atomic busy flag, so a Stop can be lost and overlapping commands clear busy early | `DeviceConnectionRepository.kt:801-810, 1181, 1208-1209, 1315, 1332` | Not rechecked |
| P0-5 | GATT callbacks resume whichever operation is pending, so a late write callback can complete the next read | `ble/BleTransport.kt:148-172` | Not rechecked |
| P0-6 | Broadcast receivers launch on an unscoped `CoroutineScope(Dispatchers.IO)` with no handler or timeout | `background/SyncTriggers.kt:139`, `background/AdvertFlagScan.kt:185` | Fixed, no unscoped scope left in `app/src/main` |
| P0-7 | Docs say the database goes to cloud backup, the rules exclude it (the token lives there) | `docs/lessons-learned.md:49` versus `res/xml/data_extraction_rules.xml` | Not rechecked |
| P0-8 | `setup-signing.sh` would upload signing secrets to the old repository name | `tools/setup-signing.sh:16` | Not rechecked |

## Target architecture

```mermaid
flowchart TB
  subgraph common["Kotlin commonMain (JVM + iOS)"]
    model[":core:model"]
    protocol[":core:protocol"]
    parquet[":core:parquet"]
    history[":core:history"]
    sync[":core:sync<br/>engine, plan, resume, verify, archive layout"]
    ds[":ui:designsystem<br/>tokens, theme, shared components"]
  end
  subgraph android[":app (Android)"]
    ble[BLE adapter]
    lan[LAN adapter]
    store[File + Room archive store]
    ui[Compose screens]
  end
  subgraph ios["ios/ (Swift + :ios:shared)"]
    cb[Core Bluetooth adapter]
    nw[Network.framework adapter]
    fm[FileManager archive store]
    cmp[CMP screens]
  end
  protocol --> model
  sync --> protocol
  sync --> parquet
  history --> parquet
  ble & lan --> sync
  store --> sync
  cb & nw --> sync
  fm --> sync
  ui --> ds
  cmp --> ds
```

Shared code owns every rule that must behave the same on both phones. Platform code owns only
radios, sockets, files, permissions, background execution and OS integration. See
[cross-platform-guide.md](cross-platform-guide.md) for the full rule set.

## Workstreams

Each workstream is one branch, built in its own git worktree, gated by
`./gradlew --no-daemon verify` (which includes `verifyIos` on a Mac), plus the unsigned simulator
build for iOS work. Each
has an exclusive file set so branches merge without conflicts. Implementation agents do not edit
`docs/status.md`. They report evidence, and the lead records it once after merging.

### Wave 1 (parallel)

**W1 Build and release hardening.** Owns `gradle.properties`, `app/build.gradle.kts` (build types,
lint, packaging), `app/proguard-rules.pro` or `app/src/main/keepRules/`, `.github/`, `tools/`,
`docs/build-and-release.md`, file headers outside the root build scripts.
- Enable R8 and resource shrinking with keep rules for zstd-jni (JNI field lookup),
  kotlinx.serialization wire models and exception names shown to users. Publish `mapping.txt`.
  Why. Smaller, faster APK and a release that matches what Play expects.
  ([App optimization](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization))
- Provide a local way to build a minified release without signing secrets, so R8 is gated on host.
- Rename the configuration cache key to `org.gradle.configuration-cache=true`, raise Gradle and
  Kotlin daemon heap for native linking.
- Dependabot for Gradle, Actions and pre-commit. Pin actions to commit SHAs. Least-privilege
  release permissions, read-only cache on tag builds, `~/.konan` cache in CI.
- Lint baseline with `warningsAsErrors`. Fix P0-8. Replace the Android sample copyright headers.
- Needs a phone run of the minified APK before the first tagged release.

**W2 Shared core foundation.** Owns `settings.gradle.kts`, root `build.gradle.kts`,
`build-logic/` (new), `core/*/build.gradle.kts`, `ios/shared/build.gradle.kts`, source layout
under `core/`.
- Done (merge `d7b3eea`). Items below are kept as written.
- Included build `build-logic/` with convention plugins (KMP library, JVM library, iOS framework)
  that set targets, bytecode 17, test aliases and warnings policy. Why. Three modules copy
  the same KMP block today and new modules must be one line. Bytecode 17 is a compiler target,
  not `jvmToolchain(17)`, so no JDK download is needed (`OpenSensorConventions.kt`). The
  `opensensor.kmp.compose-feature` convention and convention-registered gates followed on
  2026-10-09 ([platform-parity-plan.md](platform-parity-plan.md) wave 0).
- Done. Move `src/main/kotlin` to `src/jvmMain/kotlin` and `src/test/kotlin` to `src/jvmTest/kotlin`.
  Share Parquet fixtures through a proper test-fixture path, not a cross-project file reach.
- Pure Kotlin `Crc32` and `ParquetVerify` (size, CRC-32, `PAR1` head and tail) in `commonMain`.
  JVM keeps the intrinsic CRC behind `expect`/`actual`. Why. The rule that makes a file "synced"
  must exist once.
- Move portable JUnit tests to `commonTest` with `kotlin.test`, so they also run on iOS.
- Shared `SafeName` with the 399-byte contract limit (fixes P0-2) in `commonMain`.

**W3 Android data hardening.** Owns `app/src/main/java/earth/walkthru/aqsync/data/**` and its
tests, `docs/lessons-learned.md`, `docs/transports.md`, `docs/sync-engine.md`,
`docs/background-sync.md`.
- Fix P0-3 to P0-7 with a regression test each where host-testable.
- Inject dispatchers, an application scope and a clock (Hilt qualifiers). Why. Required for
  `runTest` virtual time and for the later move to `commonMain`.
- One operation gate for busy, sync and background ownership.
- Token hygiene. Plaintext tokens stop flowing to ViewModels. A transient Keystore error no longer
  deletes a credential. The raw-command denylist moves from the UI into the data layer.
- Characterization tests for the repository (reconnect, LAN fallback, token invalidation, stale
  plan) before Wave 2 splits it.
- Keep the public API used by `ui/` source compatible.

**W4 Android design system and simpler UI.** Owns `app/src/main/java/earth/walkthru/aqsync/ui/**`,
`app/src/main/res/**` (except launcher headers handled by W1), `docs/screens.md`.
- Adopt the opensensor.space identity from the website repository (`lib/brand.ts` "sensing"
  palette, Quicksand and Cairo fonts, the peacock mascot, rounded cards, dark pill actions) as a
  token-based Material 3 theme with light and dark schemes. Air-quality band colours stay
  scientific and independent of brand colours.
- Progressive disclosure through the existing detail level. Simple shows what a person needs
  (the headline readings now, today, sync state, one primary action). Detailed adds per-file and per-metric detail.
  Developer alone shows raw frames, logs, CRC, offsets, handles, schema names and diagnostics.
- Split oversized screens into a stateful route and a stateless `Screen(state, onAction)`, one
  file per card. Move the Wi-Fi polling loop out of the composable.
- Read the 1 Hz clock only in leaf composables. Collect one-off events with lifecycle awareness.
- Move device capability checks (schema prefixes, batch candidate) out of composables into a
  shared capability mapping in `:core:history` `commonMain`.

**W5 iOS native hardening.** Owns `ios/App/**`, `ios/OpenSensor.xcodeproj/**`, `ios/tests/**`,
`ios/shared/src/**`, `ios/AGENTS.md`, `docs/ios-migration.md`.
- Fix P0-1 (retry a failed window from the local offset, tolerate overlap) with a test.
- Bytes cross the Kotlin and Swift boundary as `ByteArray` and `NSData`, never hex strings. Why.
  Hex encoding builds one string per byte on the transfer hot path.
- Move LAN receive and archive writes off the main actor onto a per-session queue, with larger
  receive buffers and progress published at a bounded rate.
- Strict concurrency `complete`, then the Swift 6 language mode for the app and test targets (#65).
- File protection `completeUntilFirstUserAuthentication` on the archive, backup exclusion once,
  Local Network policy-denied detection (TN3179).
- An Xcode unit-test target with Swift Testing if it runs under the simulator in CI, otherwise
  keep `verify-native.sh` and document why.

### Wave 2 (after Wave 1 merges)

**W6 Shared sync engine `:core:sync`.** Not started as of 2026-10-09 beyond an empty
`:core:sync` skeleton. Continued in [platform-parity-plan.md](platform-parity-plan.md) waves 2
and 3. Port the engine, planner, resume, read window retry,
progress, rate meter, claims and liveness to `commonMain` behind three ports, `FrameLink`
(send and receive frames), `ArchiveStore` (partial, append, persist, commit, completed) and
`ArchiveIndex` (receipts). One canonical archive, partial and receipt layout, documented in
[data-model.md](data-model.md). The old layouts are dropped, with no migration. Android's
`SyncEngine` becomes an adapter. Fakes in `commonTest` cover resume,
window retry, corruption and cancellation on JVM and iOS.

**W7 Android repository decomposition.** Not started as of 2026-10-09. Split `DeviceConnectionRepository` into
`LinkController`, `TransportSelector`, `DeviceEnrollment`, `LanCredentialService`,
`ReconnectSupervisor` and policy, `ManifestStore`, `TransferCoordinator`, `BackgroundCollector`,
`DeviceCommandService`, `DeviceTelemetry` and a singleton `KnownDeviceRepository`. ViewModels
receive narrow interfaces. Controllers are closed on forget, flows use `WhileSubscribed`.

**W8 Portable Parquet and history.** Own `ByteReader` abstraction (JVM `MappedByteBuffer`, iOS
mapped `NSData`), common LZ4 and Snappy, `expect` gzip, `ByteArray` codec SPI, kotlinx-datetime
in place of `java.time`, typed non-boxing column accessors. kotlinx-io stays at the file edge
only, because its API is still alpha and has no random access.
- Done 2026-10-08 (branch work, simulator and host evidence only). `:core:parquet` and all of
  `:core:history` are `commonMain`. `ByteReader` with `MappedFileReader` (JVM) and
  `MappedDataReader` (iOS), a suspending footer-first reader for W12's range source (replaced by
  `RangeSource` and `ParquetReader` in issue #62), `longStatistics` and `rowGroupsOverlapping` for
  row group pruning, `longValues` / `doubleValues`, gzip through `java.util.zip` and the system zlib. kotlinx-datetime
  0.8.0 replaces `java.time` in `HistoryWindow` and `Columns`. kotlinx-io is not used at all.
- Oracle fixture tests run on the JVM and the iOS simulator (ZSTD fixtures JVM only). Details and
  the changed caller API are in [parquet.md](parquet.md#portability-and-storage-w8-2026-10-08).
- Page and offset index pruning landed with issue #62 (host JVM and iOS simulator only), see
  [parquet.md](parquet.md#portability-and-storage-w8-2026-10-08).
- Not done. History tests that need the fixtures still run on the JVM only. The iOS app does not call the reader or the history index yet.

**W9 Shared design system and iOS restyle.** Extract tokens, theme and shared components (mood
face, now card, band chart drawn with `drawText` and `drawWithCache`) to a Compose Multiplatform
`:ui:designsystem` (started 2026-10-09 with the brand tokens, theme, shapes and floating controls,
so iOS no longer falls back to Material purple). Restyle the iOS screen with it, using the same
detail-level rules.

**W10 UI quality.** String resources and typed events (no English in ViewModels), accessibility
semantics for charts and live regions, previews and host screenshot tests, a device shell for
tab navigation, `SavedStateHandle` for selections.

**W11 Device-agnostic domain.** Not started as of 2026-10-09. `DeviceCapabilities.kt` in
`:core:history` is the interim. Owns new `:core:model`, `Metrics.kt`, `AirMood.kt` and the
capability mapping in `:core:history`. Adds `Device`, `DeviceFamily`, `Capability`,
`ObservedProperty`, `Datastream` and `Scale`, following the firmware observation model. Air
quality becomes one family and `AirMood` one scale. Screens ask for capabilities, never schema
strings. A bundled dictionary per known schema ID is the fallback until the firmware dictionary
ships. Why. The product covers many sensor kinds, and a new family must be data, not new code.
See [devices-and-discovery.md](devices-and-discovery.md).

**W12 Discover map with sample data.** Starts after W9 and W11 merge. Owns new
`:core:publicfeed`, `:feature:discover` (Compose Multiplatform `commonMain`) and
`tools/discover-fixture/`, plus the navigation wiring in both apps. Needs W8's portable
Parquet reader. Design reassessed 2026-10-08 in
[devices-and-discovery.md](devices-and-discovery.md) Part 2.
- A `PublicFeed` port over the website `/indices` layout (one Parquet file per H3 resolution,
  INT64 sorted `h3_index`, `date=`/`hour=` partitions, S3 listing as manifest). The sample feed
  is bundled Parquet in that exact layout, written by a committed Python `h3` and PyArrow
  script, read through the same `:core:parquet` reader. The map labels it "Sample data".
- A suspend HTTP range source with row group and page pruning by H3 descendant ranges and a
  footer and range cache. Issue #62 built `:core:net` (OkHttp and `NSURLSession`, not Ktor) and the
  in-memory footer cache. The disk block cache waits for issues #60 and #61. An H3 interface with `com.uber:h3` on Android and H3 C through
  cinterop on iOS, because the files carry no geometry.
- A map with H3 cells coloured by the chosen metric and window (hourly values only), a
  non-modal sheet with peek, half and full heights, avg, min and max per metric, the device
  list (crowd rule of three applied by the publisher), and a public device page with range,
  aggregation and metric filters.
- `maplibre-compose` 0.20.0, which pins our Kotlin and CMP versions. One GeoJSON source, a
  cover of at most 1,024 cells, redrawn after 400 ms of camera rest. No bundled basemap, an
  optional "Offline map" download in the app Settings, and "© OpenStreetMap" always reachable.
- No location prompt at launch. Centre on the last viewed area, the own device cell or the time
  zone region, with search over a bundled place list. A "Near me" button asks for approximate
  location on tap only.
- Discover is always the start tab, under one app-wide bottom bar (owner decision 2026-10-08).
- Status 2026-10-08 (first slice, not phone qualified). Built `:core:publicfeed`,
  `:feature:discover` and `tools/discover-fixture/` with the sample feed, the map, chips, window,
  legend, sample pill, first-run card, cell sheet, local city search and the "Show public sensors"
  switch, on Android and iOS. Both read the sample through W8's common reader. H3 is `com.uber:h3`
  on Android (JNI copied into `jniLibs`, arm64 and arm only) and the vendored H3 C through cinterop
  on iOS. Still open. Wiring the `:core:net` range source to the live feed, the disk cache, S3 listing, Near me, GeoNames search, device list
  and page, offline map download, half sheet height, our own basemap extract, phone runs.

### Wave 3 (after Wave 2)

- iOS adopts `:core:sync`. Swift keeps only Core Bluetooth, Network.framework, Keychain and
  FileManager adapters. Delete the Swift engine and CRC table.
- iOS background. `BGContinuedProcessingTask` for user-started collection on iOS 26 and later,
  `beginBackgroundTask` fallback, Core Bluetooth state restoration.
- Android platform rules. Connection priority and 2M PHY during transfers (measured first),
  user-initiated data transfer jobs, notification IDs derived from work IDs.
- Real public feed. The host publisher writes cell and device Parquet to S3 or source.coop. The
  phone reads it with the portable Parquet reader, caches with ETags, and has a Settings switch.
- Per-device opt-in publishing, switched on only in that device's settings and off by default,
  to an owner-chosen H3 cell with a random public ID.
- Feature modules (`:feature:today`, `:feature:history`, `:feature:files`, `:feature:device`)
  and baseline profiles once R8 is proven on a phone.

### Later workstreams (merged, not planned above)

- **W13 Clean screens, on-demand permissions and store compliance** (merges `d660a75`,
  `6722a11`, `ae38cb7`). No permission wall, permissions asked on the tap that needs them, store
  forms and a privacy policy draft. See [store-compliance.md](store-compliance.md).
- **W14 String resources, typed device messages and copy rules** (merge `509d203`). Android
  strings moved to `strings.xml`. A shared string catalogue for both apps is still planned in
  [platform-parity-plan.md](platform-parity-plan.md).

## Docs that follow the new direction after Wave 1 merges

Wave 1 agents own these files, so the lead aligns them with the sensor-agnostic and discover
direction only after merging, to avoid conflicts. The list covers `screens.md` (Discover, My
devices and Settings tabs, per-device sharing switch), `transports.md` and `background-sync.md`
(the network rule now allows the public feed and basemap), `ios-migration.md` and
`ios/AGENTS.md` (shared discover screens), and the `ui/` and `data/` `AGENTS.md` files (generic
component and model names). README, `docs/README.md`, `architecture.md`, `core/AGENTS.md`,
`history-pipeline.md` and the older location proposal were updated on 2026-10-08.

## Evidence rules for this plan

A merged branch is host-verified only. R8, BLE throughput, background execution, Local Network
prompts and every radio behaviour still need a dated phone run on each platform before a release
claims them. Record results in [status.md](status.md) and firmware observations in the board's
`bench-verified.md`.
