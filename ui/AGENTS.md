# Shared UI modules

Compose Multiplatform for Android and iOS (`opensensor.kmp.compose-feature`). The screens that use
these modules are in `../feature/`, see `../feature/AGENTS.md`. Open `../docs/screens.md` for the
copy, permission timing and floating control rules in full.

| Module | Owns |
| --- | --- |
| `designsystem/` | `BrandTokens`, `brandColorScheme`, `BrandShapes`, `rememberBrandTypography`, the components (`BrandCard`, `PillButton`, `CardHeader`, `StatLine`, `InfoButton`, `SignalBars`, the bottom bar), `AppIcons`, `FitText`, `FloatingControl`, the peacock art, `ColorContrast` and the credential capture guard |
| `strings/` | `UiText`, the shared string catalogue and the copy gates (`CopyLintTest`, `SemanticsLintTest`, `PresentationLiteralsTest`, `SimpleCopyTest`, `TranslationsTest`, `StringsMatchAndroidTest`) |
| `permissions/` | `RuntimeAccess`, `PermissionFlow`, the trigger table in `PermissionTriggers` and `AccessDialogs` |
| `map/` | `BaseMap`, `CellPicker`, `NearMe`, the place search, the map cache and the bundled basemap styles and place list |

## Design system

- Use the tokens and components of `:ui:designsystem`. No raw colours or font families in a screen.
  Icons come from `AppIcons` (vendored Material vectors), never `material-icons-extended`. Token
  values come from the website's `lib/brand.ts` (`sensing`), pinned by `BrandTokensTest`.
- Brand blue marks the selected or primary thing only. Everything else is neutral surface and text.
  Never use purple.
- Band colours are the WHO 2021 steps from `:family:air` (`WhoStep.color()` in `ColorContrast.kt`,
  the one mapping). Never restyle them. Put text on a band with `onColor()` and band coloured text
  with `textColorOnSurface()`.
- Floating controls follow the rules at the top of `FloatingControls.kt`. One layer over content,
  compact at rest and growing into their options while in use, curved cards and never circles,
  touch targets of at least `FloatingControl.Size`.
- A label stays on one line and shrinks to fit through `FitText`, with a floor at its size at font
  scale 1.0. Body text wraps between words only. A word is never split across lines, in English or
  Arabic.
- Use the reference peacock for live and archived air moods and for welcome, guide, empty,
  progress, unavailable and success states, through `PeacockIcon`, `AirQualityPeacock` and
  `MascotGuide`. Keep the full artwork for welcome and static compact portraits elsewhere, at
  least 48 dp. Poor air uses a serious masked peacock, and the most severe mood shields its beak
  with feathers. Missing values use Confused and keep their translated explanation. Images beside
  translated labels are decorative (`contentDescription = null`). All character art ships in
  Compose resources, with no Internet asset fetch.
- Fonts are OFL (Quicksand, Cairo). Keep
  `designsystem/src/commonMain/composeResources/files/licenses/` with them.
- A field that takes a PIN or a Wi-Fi passphrase sits under `rememberCredentialCaptureGuard`, so
  the secret stays out of screen captures.

## Copy

- No paragraph copy in the default view. A card is a title, its value or state and one primary
  action, with at most one short supporting line. Explanations go behind `InfoButton` through
  `CardHeader(title, info = InfoContent(...))`, radio option descriptions included.
- User-facing copy has no em or en dashes, no colons in sentences and no semicolons. `CopyLintTest`
  checks every Android and Compose `strings.xml`, the `values-ar` copies included.
- Shared copy lives in each module's `src/commonMain/composeResources/values/strings.xml` and in
  `:ui:strings`. `app/src/main/res/values/strings.xml` keeps the copy only Android shows, and
  `StringsMatchAndroidTest` holds a key that both have to the same words.
- Every custom tap target names its role (`SemanticsLintTest`).

## Permissions

- Never request a permission at launch or from composition. A screen takes a `RuntimeAccess` and
  calls it from the tap that needs it, with `AccessDialogs` beside the screen. `PermissionFlow`
  decides whether to run, explain, ask or send the user to system settings, and
  `PermissionTriggers` lists the one trigger of each permission.
- Location is foreground and approximate only. The fix moves the map and is never stored, logged
  or sent (`NearMe`, `CellPicker`).

## Map

- The map draws H3 cells, never the coordinates of a device. Basemap tiles, glyphs and sprites are
  anonymous read-only GETs under the Settings network switch, and the styles and the place list
  are bundled (`../tools/AGENTS.md` names their generators).
