# Android UI

Open root `docs/screens.md` for navigation and screen roles; open
`docs/history-pipeline.md` for metric meaning. Composables render ViewModel
state and dispatch intent. BLE, file, Room and long-running work belong in the
data layer. Reuse shared components in `components/` when multiple screens
need the same behavior.

- Known devices are devices the owner connected to; a nearby advertisement is
  only a scan result. Forget must stay forgotten until explicit reconnection.
- Navigation 3 entries own screen ViewModels. Load once when an entry opens;
  collect observable Room/repository state with lifecycle awareness. Refresh
  only for explicit user action, reconnection, or changed request keys. A
  composable recomposition must never start transport/file work. Cancel screen
  jobs on pop; process-owned controllers and WorkManager may outlive the screen.
- Simple mode must explain the current reading and archive state without
  Debug. Advanced mode only reveals extra information; it must not alter sync.
- Distinguish stale, unavailable and unverified readings from zero. Keep
  archived history usable when a device is disconnected or a worker owns it.
- Device-changing actions require an explanation, and destructive ones require
  confirmation. Preserve the explicit reboot path in root `AGENTS.md`.

## Design system and detail levels

- Use the tokens in `theme/` (`BrandTokens`, `brandColorScheme`, `BrandShapes`, `brandTypography`)
  and the components in `components/Brand.kt`. No raw colours or font families in screens.
  Values come from the website's `lib/brand.ts` (`sensing`), pinned by `BrandTokensTest`.
- Band colours are a scientific metric scale. Never restyle them. Put text on a band with
  `onColor()` and band-coloured text with `textColorOnSurface()`.
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
  Keep `assets/licenses/` with them.
- User-facing copy lives in `res/values/strings.xml`, with plurals for counts. No em dashes,
  colons or semicolons.
