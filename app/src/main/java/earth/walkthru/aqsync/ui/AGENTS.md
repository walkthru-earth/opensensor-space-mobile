# Android UI

Open root `docs/screens.md` for navigation and screen roles; open
`docs/history-pipeline.md` for metric meaning. Composables render ViewModel
state and dispatch intent. BLE, file, Room and long-running work belong in the
data layer. Reuse the shared components in `:ui:designsystem` (cards, buttons, rows, info sheet,
signal bars, the bottom bar, `AppIcons`), shared with iOS. `components/` keeps only what still needs
app types or `R.string`.

- Known devices are devices the owner connected to; a nearby advertisement is
  only a scan result. Forget must stay forgotten until explicit reconnection.
- One bottom bar (Discover, My devices, Settings), the shared `BrandBottomBar`, lives at the app root in `Navigation.kt`, and
  Discover is always the start tab. Screens never draw a bottom bar. Device sections use
  `DeviceSectionTabs` under the top bar. Per-tab back stack rules live in `TabNavigator`.
- Navigation 3 entries own screen ViewModels. Load once when an entry opens;
  collect observable Room/repository state with lifecycle awareness. Refresh
  only for explicit user action, reconnection, or changed request keys. A
  composable recomposition must never start transport/file work. Cancel screen
  jobs on pop; process-owned controllers and WorkManager may outlive the screen.
- Simple mode must show the current reading and archive state without
  Debug. Advanced mode only reveals extra information; it must not alter sync.
- Distinguish stale, unavailable and unverified readings from zero. Keep
  archived history usable when a device is disconnected or a worker owns it.
- Device-changing actions require an explanation (in the card's info sheet), and destructive ones
  require a short confirmation. Preserve the explicit reboot path in root `AGENTS.md`.
- No paragraph copy in the default view. A card is a title, its value or state, and one primary
  action, with at most one short supporting line. Explanations go behind `InfoButton` through
  `CardHeader(title, info = InfoContent(...))`, radio option descriptions included
  (`docs/screens.md`, Copy and the info affordance).
- Never request a permission at launch or from composition. Use `rememberBluetoothAccess()`,
  `rememberLocalNetworkAccess()` or `rememberNotificationPermission()` and call them from the tap
  that needs them, with `AccessDialogs` beside the screen. The decisions and the trigger table
  live in the shared `:ui:permissions` module. This package only launches the system dialogs.
  Timing table in `docs/screens.md`.

## Design system and detail levels

- Use the tokens in `:ui:designsystem` (`BrandTokens`, `brandColorScheme`, `BrandShapes`, `rememberBrandTypography`)
  and its components (`BrandCard`, `PillButton`, `CardHeader`, `StatLine`, ...). Icons come from
  `AppIcons` (vendored Material vectors), never `material-icons-extended`. No raw colours or font families in screens.
  Values come from the website's `lib/brand.ts` (`sensing`), pinned by `BrandTokensTest`.
- Band colours are the WHO 2021 steps from `:core:airquality` (`WhoStep.color()` in `theme/Color.kt`,
  the one mapping). Never restyle them. Put text on a band with `onColor()` and band-coloured text
  with `textColorOnSurface()`.
- Design Simple first. Gate technical facts with `level.detailed` or `level.developer` and list
  new ones in the audit table in `docs/screens.md`. Debug is reachable only at Developer.
- Name components after what they show in general (`MetricHeroCard`, `SignalBars`,
  `MascotGuide`). Read labels, units and bands from the metric registry, not literals.
- Large screens are a stateful `XRoute` (ViewModel, `CollectEvents`, launchers, permissions,
  dialogs) and a stateless `XScreen(state, onAction)`, one file per card, with light and dark
  previews at Simple and Developer.
- Read `rememberNowMillis()` only in leaf texts (`TickingText`). Capability checks come from
  `DeviceCapabilities`, never schema strings.
- The peacock is for welcome, empty and success states only. Fonts are OFL (Quicksand, Cairo).
  Keep `ui/designsystem/src/commonMain/composeResources/files/licenses/` with them.
- User-facing copy lives in `res/values/strings.xml`, with plurals for counts. No em dashes,
  colons or semicolons (`CopyLintTest` in `:ui:strings` checks). Data-layer snackbars arrive as
  `DeviceMessage` codes and are mapped in `components/DeviceMessages.kt`. Typed UI values
  (`GroupTitle`, `RawError`, `FailReason`) are mapped in the screen, never rendered as English.
