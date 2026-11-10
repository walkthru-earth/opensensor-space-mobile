# Android screen hosts

The screens are shared Compose in `feature/` and `ui/`. Read `feature/AGENTS.md` for the screen
rules (controllers, detail levels, copy, navigation) and `ui/AGENTS.md` for the design system,
copy lint and permissions. This package only hosts them on Android. A stateful `XRoute` owns the
ViewModel, `CollectEvents`, launchers, permissions and dialogs and hands state to the shared
stateless `XScreen(state, onAction)`. BLE, file, Room and long-running work belong in the data
layer. `components/` keeps only what still needs app types or `R.string`.

- Known devices are devices the owner connected to; a nearby advertisement is
  only a scan result. Forget must stay forgotten until explicit reconnection.
  A confirmed Forget runs in `@ApplicationScope` through `forgetWhenFree`, which waits until the
  link is not busy and tries the forget `FORGET_ATTEMPTS` times in total. Every try but the last
  is quiet, so the user sees Busy once and leaving Today never drops the forget.
- Setup retries hide the failure they replaced. `SetupViewModel` keeps that `staleFailure`, and
  `skipStale` drops the same instance (`===`) while `connectsRunning` is above zero or no new
  `connectFailure` was recorded, so a retry that fails the same way still shows.
- Setup never asks for Local Network and its Find is Bluetooth only. A Wi-Fi passphrase goes over
  Bluetooth only, so the shared `ReconnectOverBluetooth` shows on a ready link that is not BLE and
  the route answers it with `reconnectOverBluetooth()` behind Bluetooth access.
- One bottom bar (Discover, My devices, Settings) lives in the shared `AppShell` (`:feature:shell`),
  which `Navigation.kt` hosts with the Android entries, and Discover is always the start tab.
  Keys (`AppKey`) and per-tab back stack rules (`TabNavigator`) live in `:feature:shell`.
- Navigation 3 entries own screen ViewModels. Load once when an entry opens;
  collect observable Room/repository state with lifecycle awareness. Refresh
  only for explicit user action, reconnection, or changed request keys. A
  composable recomposition must never start transport/file work. Cancel screen
  jobs on pop; process-owned controllers and WorkManager may outlive the screen.
- Never request a permission at launch or from composition. Use `rememberBluetoothAccess()`,
  `rememberLocalNetworkAccess()` or `rememberNotificationPermission()` and call them from the tap
  that needs them, with `AccessDialogs` beside the screen. The decisions and the trigger table
  live in the shared `:ui:permissions` module. `permissions/` here only launches the system
  dialogs. Timing table in `docs/screens.md`.
- Android only copy lives in `res/values/strings.xml`, with plurals for counts. Shared copy lives
  in the Compose resources of each shared module. Data-layer snackbars arrive as `DeviceMessage`
  codes and are mapped in `components/DeviceMessages.kt`.
- Android notifications use coloured large peacock portraits and a monochrome peacock small icon
  for system tinting.
- Routes keep light and dark previews at Simple and Developer (`ScreenPreviews.kt`).
