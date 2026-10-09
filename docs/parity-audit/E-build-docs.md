# Audit E. Build system, module graph, tests, CI and docs drift

Repo `opensensor-space-mobile`, `main` at `180d1ee`, audited 2026-10-09. Read only. Evidence is from
build files, a `./gradlew verify verifyIos --dry-run`, `git grep` and source counts.

## 1. Module graph today

### Toolchain (gradle/libs.versions.toml, gradle-wrapper.properties)

| Item | Version |
| --- | --- |
| Kotlin | 2.4.20 |
| Compose Multiplatform | 1.12.1 (CMP Material3 1.9.0) |
| AGP | 9.4.1 (`com.android.application`, `com.android.kotlin.multiplatform.library`) |
| Gradle | 9.8.0 |
| KSP | 2.3.12 |
| Hilt | 2.60.1, androidx.hilt 1.4.0 |
| Room | 2.8.5 (Android only today) |
| DataStore | 1.2.1, WorkManager 2.12.0 |
| Navigation 3 | 1.1.7 (androidx, Android only) |
| Lifecycle | 2.11.0 (androidx, Android only) |
| Coroutines 1.11.0, serialization 1.11.0, datetime 0.8.0 | |
| maplibre-compose | 0.19.0, h3 4.5.0 |
| Koin, Metro, Kotlin-Inject, Ktor | **not pinned**, no KMP DI or HTTP library in the catalog |

Android `compileSdk 37`, `minSdk 31`, `targetSdk 37`. iOS deployment target 18.5 (`ios/OpenSensor.xcodeproj/project.pbxproj:27-31`).

### Modules (settings.gradle.kts)

| Module | Plugin | Targets | Depends on |
| --- | --- | --- | --- |
| `:core:protocol` | `opensensor.kmp.library` + serialization | jvm(17), iosArm64, iosSimulatorArm64 | none (jvmTest uses `:core:parquet` test fixtures) |
| `:core:parquet` | `opensensor.kmp.library` + serialization | jvm, iosArm64, iosSimulatorArm64 | none |
| `:core:airquality` | `opensensor.kmp.library` | jvm, iosArm64, iosSimulatorArm64 | none |
| `:core:units` | `opensensor.kmp.library` | jvm, iosArm64, iosSimulatorArm64 | none |
| `:core:history` | `opensensor.kmp.library` | jvm, iosArm64, iosSimulatorArm64 | api protocol, parquet, airquality, kotlinx-datetime |
| `:core:publicfeed` | `opensensor.kmp.library` + serialization, H3 C cinterop | jvm, iosArm64, iosSimulatorArm64 | api parquet, airquality. jvmMain `com.uber:h3` |
| `:ui:designsystem` | raw `kotlin.multiplatform` + `android.kotlin.multiplatform.library` + CMP | **android**, iosArm64, iosSimulatorArm64 (no jvm) | CMP runtime/foundation/material3/ui/resources |
| `:feature:discover` | same raw plugin block (copy of designsystem) | **android**, iosArm64, iosSimulatorArm64 (no jvm) | api publicfeed, units, designsystem. maplibre-compose |
| `:ios:shared` | `opensensor.kmp.ios-framework` + CMP | jvm, iosArm64, iosSimulatorArm64, static `OpenSensorShared` framework | commonMain: protocol, history, units, CMP. **iosMain only**: feature:discover, designsystem |
| `:app` | android.application, Hilt, KSP, compose compiler, serialization | Android | protocol, parquet, history, publicfeed, units, feature:discover, designsystem (airquality transitively) |
| `ios/OpenSensor.xcodeproj` | Xcode | iOS | shell build phase runs `:ios:shared:embedAndSignAppleFrameworkForXcode`, links `-framework OpenSensorShared -lc++ -lz` |

Observations.
- Two families of shared modules. `:core:*` use `jvm()` through the convention plugin. Compose
  modules use the AGP KMP `android {}` target and are configured by hand. The Android block
  (namespace, `compileSdk = 37`, `minSdk = 31`, `withHostTest {}`, `jvmTarget 17`, `androidResources`)
  is duplicated in `ui/designsystem/build.gradle.kts:11-18` and `feature/discover/build.gradle.kts:13-20`.
  No convention plugin covers Compose feature modules.
- Because `:feature:discover` has no `jvm` target and `:ios:shared` does, `:ios:shared` can only
  consume Discover from `iosMain` (`ios/shared/build.gradle.kts:20-24`). This jvm-vs-android split is
  the main build-level obstacle to sharing screens.
- `:app` consumes the shared modules as plain Android-variant dependencies. It does not consume `:ios:shared`.
- `build-logic/bin/main/*.kt` is a stale IDE output copy (git-ignored, identical today). Harmless.
- `OpenSensorConventions.kt` comment says JVM target set without `jvmToolchain` on purpose. The plan
  (`docs/production-readiness-plan.md:124`) still says `jvmToolchain(17)`.

### Gates (build.gradle.kts)

`verify` (`build.gradle.kts:14-18`) depends on
`:ios:shared:jvmTest, :app:lintDebug, :app:assembleDebug, :app:testDebugUnitTest, :core:protocol:test,
:core:parquet:test, :core:history:test, :core:publicfeed:test, :core:units:test,
:feature:discover:testAndroidHostTest, :ui:designsystem:testAndroidHostTest`.

`verifyIos` exists (`build.gradle.kts:21-25`) and depends on
`iosSimulatorArm64Test` of protocol, parquet, history, publicfeed, units, `:ios:shared`, plus
`:ios:shared:linkDebugFrameworkIosSimulatorArm64`.

Gaps confirmed by `--dry-run`.
- **`:core:airquality` tests never run.** Neither `:core:airquality:test`/`jvmTest` nor
  `:core:airquality:iosSimulatorArm64Test` is in either gate. Its 7 commonTest tests (WHO guideline
  edges and `WhoPaletteTest`, the colour-blind palette check the cross-platform guide cites) are dead in CI.
- `:feature:discover` and `:ui:designsystem` have no iOS test task in `verifyIos`. Their iOS code is
  only compiled indirectly through the `:ios:shared` framework link.
- `iosArm64` (device) Kotlin compile is gated only by the CI `xcodebuild -configuration Release` step, not by Gradle.
- The aggregate list is hand-maintained. Adding a module silently skips its tests (exactly what happened to airquality).

### CI (.github/workflows/verify.yml, release.yml)

- `checks` job (ubuntu): pre-commit `--all-files` (hygiene, gitleaks, ktlint).
- `verify` job (ubuntu): two Python migration validators, `./gradlew verify`, upload debug APK and reports.
- `ios` job (`runs-on: xcode-27`, Xcode 27.0 pinned, `workers.max=2`): `ios/tests/verify-native.sh`
  (swiftc host harness over `SensorArchive.swift`, `SensorTransfer.swift`, `LanPolicy.swift`),
  `./gradlew verifyIos`, unsigned simulator Debug build, Swift Testing target on a simulator
  (`OpenSensorTests`), unsigned device Release build, simulator app artifact.
- `release.yml` (ubuntu): `verify :app:assembleRelease`, apksigner check, GitHub release. No iOS release.
- Hooks: `.pre-commit-config.yaml` pre-commit (hygiene, gitleaks, ktlint via `tools/hooks/ktlint.sh`)
  and pre-push `./gradlew -q verify`. **Pre-push never runs `verifyIos`, even on a Mac.**
- The konan cache key (`verify.yml:107`) hashes `core/*/build.gradle.kts` and `ios/shared/...` but not
  `feature/*` or `ui/*` build files. Minor.

## 2. Test placement

Counts of `@Test` functions by source set (files in brackets).

| Source set | Tests | Runs on |
| --- | --- | --- |
| commonTest, `:core:protocol` | 72 (11) | JVM + iOS sim |
| commonTest, `:core:parquet` | 31 (6) | JVM + iOS sim |
| commonTest, `:core:publicfeed` | 24 (4) | JVM + iOS sim |
| commonTest, `:core:history` | 15 (4) | JVM + iOS sim |
| commonTest, `:core:units` | 15 (2) | JVM + iOS sim |
| commonTest, `:ios:shared` | 15 (2) | JVM + iOS sim |
| commonTest, `:core:airquality` | 7 (1) | **nowhere (not gated)** |
| **commonTest total** | **179** | |
| jvmTest (`history` 29, `protocol` 8, `parquet` 3) | 40 | JVM only |
| androidHostTest (`feature:discover` 13, `designsystem` 4) | 17 | Android host only |
| iosTest (`parquet` 2, `ios:shared` 2) | 4 | iOS sim only |
| `app/src/test` | 207 (40) | Android host only |
| `app/src/androidTest` | 1 | not in any gate |
| Swift (`ios/tests`) | 2 `@Test` wrappers over ~56 `check(...)` assertions in `ArchiveChecks.swift` / `TransferChecks.swift` | Mac host + iOS sim |

So 179 of about 447 Kotlin tests are shared. The rest (264) run on one platform only.

### Behaviour the guide says must be in commonTest but is not

`docs/cross-platform-guide.md:89-91` says resume, window retry, corruption, cancellation and name
validation are tested in `commonTest` and run through `verify` and `verifyIos`, and that a behaviour
tested only in `jvmTest` or only in Swift is not shared. Reality.

| Behaviour | Android (Kotlin) | iOS (Swift) | Shared? |
| --- | --- | --- | --- |
| Name validation | `SafeNameTest` commonTest, `SyncPlanner` now calls `SafeName` | Swift whitelist + 399 limit re-implemented, `ArchiveChecks` | rule shared on Android only, Swift has its own copy |
| Corruption / verify (size, CRC, PAR1) | `ParquetVerifyTest` commonTest | Swift CRC table + verify in `SensorArchive.swift`, `ArchiveChecks` | Kotlin rule shared, iOS does not call it |
| Window retry | `WindowRecoveryTest` (app) | `TransferChecks` (short READ_END, gap, overlap, exhaustion) | **two engines, two test suites** |
| Resume | `SyncResumePolicyTest`, `ConcurrentArchiveTest` (app) | `TransferChecks` resume, receipts | **two engines** |
| Cancellation | `ConcurrentArchiveTest`, `AutoSyncRunnerTest` (app) | `TransferChecks` | **two engines** |
| Sync plan, smart sync, manifest | `SyncPlannerTest`, `SmartSyncPolicyTest`, `ManifestStateTest` (app) | none | Android only |
| Device continuity, ownership | `DeviceContinuityPolicyTest`, `DeviceOwnershipTest` (app) | Swift alias guard, untested in Kotlin | Android only |
| LAN token policy | `LanTokenPolicyTest` (app) | `LanPolicy.swift` | two copies |
| Chart axis, aggregation, columns, history window, time estimate, file summary, indexer | `core/history/src/jvmTest` (29 tests, JUnit; `ColumnsTest`/`HistoryWindowTest` use `java.time`) | not run on iOS | code is commonMain, tests are JVM only |
| Discover cell chart math and formatting | `CellChartMathTest`, `DiscoverFormatTest` androidHostTest (no Java imports) | not run on iOS | `:feature:discover` has no commonTest |
| Y axis ticks, history presentation, file grouping, copy rules | app tests | none | Android only |

Pure Kotlin in `app/` with no Android imports, so cheap to move to a shared module with its tests:
`data/sync/SyncPlanner.kt`, `SyncResumePolicy.kt`, `SmartSyncPolicy.kt`, `FileProgressReporter.kt`,
`ReadWindow.kt` (needs `AqTransport` to become a port), `data/ManifestState.kt`,
`DeviceContinuityPolicy.kt`, `DeviceMessage.kt`. `SyncEngine.kt` still imports Room DAO, `javax.inject`
and `sha256Hex` (JVM), and `FileArchive.kt` uses `java.io`.

## 3. Docs drift (file:line)

### docs/architecture.md
- `:7-27` "Module graph" (labelled "today's code" at `:42`) lists only protocol, parquet, history, app,
  ios:shared. Missing `:core:publicfeed`, `:core:units`, `:core:airquality`, `:ui:designsystem`, `:feature:discover`.
- `:76` `opensensor.kmp.library` "applied by protocol, parquet, history". Also publicfeed, units, airquality.
  No row for the Compose feature module setup.
- `:92-93` "`verify` and `verifyIos` aggregate the module tasks". They miss `:core:airquality` entirely.
- `:126` `AppDatabase (v9)`. Code is `version = 10` (`AppDatabase.kt:34`, `app/schemas/10.json`). `:16` already says v10, so the doc contradicts itself.
- `:154-155` "Android `SyncPlanner.kt` still has its own copy with a 512 limit". Fixed, `SyncPlanner.kt:96` calls `SafeName.isValid`.
- `:164` "`:core:parquet` and file-backed history stay JVM-only". False since W8, contradicts `:31` of the same doc.
- `:136-140` core file table omits publicfeed, units, airquality.

### docs/ios-migration.md
- `:5` Code line lists only `core/{protocol,history}`.
- `:21-24` "old `src/main/kotlin` and `src/test/kotlin` remain mapped to jvmMain/jvmTest". W2 moved them, no such mapping exists in any build file.
- `:27` "The iOS app does not call the reader yet". Contradicted by `:47-49` (Discover reads the bundled sample through the common reader). True only for the archive.
- `:161-162` `verify` description omits Compose host tests and silently excludes airquality.
- `:175-177` CI description omits `verify-native.sh` and the Swift Testing step that the job runs.

### docs/build-and-release.md
- `:46` `zstd-jni 1.5.7-17`. Catalog has `1.5.7-20`.
- `:47` "AGP 9.4.0, Gradle 9.7.1". Catalog AGP 9.4.1, wrapper Gradle 9.8.0. Also omits units and airquality from the "target JVM and iOS" list.
- `:88` `verifyIos` "runs protocol, history and presentation common tests". It also runs parquet, publicfeed, units, and not airquality.

### docs/cross-platform-guide.md
- `:41-72, :83, :104` ports `FrameLink`, `ArchiveStore`, `ArchiveIndex`, `CredentialStore` and `payloadMax` on a port. None of these interfaces exist (`git grep` empty). The diagram is presented as "Where the code lives", not as a target.
- `:80-82` "One engine ... runs in shared Kotlin". Today `app/.../data/sync/SyncEngine.kt` (Android) and `ios/App/SensorTransfer.swift` + `SensorArchive.swift` (Swift) are separate engines.
- `:89-91` rule 5, see section 2.
- `:112` `:core:model` and `DeviceFamily`. Neither exists. `MetricCard` (`:113`, also `core/AGENTS.md:20`) does not exist.
- `:210` "shared string catalogue". None. Android has 731 `<string>` entries in `res/values*/strings.xml`, Discover has `DiscoverStrings.kt`, iOS screen strings live in `ios:shared` Kotlin.
- `:221` checklist asks for `verify verifyIos`. Neither the pre-push hook nor Linux CI runs `verifyIos`.

### docs/production-readiness-plan.md
- `:9-11, :96-268` lists W1 to W12 only. Git history also merged **W13** (`d660a75`, `6722a11`, `ae38cb7`) and **W14** (`509d203`), referenced by `docs/status.md:9`, which the plan never defines.
- `:24-41` "four structural problems" written as present tense. Problem 3 (parquet/history JVM-only) is resolved by W8 (`:195`). Problem 1 still holds (two engines), but the 512 example (`:31`) is fixed.
- `:43-54` P0 table has no status column. P0-2 is fixed (`SyncPlanner.kt:96`). P0-6 unscoped `CoroutineScope(Dispatchers.IO)` no longer appears in `app/src/main`. P0-1 is fixed per `ios-migration.md:90-96` and `TransferChecks`. Others not checked in this slice.
- `:33` `DeviceConnectionRepository.kt` "1430 lines". Now 1597 (`wc -l`). W7 decomposition has not happened and the file grew.
- `:124` `jvmToolchain(17)`. Deliberately not used (`build-logic/.../OpenSensorConventions.kt`).
- `:126` source move (W2) is done but the item is not marked.

### docs/status.md
- `:79-81` "247 tests ... across `:app` and the three `:core` modules". Today 6 core modules plus 2 Compose modules plus ios:shared, roughly 447 Kotlin tests.
- `:201-214` ladder says 151 tests (2026-09-18). Stale, as dated, but it is under a heading presented as current.

### AGENTS.md files
- `AGENTS.md:45-47` "`protocol` and the sensor-domain part of `history` compile for JVM and iOS; platform file/stream adapters stay in JVM source sets". Parquet, publicfeed, units, airquality also target iOS, and there are `iosMain` adapters (NSData mapping, zlib, H3 cinterop).
- `AGENTS.md:79-80` "`verify` (lint, debug APK and all unit tests)". Not all, airquality and `androidTest` excluded.
- `core/AGENTS.md:3-6, :27` names only protocol, parquet, history.
- `core/AGENTS.md:40-41` "Shared tests in commonTest run ... on the iOS simulator through `verifyIos`". Not for airquality.
- `core/AGENTS.md:18-20` `:core:model` "when it lands (W11)" and `MetricCard`. Consistent as future, but W11 is not started.
- `.pre-commit-config.yaml:8` "every module's unit tests". Same airquality gap.

### Workstreams related to iOS parity, real status

| WS | Plan says | Real status |
| --- | --- | --- |
| W2 shared core foundation | conventions, source moves, commonMain verify/CRC/SafeName | Done (merge `d7b3eea`). Conventions exist for core and ios-framework only, none for Compose features. |
| W5 iOS native hardening | P0-1, NSData, off-main LAN, file protection, Swift Testing | Done (merge `893c23f`), host/sim evidence. |
| W6 `:core:sync` | shared engine behind FrameLink/ArchiveStore/ArchiveIndex | **Not started.** No module, no ports. Biggest parity gap. |
| W7 repository split | decompose `DeviceConnectionRepository` | **Not started**, file grew to 1597 lines. Blocks moving data logic to shared. |
| W8 portable Parquet/history | commonMain | Done for code. History fixture tests still jvmTest only. iOS archive does not use the index. |
| W9 shared design system | tokens, theme, components, iOS restyle | Partial. Tokens, theme, fonts, floating controls in `:ui:designsystem`. Mood face, now card, band chart still in `app/ui`. |
| W10 UI quality | strings, previews, screenshot tests | Partial via W14 (string resources), but Android-only `strings.xml`, no shared catalogue. |
| W11 `:core:model` | device-agnostic domain | **Not started.** `DeviceCapabilities.kt` in `:core:history` is the interim. |
| W12 Discover | shared feature | First slice shipped on both platforms. Ktor range source, device page, etc. open. |
| Wave 3 | iOS adopts `:core:sync`, feature modules `:feature:today/history/files/device` | Not started. |

## 4. Build-level proposal for sharing Android screens and data logic

### 4.1 Convention plugins (build-logic)

1. **`opensensor.kmp.compose-feature`** (new). Applies `kotlin.multiplatform`,
   `com.android.kotlin.multiplatform.library`, `org.jetbrains.compose`, `kotlin.plugin.compose`.
   Configures `android { compileSdk/minSdk from one constant, withHostTest {}, androidResources, jvmTarget 17 }`,
   `iosArm64()`, `iosSimulatorArm64()`, `-Xexpect-actual-classes`, CMP runtime/foundation/material3/ui/resources
   in commonMain, `kotlin-test` in commonTest, a `compose.resources` block with `packageOfResClass`
   derived from the namespace, and a `test` alias to `testAndroidHostTest`. Replace the hand blocks
   in `:ui:designsystem` and `:feature:discover` with it. Note AGP's KMP host test compiles
   `commonTest` into `androidHostTest`, so a commonTest in a feature module runs in `verify` and on iOS.
2. **`opensensor.kmp.android-library`** (new, non-Compose). Same as `kmp.library` but with the AGP
   `android {}` target instead of `jvm()`, for shared data modules that need Android actuals
   (Room KMP, DataStore, Context-backed adapters). Keep `opensensor.kmp.library` (jvm) for pure
   core modules so host tools keep a plain JVM jar.
3. **`opensensor.kmp.ios-framework`**. Change `:ios:shared` to iOS targets only (drop `jvm`). Its
   two commonTest files (`CompanionStateTest`, `SyncWireTest`) move with `CompanionState` into a
   `:feature:devices` module, so they run on Android host and iOS sim. Then `:ios:shared` can depend
   on feature modules from `commonMain`, and becomes a thin umbrella that `export`s nothing but the
   Swift-facing API.
4. **Aggregation by convention, not by list.** Each convention registers its module tasks into
   root lifecycle tasks (for example `allTests` style). Root `verify` = `:app:lintDebug :app:assembleDebug`
   plus every project's `test`. `verifyIos` = every project's `iosSimulatorArm64Test` plus the framework
   link plus `compileKotlinIosArm64`. This would have caught `:core:airquality`.
5. **Shared constants.** Move `compileSdk 37`, `minSdk 31` into `OpenSensorConventions` (or the catalog) so `:app` and the features cannot drift.

### 4.2 Module shape for moved screens

- `:feature:today`, `:feature:history`, `:feature:files`, `:feature:device`, `:feature:settings`,
  `:feature:devices` (iOS companion state) as `opensensor.kmp.compose-feature`. Each exposes a
  stateless `Screen(state, onAction)` and a `ViewModel`/presenter in commonMain.
- ViewModel and navigation. JetBrains publishes KMP forks of `lifecycle-viewmodel-compose` and
  Navigation 3 for CMP (`org.jetbrains.androidx.lifecycle`, `org.jetbrains.androidx.navigation3`). Pin
  versions compatible with CMP 1.12.1 after checking the CMP compatibility table. Not verified in this audit.
- Strings move to Compose resources (`composeResources/values/strings.xml`) per feature, so both
  platforms read one catalogue. Android `res/values` keeps only manifest, notification and permission strings.
- Data. New `:core:sync` (`opensensor.kmp.library`, jvm+ios) with ports `FrameLink`, `ArchiveStore`,
  `ArchiveIndex`, `Clock`, dispatchers. Seed with the pure files listed in section 2 and their tests
  moved to commonTest. `SyncEngine` follows after W7 splits the repository.
- Storage. Room 2.8.x and DataStore 1.2.x have KMP artifacts with an iOS bundled SQLite driver. That
  is a candidate for the iOS archive index (ios-migration "Remaining migration" item 1) without a
  second schema. Needs a KSP-per-target setup (`kspAndroid`, `kspIosArm64`, `kspIosSimulatorArm64`).
  Verify against Kotlin 2.4.20 and KSP 2.3.12 before adopting.

### 4.3 How app and iOS consume them

- `:app` adds `implementation(project(":feature:x"))` and hosts each feature in its Navigation 3
  entry. Android-only capabilities (permission prompts, SAF, WorkManager, foreground service) stay
  in `:app` or in a feature's `androidMain` actual, behind an interface passed in from `:app`.
- `:ios:shared` `commonMain` depends on the same features and builds the iOS root (tab bar already
  in `AppRoot.kt`). Swift adapters (`BluetoothCompanion.swift`, `LanSession.swift`) implement the
  Kotlin ports and are passed into `MainViewController(...)`. Keep the direct Xcode framework build phase.

### 4.4 DI without Hilt in shared code

- Nothing is pinned for KMP DI today (no Koin, Metro or kotlin-inject in the catalog). Hilt stays in `:app` (10 `@HiltViewModel`, 35 injected classes, `data/di/`).
- Recommendation. **Manual constructor injection in shared code.** Each shared feature exposes a small
  `FeatureDeps` interface or data class (ports, dispatchers, clock, settings) and a factory. `:app`
  provides it from a Hilt `@Provides` module, iOS builds it once in `AppRoot`/`MainViewController`.
  This keeps shared code free of a DI framework, works with Swift callers, and matches the graph size.
  ViewModels are created with `viewModel { FeatureViewModel(deps) }` from the KMP lifecycle artifact.
- If the graph grows, Koin 4.x (runtime, KMP, Compose ViewModel support) is the common choice, and
  Metro is a compile-time KMP option. Either must be checked against Kotlin 2.4.20 and the
  configuration cache before pinning. Do not mix Hilt into shared modules.

### 4.5 Gating iOS in `verify`

- Add to root `build.gradle.kts`, `if (org.jetbrains.kotlin.konan.target.HostManager.hostIsMac) verify.dependsOn("verifyIos")`,
  or an opt-out property `-Popensensor.skipIos=true`. Linux CI keeps working, every Mac pre-push runs iOS.
- Include `compileKotlinIosArm64` (device) for every shared module in `verifyIos`, cheap and catches
  device-only `actual` gaps before `xcodebuild`.
- Linux can cross-compile iOS klibs for modules without cinterop. `:core:publicfeed` (xcrun clang H3)
  cannot. Optional `verify` addition on Linux, `compileKotlinIosSimulatorArm64` for cinterop-free
  modules, if the Kotlin 2.4 klib cross-compilation behaves on this project. Check first.
- Run `testAndroidHostTest` for every Compose feature and `test` for every core module through the
  convention aggregation, not a list.
- Add `feature/*/build.gradle.kts` and `ui/*/build.gradle.kts` to the konan cache key.

## 5. Ordered steps

| # | Step | Size |
| --- | --- | --- |
| 1 | Add `:core:airquality:test` and `:core:airquality:iosSimulatorArm64Test` to `verify`/`verifyIos` (immediate fix for dead tests) | S |
| 2 | Fix docs drift listed in section 3 (architecture graph and tables, ios-migration source-set and reader claims, build-and-release versions, AGENTS gate wording, plan P0 status and W13/W14, status test counts) | S |
| 3 | Convention-based aggregation of `test`/`iosSimulatorArm64Test`/`compileKotlinIosArm64` into `verify`/`verifyIos`, and `verify` depends on `verifyIos` on macOS hosts. Update pre-push wording | S |
| 4 | `opensensor.kmp.compose-feature` plugin, migrate `:ui:designsystem` and `:feature:discover`, shared SDK constants. Add a commonTest to `:feature:discover` and move `CellChartMathTest`, `DiscoverFormatTest` there | S |
| 5 | Move `core/history/src/jvmTest` portable tests to commonTest (replace `java.time` with kotlinx-datetime, JUnit with kotlin.test, fixtures via the parquet `fixtureBytes` helper) | M |
| 6 | Drop `jvm` from `:ios:shared`, move `CompanionState` and its tests into a `:feature:devices` compose-feature module, let `:ios:shared` commonMain depend on features | M |
| 7 | Pin KMP lifecycle-viewmodel and navigation3 for CMP, decide manual DI pattern (`FeatureDeps` + Hilt bridge), and move one Android screen end to end as the template (Settings is smallest) | M |
| 8 | Create `:core:sync` with ports and move the pure sync policies (`SyncPlanner`, `SyncResumePolicy`, `SmartSyncPolicy`, `ManifestState`, `FileProgressReporter`, `DeviceContinuityPolicy`, `DeviceMessage`) with tests to commonTest | M |
| 9 | W7 split of `DeviceConnectionRepository` (1597 lines) so the engine and controllers have narrow inputs | L |
| 10 | Port `SyncEngine`/`ReadWindow`/`FileArchive` onto ports in `:core:sync`, Android becomes an adapter, port Swift `TransferChecks` scenarios to commonTest fakes | L |
| 11 | iOS adopts `:core:sync` (Swift keeps Core Bluetooth, NWConnection, Keychain, FileManager adapters), delete Swift engine and CRC, retire `verify-native.sh` engine checks | L |
| 12 | Move Today, History, Files, Device screens into feature modules with Compose resources strings. Decide Room KMP for the iOS archive index | L |
