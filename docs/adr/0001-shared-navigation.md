# ADR 0001. Shared shell and navigation

- Status. Accepted (lead agent decision, owner review pending)
- Date. 2026-10-09
- Issue. #12

## Decision

Both apps host one `AppShell` from `:feature:shell` commonMain. It uses JetBrains Navigation 3 UI
(`jetbrainsNavigation3`) and JetBrains `lifecycle-viewmodel-navigation3` (`jetbrainsLifecycle`),
with the multiplatform `androidx.navigation3:navigation3-runtime` (`nav3Core`) in `commonMain`.
On Android these coordinates resolve to the same Google artifacts, so predictive back is
unchanged. On iOS `NavDisplay` takes the start edge swipe through Navigation Event.

`AppShell` holds one `rememberNavBackStack(AppNavConfig, root)` per tab, decorated with the
saveable state and ViewModel store decorators, so a device page left open keeps its state while
another tab shows. `TabNavigator` holds the rules and depends only on `NavKey`. Reselecting a tab
pops it to its root, Back pops the active stack and then returns a tab root to Discover, and at the
Discover root Back closes the app. `TabNavigatorTest` and `AppNavConfigTest` cover them in
`commonTest`.

## Rejected options

- JetBrains navigation-compose (nav2 style). Per-tab stacks need `saveState` and `popUpTo` rules
  inside `NavController`, which are hard to test without Compose, and the version paired with CMP
  was a beta.
- An own host over `TabNavigator`. It re-implements ViewModel store clearing, saved state and the
  predictive pop animation. Kept as the iOS fallback only.

## Rules that follow

- Every navigation key is a `@Serializable` subtype of `sealed interface AppKey : NavKey` in
  `:feature:shell`. `rememberNavBackStack` is always called with `AppNavConfig`, on Android too.
- Android entries call `hiltViewModel()`. Common screens take plain controllers, which iOS keeps
  in a `viewModel { }` holder scoped to the entry. No Hilt in common.
- Back handlers inside screens use `NavigationBackHandler` from `navigationevent-compose`, never
  `androidx.activity.compose.BackHandler`. Inner handlers win because Navigation Event dispatches
  last in, first out. The Discover sheet collapse lives in `DiscoverScreen` this way.
- `jetbrainsNavigation3` and `jetbrainsLifecycle` move in lockstep with Compose Multiplatform, per
  its release notes, and `nav3Core` must equal the runtime the JetBrains UI POM names.
- iOS saves state while the scene lives and starts on Discover after a cold launch.
- Simulator results do not prove the gesture. The iPhone edge swipe, Android predictive back and
  Android process death restore need phone runs recorded in [status.md](../status.md).
