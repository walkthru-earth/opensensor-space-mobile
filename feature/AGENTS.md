# Shared screens

Every module here is Compose Multiplatform for Android and iOS (`opensensor.kmp.compose-feature`).
Open `../docs/screens.md` for navigation and screen roles, `../docs/history-pipeline.md` for metric
meaning and `../ui/AGENTS.md` for the design system, copy and permission rules.

| Module | Screen |
| --- | --- |
| `shell/` | `AppShell` with the one bottom bar, `AppKey` and the per-tab back stacks of `TabNavigator` |
| `discover/` | The public H3 map and its cell sheet |
| `devices/` | My devices, the device rows and Collect all |
| `today/` | The Station page of one sensor (`TodayScreen`) |
| `history/` | History charts, calendar and the Track card |
| `files/` | The file list and the file Metrics inspector |
| `device/` | Device settings, the config cards, location and site |
| `setup/` | The setup wizard |
| `settings/` | App settings and storage controls |
| `debug/` | The Developer level Debug screen |

## Rules

- A screen is a stateless `XScreen(state, onAction)` and a plain `XController` that holds its state
  and actions. A controller takes its ports as constructor parameters. No Hilt, no `Context` and no
  ViewModel in these modules. Android wraps a controller in a ViewModel under
  `app/src/main/java/space/opensensor/ui/`, and iOS builds it in `ios/shared/src/iosMain`.
- Transport, file and Room work stays out of composables. A recomposition never starts it. A
  composable renders state and dispatches intent.
- Sensor semantics come from the metric registry and the thing's template. Read labels, units and
  bands from there, never from literals, and never fabricate a default. Capability checks come
  from `DeviceCapabilities`, never from schema strings. Distinguish stale, unavailable and
  unverified readings from zero.
- Copy lives in each module's `src/commonMain/composeResources/values/strings.xml`, with its
  `values-ar` copy and plurals for counts. State code holds a `UiText` and the composable edge
  resolves it. Typed UI values (`GroupTitle`, `RawError`, `FailReason`) are mapped in the screen,
  never rendered as English. `PresentationLiteralsTest` fails on an English literal in a shared
  presentation source.
- Design Simple first. Gate technical facts with `level.detailed` or `level.developer`
  (`DetailLevel` in `:core:settings`) and list new ones in the level table in `docs/screens.md`.
  Simple speaks in moods and never names the guideline body (`SimpleCopyTest`). A level only
  reveals information, it never alters sync. Debug is reachable only at Developer.
- The clock and the Wi-Fi token are automatic, so their surfaces are Developer only (the Today
  CLOCK card, the token line of the auto sync card, the "no token" tag in device rows, the token
  sync endings).
- Screens never draw a bottom bar. The Station page is the hub of one sensor, and History, Files,
  Device settings and Debug are plain pushes over it with a back arrow. Each thing action has one
  home, never a second button. A screen that takes Back first uses `NavigationBackHandler`, never
  the Android `BackHandler`.
- Device-changing actions carry an explanation in the card's info sheet, and destructive ones need
  a short confirmation. Keep the explicit reboot path of the root `AGENTS.md`.
- Never request a permission at launch or from composition. Ask from the tap that needs it through
  `:ui:permissions`.
- Read `rememberNowMillis()` only in leaf texts (`TickingText`).
- Name components after what they show in general (`MetricHeroCard`, `SignalBars`, `MascotGuide`).
  Air specific presenters stay in the files that name the family, such as `FamilyPresenters.kt`.
- Platform seams are small `expect` and `actual` declarations in `Platform.kt` of a module, with
  the actuals in `androidMain` and `iosMain`. Build a feature once in `commonMain`.
- Shared tests live in `commonTest` and run on the host in `verify` and on the simulator in
  `verifyIos`.
