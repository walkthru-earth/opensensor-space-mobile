# ADR 0001. Shared shell and navigation

- Status. Accepted (lead agent decision, owner review pending)
- Date. 2026-10-09
- Issue. #12 (shared shell and navigation), parity plan step 0 and item 2
- Features covered. F01, F03, F04, F05, F12, F34 in `docs/parity-audit/F-feature-inventory.md`

## Context

Android already has the navigation we want. `app/.../ui/Navigation.kt` draws one bottom bar
(Discover, My devices, Settings) around one Navigation 3 `NavDisplay`. Each tab has its own
`rememberNavBackStack`, decorated with `rememberSaveableStateHolderNavEntryDecorator` and
`rememberViewModelStoreNavEntryDecorator`, so a device left open in My devices keeps its
ViewModels while another tab shows. The rules live in `TabNavigator.kt`, which is free of Compose
and depends only on `androidx.navigation3.runtime.NavKey`. Reselecting a tab pops it to its root,
Back pops the active stack and then returns a tab root to Discover, and at the Discover root Back
closes the app. `TabNavigatorTest` covers this as a JVM unit test in `:app`.

iOS has none of this. `ios/shared/src/iosMain/.../AppRoot.kt` keeps one `tab` variable, a custom
`TabBar`, no stacks, no per-entry state and no back handling (audit A 1.1, F04 missing). The
Discover sheet collapse on Back (F12) uses `androidx.activity.compose.BackHandler`, so it exists
only on Android.

Pinned toolchain. Kotlin 2.4.20, Compose Multiplatform 1.12.1, androidx `navigation3-runtime` and
`navigation3-ui` 1.1.7, `lifecycle-viewmodel-navigation3` 2.11.0, lifecycle 2.11.0,
kotlinx-serialization 1.11.0, Android `targetSdk` 37, iOS deployment target 18.5.
maplibre-compose 0.19.0 ties Kotlin and CMP, so the navigation choice must not force a CMP bump.

Criteria, in order.

1. Per-tab back stacks, reselect pops to root, Back returns to Discover.
2. iOS start edge swipe back.
3. Android predictive back, with no regression from today.
4. Saved state across process death on Android.
5. Hilt ViewModels at the Android edge, plain controllers in common (audit A finding 6).
6. Minimal new dependencies.
7. Navigation rules testable in `commonTest`.

## Evidence

Checked on 2026-10-09 against Maven Central, Google Maven, JetBrains docs and the local Gradle cache.

- CMP 1.12.1 release notes name the paired libraries. Navigation 3
  `org.jetbrains.androidx.navigation3:navigation3-*:1.1.2`, lifecycle
  `org.jetbrains.androidx.lifecycle:lifecycle-*:2.11.0`, Navigation Event
  `org.jetbrains.androidx.navigationevent:navigationevent-compose:1.1.0`, navigation (nav2 style)
  `org.jetbrains.androidx.navigation:navigation-*:2.10.0-beta01`.
  <https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.12.1>
- `org.jetbrains.androidx.navigation3:navigation3-ui` versions on Maven Central end at 1.1.2
  (stable, published 2026-09-22, the same day as CMP `ui` 1.12.1) and 1.2.0-rc01 (2026-10-06).
  <https://repo1.maven.org/maven2/org/jetbrains/androidx/navigation3/navigation3-ui/maven-metadata.xml>
- The 1.1.2 POM depends on `androidx.navigation3:navigation3-runtime:1.1.7`, the exact runtime the
  app pins today, plus `navigationevent-compose` 1.1.0 and CMP 1.10.0 or newer. Its Android variant
  (`navigation3-ui-android` 1.1.2) depends on `androidx.navigation3:navigation3-ui-android:1.1.7`.
  So on Android the JetBrains coordinate resolves to the same Google binaries the app ships now.
  It publishes `iosArm64` and `iosSimulatorArm64` variants.
  <https://repo1.maven.org/maven2/org/jetbrains/androidx/navigation3/navigation3-ui-android/1.1.2/navigation3-ui-android-1.1.2.pom>
- `androidx.navigation3:navigation3-runtime:1.1.7` is itself multiplatform. Its Gradle module
  lists Android, desktop JVM, `iosArm64`, `iosSimulatorArm64` and more. `NavKey`, `NavBackStack`,
  `rememberNavBackStack` and `rememberDecoratedNavEntries` can therefore sit in `commonMain` without
  a JetBrains fork. Google's `navigation3-ui` 1.1.7 has no iOS variant, which is why the JetBrains
  `navigation3-ui` exists. (Read from the cached `navigation3-runtime-1.1.7.module`.)
- `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3:2.11.0` exists (published
  2026-07-13). Its Android variant depends on `androidx.lifecycle:lifecycle-viewmodel-navigation3-android:2.11.0`,
  again the artifact the app ships today.
  <https://repo1.maven.org/maven2/org/jetbrains/androidx/lifecycle/lifecycle-viewmodel-navigation3/maven-metadata.xml>
- JetBrains Navigation 3 guide. Only `navigation3-ui` has a separate CMP implementation. On non-JVM
  targets `rememberNavBackStack` needs the overload that takes a `SavedStateConfiguration` with a
  polymorphic `SerializersModule`, because reflection serialization is Android only. A sealed
  interface plus `subclassesOfSealed` is the documented single-module pattern.
  <https://kotlinlang.org/docs/multiplatform/compose-navigation-3.html>
  `subclassesOfSealed` is present in the cached kotlinx-serialization-core 1.11.0.
- iOS back gesture. CMP 1.10 moved back handling to Navigation Event and deprecated
  `PredictiveBackHandler` in favour of `NavigationBackHandler`. On iOS the start edge pan is always
  bound to Back, and `EndEdgePanGestureBehavior` (default `Disabled`) controls the other edge.
  <https://kotlinlang.org/docs/multiplatform/whats-new-compose-110.html>
  The CMP 1.12.1 iOS `ui` klib in the local cache contains `UIKitBackGestureRecognizer`,
  `EdgePanGestureBehavior` and `endEdgePanGestureBehavior`, so this ships in our pinned version.
  Navigation Event maps an iOS gesture from the left to Back.
  <https://developer.android.com/guide/navigation/navigation-event>
- Android predictive back. `NavDisplay` consumes Navigation Event back events and plays its
  predictive pop transition. Apps targeting API 36 or higher get predictive back by default, and
  `:app` targets 37, so no manifest flag is needed.
  <https://developer.android.com/guide/navigation/navigation-event/handle-back>
- Already present. The iOS klib manifest of `:feature:discover` already lists
  `org.jetbrains.androidx.navigationevent:navigationevent-compose`, `org.jetbrains.compose.ui:ui-backhandler`,
  `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose` and `lifecycle-viewmodel-savedstate`
  as transitive dependencies of CMP 1.12.1. Option (a) adds only two small iOS klibs.

## Options

### (a) JetBrains Navigation 3 for Compose Multiplatform

Coordinates `org.jetbrains.androidx.navigation3:navigation3-ui:1.1.2` and
`org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3:2.11.0`, with the existing
multiplatform `androidx.navigation3:navigation3-runtime:1.1.7` in `commonMain`.

- Per-tab stacks, reselect and Back to Discover. Yes. `TabNavigator` moves unchanged, and the
  current `rememberDecoratedNavEntries` per tab plus `NavDisplay(entries = ...)` pattern moves as is.
- iOS edge swipe. Yes, `NavDisplay` listens to Navigation Event, which CMP feeds from the UIKit
  start edge recognizer.
- Android predictive back. Unchanged from today, same Google binaries.
- Process death on Android. Yes, `rememberNavBackStack` saves through savedstate. Moving to the
  `SavedStateConfiguration` overload also removes reflection on Android.
- Hilt at the edge. Yes. The ViewModel decorator gives each entry a `ViewModelStoreOwner`.
  Android entries call `hiltViewModel()` inside it as today. iOS entries hold plain controllers in
  a `viewModel { }` holder, which fixes audit risk 7 (device state reset on tab switch).
- New dependencies. Two iOS klibs. Zero new Android binaries.
- `commonTest`. `TabNavigator` keeps only `NavKey`, which is in the multiplatform runtime.
- Cost. Keys need one sealed interface and one `SavedStateConfiguration`. The JetBrains version must
  move in lockstep with CMP (1.1.2 now, 1.2.x with a later CMP).

### (b) JetBrains navigation-compose (nav2 style)

Coordinates `org.jetbrains.androidx.navigation:navigation-compose`. The version paired with CMP
1.12.1 is `2.10.0-beta01` per the release notes (Maven Central has up to 2.10.0-rc01, latest stable 2.9.2).

- Per-tab stacks need `saveState` and `restoreState` on `navigate`, and "Back returns to Discover"
  needs custom `popUpTo` rules. The state lives inside `NavController`, not in a plain list, so the
  rules are harder to test without Compose.
- Android would move off Navigation 3, a rewrite of working code and a likely regression.
- The paired version is a beta. Rejected.

### (c) Own small host over `TabNavigator` in commonMain

A `when (key)` display plus `SaveableStateHolder`, a hand written per-entry `ViewModelStore` that is
cleared on pop, a custom `Saver` for the stacks, and `NavigationBackHandler` for Back.

- No new library, and `TabNavigator` is already ready for it.
- Re-implements what `rememberViewModelStoreNavEntryDecorator`, `rememberNavBackStack` and
  `NavDisplay` already do, including predictive pop animation on Android, which would be lost or
  rebuilt. ViewModel store clearing and saved state are the usual sources of leaks and restore bugs.
- Android would drop Navigation 3 or keep two hosts.
- Kept as the fallback only.

## Decision

Option (a). Use JetBrains Navigation 3 UI 1.1.2 and JetBrains `lifecycle-viewmodel-navigation3`
2.11.0 in a new `:feature:shell` module, with the multiplatform `androidx.navigation3:navigation3-runtime`
1.1.7 in `commonMain`. Both apps host one `AppShell` from that module.

Reasons. It is the set CMP 1.12.1 names, it resolves on Android to the exact artifacts the app runs
today, it brings the iOS start edge swipe and Android predictive back through one Navigation Event
path, and it keeps `TabNavigator` as the single, Compose free rule set tested in `commonTest`.

Fallback. If the iPhone run shows `NavDisplay` broken on iOS, switch only the iOS host to option (c).
`TabNavigator`, the keys and the tests stay, since they depend on nothing but `NavKey`.

## Consequences

- One shell, one bar, one set of rules on both apps. F03 and F04 close together.
- All navigation keys become `@Serializable` subtypes of one `sealed interface AppKey : NavKey` in
  `:feature:shell`. A key added elsewhere must join that hierarchy or be registered explicitly.
- `rememberNavBackStack` is always called with `AppNavConfig`, on Android too.
- Screen content stays per platform behind an entry slot until each screen moves to common. Android
  entries keep `hiltViewModel()`. Common screens take plain controllers, and iOS keeps them in a
  `viewModel { }` holder scoped to the entry. No Hilt in common.
- Back handlers inside screens use `NavigationBackHandler` from `navigationevent-compose`, never
  `androidx.activity.compose.BackHandler`. Inner handlers win over `NavDisplay` because Navigation
  Event dispatches last in, first out.
- Version lockstep. `jetbrainsNavigation3` and `jetbrainsLifecycle` follow the CMP release notes.
  `nav3Core` must equal the runtime that the JetBrains UI POM names (1.1.7 for 1.1.2).
  Upgrading CMP for maplibre also moves these.
- iOS has no process death restore in the Android sense. iOS state is saved while the scene lives
  and starts on Discover after a cold launch. This matches the start tab rule.
- Simulator results do not prove the gesture. The iPhone edge swipe and the Android predictive back
  need real phone runs recorded in `docs/status.md`.

## Migration steps

1. Add the catalog entries below. No version bump of anything already pinned.
2. Create `:feature:shell` with `opensensor.kmp.compose-feature` and the Kotlin serialization
   plugin. `commonMain` gets `api(libs.androidx.navigation3.runtime)`,
   `api(libs.jetbrains.navigation3.ui)`, `api(libs.jetbrains.lifecycle.viewmodel.navigation3)`,
   `implementation(libs.jetbrains.lifecycle.viewmodel.compose)` and `api(project(":ui:designsystem"))`.
3. Move `NavigationKeys.kt` to `:feature:shell` commonMain as `sealed interface AppKey : NavKey`
   with the same nine keys, and add
   `val AppNavConfig = SavedStateConfiguration { serializersModule = SerializersModule { polymorphic(NavKey::class) { subclassesOfSealed<AppKey>() } } }`.
4. Move `TopTab` and `TabNavigator` to `:feature:shell` commonMain without behaviour changes. Move
   `TabNavigatorTest` to `commonTest` on `kotlin.test`, and add a test that every `AppKey`
   round trips through `AppNavConfig`.
5. Write `AppShell(entries: EntryProviderScope<NavKey>.(TabNavigator) -> Unit, bar slot)` in common.
   It holds `rememberNavBackStack(AppNavConfig, root)` per tab, the saveable tab, the per-tab
   `rememberDecoratedNavEntries` with the saveable state and ViewModel decorators, and
   `NavDisplay(entries = visibleTabs..., onBack = { nav.pop() })`, exactly as `Navigation.kt` does now.
6. Android. `MainNavigation` becomes the entry slot for `AppShell`. Entries keep `hiltViewModel()`
   and `LanDiscoveryEffect` stays in `:app`. Remove the direct `androidx-navigation3-ui` and
   `androidx-lifecycle-viewmodel-navigation3` lines from `app/build.gradle.kts`, since the shell
   brings the same artifacts.
7. iOS. `AppRoot` hosts `AppShell`. Discover, My devices (today's `CompanionScreen`) and Settings
   become entries. `DiscoverController` and later device controllers move into `viewModel { }`
   holders so they survive tab switches and clear on pop.
8. F12. Replace `BackHandler` with `NavigationBackHandler` so the sheet collapses on Back and on
   the iOS swipe. Done in #12 at the platform edge (`DiscoverRoute.kt` on Android, `DiscoverEntry`
   in iOS `AppRoot.kt`). Moving the one handler into `:feature:discover` commonMain is still the
   target, so the two copies cannot drift.
9. F03, F05, F34. One bottom bar in the shell from `:ui:designsystem` (curved card indicator,
   brand blue only for selection), then `DeviceSectionTabs` and the device top bar.
10. Gate. `./gradlew --no-daemon verify`, the iOS simulator build, then phone runs. iPhone start
    edge swipe pops a stack and returns a tab root to Discover. Android predictive back preview
    shows the previous entry. Android process death (Don't keep activities, or kill in background)
    restores the open tab and its stack.

## Catalog entries for `gradle/libs.versions.toml`

```toml
[versions]
# Multiplatform Navigation 3 UI, paired with CMP 1.12.1 (release notes). Pins nav3Core 1.1.7.
jetbrainsNavigation3 = "1.1.2"
# Multiplatform lifecycle, paired with CMP 1.12.1. Android resolves to androidx.lifecycle 2.11.0.
jetbrainsLifecycle = "2.11.0"

[libraries]
jetbrains-navigation3-ui = { module = "org.jetbrains.androidx.navigation3:navigation3-ui", version.ref = "jetbrainsNavigation3" }
jetbrains-lifecycle-viewmodel-navigation3 = { module = "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-navigation3", version.ref = "jetbrainsLifecycle" }
jetbrains-lifecycle-viewmodel-compose = { module = "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "jetbrainsLifecycle" }
```

The existing `androidx-navigation3-runtime` entry (`androidx.navigation3:navigation3-runtime`,
`nav3Core = "1.1.7"`) is reused in `commonMain` as is. `androidx-navigation3-ui` and
`androidx-lifecycle-viewmodel-navigation3` can be deleted once `:app` no longer names them.
