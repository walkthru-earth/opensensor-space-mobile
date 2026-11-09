# Setup wizard and config forms

Part of [Thing plugins](README.md). 2026-10-27, updated 2026-11-09 for #272. Sections 1 to 3 match the code.

## 1. Today

- `:feature:setup` holds the wizard (`FIND → NAME → LOCATION → SITE → WIFI → DONE`, slice 3 of [2026-10-12-stations-ux-design.md](../2026-10-12-stations-ux-design.md)). `SetupFlows.BOARD` is the `opensensor-board` step list, and `SetupController` walks it over a `SetupPort` on each platform. Add sensor on Discover opens it full screen over My devices (`SetupKey`). A new sensor tapped under Nearby on My devices opens it too, with that sensor picked (`SetupKey.pick`), so pairing leads to Name and never straight to Today. My devices also opens it by itself, at most once per foreground session, when `ThingSetupWatch` says exactly one sensor needs setup (advert bit 6, or page 1 for firmware without the flag, see [sync-engine.md](../../../sync-engine.md#thing-setup)). That open lands on Find with the sensor offered first (`SetupKey.offer`), and nothing connects until the user taps it. A tap on "Finish setup" resumes an added sensor at Location (`SetupKey.resume`). Backing out leaves a "Finish setup" chip or a "needs setup" tag on its row instead of opening again. With two or more sensors asking, one count chip opens Find and the wizard never opens by itself. Pairing happens in the OS dialog when a sensor is picked on Find. The known row is written only after INFO passed the identity check, so a failed pairing leaves no entry. INFO with a `proto` below 3 closes the link before that check on both platforms, and Find shows the firmware update message.
- The nickname may be blank, which shows the advertised name, and is at most 32 characters after trimming. It and the pending setup flag live in the phone settings store (`SettingsRepository`), not in `KnownDevice`, so they never reach the sensor or the public feed. Stations cards do not show them yet.
- Location, Site and Wi-Fi pages reuse `DeviceLocationForm`, `DeviceSiteForm` and `WifiCard` over the same `DeviceConfigController` as Device settings. The Wi-Fi page only joins a network (`wifi.on`, `wifi.ssid`, `wifi.psk`). Wi-Fi sync and its token follow by themselves on the next Bluetooth connection, so setup never asks for local network and shows no token.
- Location is required (`SetupStep.Location(required = true)`), since a Thing cannot be set up without a fixed or moving location. It has no Skip, and Next stays off until the board confirms a valid `location.cell`. The cell is tapped on the `CellPicker` area map of `:ui:map`, which phone location and place search only move. Country stays optional. Done, which clears the pending flag, needs every required page, so a cell lost after Location keeps Done closed. A resumed run opens at Location. Wi-Fi can be skipped.
- Site (`SetupStep.Site`) follows Location and is optional, so it has Skip. It asks for the owner name, organisation, link, description and deployment ID ([device-location.md](../../../device-location.md#site)). Skip moves on and sends nothing. Next with no change moves on too. Next with changes is the explicit save. It is off while a value breaks a firmware rule, sends only the changed values in their own `SET_CONFIG`, and moves on only once pages 5 and 6 read back the same. A refusal or a different readback stays on the page with its reason. A board whose page 5 answers `ERROR` 1, or has no `owner`, has no site keys. `SetupController.onSiteSupport(false)` then leaves Site out of Next and Back, and moves on if Site is showing. While page 5 has not answered, Site shows with its fields off. Setup does not send `thing.mobility`. The board firmware defaults to fixed, and a moving kind will need its own way to satisfy Location.
- Back goes one page back. On Name after pairing it asks "Leave setup?" instead, and system back (the Android gesture or the iOS edge swipe) asks on every page after pairing, like the toolbar close. Leaving keeps the sensor added with setup pending, and picking it again on Find runs setup again.
- Board-specific parts. Find uses the BLE service UUID scan. Pairing has random, fixed and none modes. Wi-Fi join and the automatic LAN token go over Bluetooth only. The sensor sticker is Plantower only.
- Device settings are a fixed list of cards in `DeviceConfigSection.kt`. The sensor card is gated by one column name.
- `SET_CONFIG` keys are rows of `ConfigFields` in `core/protocol/.../ConfigField.kt`, each with its secret flag and gate. `SECRET_KEYS` is built from it, and `ConfigFields.merge` lets a schema only tighten a known key. `ConfigPatch.Builder` keeps one typed method per key with firmware limits and the closed `ConfigProblem` enum. `DeviceConfigController.confirm()` sends every confirmed write, and its `DeviceConfigDialog.FIELDS` table maps each gated key to its dialog, checked by a test.

## 2. Step graph per kind

Each `ThingKind` declares an ordered list of steps in `SetupFlows`. Each step is a shared Compose page in `:feature:setup`, and first setup and later settings reuse the same forms.

```kotlin
sealed interface SetupStep { val id: String; val skippable: Boolean; val required: Boolean; val isPage: Boolean }
// Permission(UserAction), Find, Pair, Name, Location(required), Site, Fields(id, keys), Done
```

- A Permission step only binds `PermissionTriggers` to the next page's tap. It is never a wall. Permission and Pair are not pages.
- Sharing is never a step. Upload is turned on only from that Thing's settings (`devices-and-discovery.md`, AGENTS.md). Done may at most link to the setting.
- `SetupController` walks the pages over a `SetupPort` (`nearby`, `startFind`, `stopFind`, `connect`, `link`, `nickname`, `saveNickname`, `setPending`). `SetupLink` is the link as the platform sees it, and `SetupFailure` gives one message per failed phase (`UPDATE_FIRMWARE`, `PAIRING`, `UNREACHABLE`, `BLUETOOTH`).
- Find on iOS could later use [AccessorySetupKit](https://developer.apple.com/documentation/accessorysetupkit) (the iOS target is 18.5). Find on Android could use [Companion Device Manager](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing) `associate()`, which the app already uses for presence.

## 3. Fields from data

A small closed set of field types, not full JSON Schema. No mature Compose or KMP JSON Schema renderer exists, and Home Assistant selectors show a closed set scales well. These are the types in `core/protocol/.../ConfigField.kt`.

```kotlin
sealed interface FieldType   // Text(minBytes, maxBytes), Digits(length), IntBetween(min, max), Choice(values),
                             // Toggle, Action, H3Cell, Country
enum class Applies { IMMEDIATELY, NEXT_SAMPLE, NEXT_BOOT, ONCE }
sealed interface Confirm     // Never, Always, When(value)

data class ConfigField(
    val key: String,
    val type: FieldType,
    val applies: Applies,
    val secret: Boolean = false,     // Bluetooth only, never in a log or frame
    val confirm: Confirm = Confirm.Never,
    val disrupts: Boolean = false,   // waits for a quiet link, DeviceConfigUiState.canDisrupt
)
```

- Placement. `ConfigField` and the `ConfigFields` allowlist hold only wire facts and live in `:core:protocol`. The dialogs live in `:feature:device` as `DeviceConfigDialog.Field` rows in `DeviceConfigDialog.FIELDS`, so no core module reaches into a feature or settings module.
- Confirmations come from data. `ble.pair` is `Confirm.When("none")`. Clearing bonds and rotating the token are `Confirm.Always` and `disrupts`. Reboot stays its own action. `DeviceConfigController.confirm()` stays the single place that sends a confirmed write.
- A key that needs a confirmation but has no `DeviceConfigDialog.FIELDS` row fails the table test in `:feature:device`.

| Key | `Confirm` | `DeviceConfigDialog` | `DeviceConfigChange` |
| --- | --- | --- | --- |
| `ble.pair` | `When("none")` | `PairingNone` | `PAIRING` |
| `ble.clear_bonds` | `Always`, disrupts | `ForgetBonds` | `BONDS_CLEARED` |
| `lan.rotate_token` | `Always`, disrupts | `RotateToken` | none, the token is refetched |
| reboot, an action and not a field | own dialog | `Reboot` | none |

- Secrets are marked in the table. A secret field is never logged, drafted, sent over LAN or used as a default. `SECRET_KEYS` is built from it. The redaction tests stay.
- Labels stay in the app.
- App floor rules win over any schema. `ConfigFields.merge` lets a schema make a known key stricter, never looser, and drops any key the app does not know.
- Next, an optional `config` block on the firmware #117 module and board manifests, copied into the template, so firmware owns limits and gates and the app stops duplicating them. The typed builders stay as tested shortcuts.

## 4. Phone and health flows

Phone Thing. Pick sensors (barometer, optional mic), mic calibration, session rules, location, review. No pairing or Wi-Fi steps.
- Recording is an explicit session, since neither platform gives background sampling.
- The mic records dBFS marked uncalibrated until an offset exists. Where it is stored is stac-opensensor#2 section C.
- Precise location would be a new `AccessKind`, asked only from Start recording. Owner question 5.

Health. Pick read types, the system permission sheet, then a "stays on this phone" note. No location, sharing or publish steps.

## 5. Prior art

- [Home Assistant config flow](https://developers.home-assistant.io/docs/config_entries_config_flow_handler), [options flow](https://developers.home-assistant.io/docs/config_entries_options_flow_handler), [selectors](https://github.com/home-assistant/core/blob/dev/homeassistant/helpers/selector.py).
- [Matter commissioning](https://developers.home.google.com/matter/primer/commissioning). Fixed phases with one failure message per phase.
- [ESPHome](https://esphome.io) and [Improv Wi-Fi](https://www.improv-wifi.com/ble/). Host-side schema validation, Wi-Fi provisioning over BLE as one small state machine.
- [JSON Forms](https://github.com/eclipsesource/jsonforms). Data schema split from UI hints.
- [Meshtastic `ConfigRoute`](https://github.com/meshtastic/Meshtastic-Android/blob/main/feature/settings/src/commonMain/kotlin/org/meshtastic/feature/settings/navigation/ConfigRoute.kt). Wire config sections as the unit of settings. Avoid its hand written screen per section.

## 6. First step

1. Done. Slice 3 with steps as a `SetupStep` list and one `opensensor-board` kind.
2. Done. The `ConfigField` table drives `confirm()` and `SECRET_KEYS`.
3. Done. `ble.pin_set` replaces the echoed PIN. Mobility is not wired, setup sends no `thing.mobility`.
4. Propose the `config` block on firmware #117.
