# Setup wizard and config forms

Part of [Thing plugins](README.md). Proposal, 2026-10-27.

## 1. Today

- `:feature:setup` holds the wizard (`FIND → NAME → LOCATION → WIFI → DONE`, slice 3 of [2026-10-12-stations-ux-design.md](../2026-10-12-stations-ux-design.md)). `SetupFlows.BOARD` is the `opensensor-board` step list, and `SetupController` walks it over a `SetupPort` on each platform. Add sensor on Discover opens it full screen over My devices (`SetupKey`). Pairing happens in the OS dialog when a sensor is picked on Find. The known row is written only after INFO passed the identity check, so a failed pairing leaves no entry.
- The nickname (1 to 32 characters) and the pending setup flag live in the phone settings store (`SettingsRepository`), not in `KnownDevice`, so they never reach the sensor or the public feed. Stations cards do not show them yet.
- Location and Wi-Fi pages reuse `DeviceLocationForm`, `WifiCard` and `LanCard` over the same `DeviceConfigController` as Device settings.
- Location is required (`SetupStep.Location(required = true)`), since a Thing cannot be set up without a fixed or moving location. It has no Skip, and Next stays off until the board confirms a valid `location.cell`, from phone location or the map. Country stays optional. Done, which clears the pending flag, needs every required page, and a resumed run opens at Location. A moving kind will satisfy it by declaring moving mobility instead of a pin. Wi-Fi can be skipped.
- Board-specific parts. Find uses the BLE service UUID scan. Pairing has random, fixed and none modes. Wi-Fi join and the LAN token go over Bluetooth only. The sensor sticker is Plantower only.
- Device settings are a fixed list of cards in `DeviceConfigSection.kt`. The sensor card is gated by one column name.
- `SET_CONFIG` keys are rows of `ConfigFields` in `core/protocol/.../ConfigField.kt`, each with its secret flag and gate. `SECRET_KEYS` is built from it, and `ConfigFields.merge` lets a schema only tighten a known key. `ConfigPatch.Builder` keeps one typed method per key with firmware limits and the closed `ConfigProblem` enum. `DeviceConfigController.confirm()` sends every confirmed write, and its `DeviceConfigDialog.FIELDS` table maps each gated key to its dialog, checked by a test.

## 2. Step graph per kind

Each `ThingKind` declares an ordered list of steps. Each step is a shared Compose component in a new `:feature:setup` module. Following the Home Assistant config flow, a step returns a form, an abort or a finished result, discovery can start the flow already filled in, and first setup and later settings reuse the same step code.

```kotlin
sealed interface SetupStep { val id: String; val skippable: Boolean; val required: Boolean }
// Permission(UserAction), Find, Pair, Name, Location(required), Fields(id, keys), Done
```

- A Permission step only binds `PermissionTriggers` to the step's action. It is never a wall.
- Sharing is never a step. Upload is turned on only from that Thing's settings (`devices-and-discovery.md`, AGENTS.md). Done may at most link to the setting.
- Find on iOS can use [AccessorySetupKit](https://developer.apple.com/documentation/accessorysetupkit) (the iOS target is 18.5), which filters by our service UUID and grants Bluetooth for that accessory only. Find on Android can use [Companion Device Manager](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing) `associate()`, which the app already uses for presence.

## 3. Fields from data

A small closed set of field types, not full JSON Schema. No mature Compose or KMP JSON Schema renderer exists, and Home Assistant selectors show a closed set scales well.

```kotlin
sealed interface FieldType {
    data class Text(val maxBytes: Int, val pattern: String? = null, val singleLine: Boolean = true) : FieldType
    data class IntBetween(val min: Int, val max: Int) : FieldType
    data class Choice(val values: List<String>) : FieldType
    data object Toggle : FieldType      // "0" | "1"
    data object Action : FieldType      // ble.clear_bonds, lan.rotate_token
    data object H3Cell : FieldType      // only the location form can fill it
}
enum class Applies { NOW, NEXT_SAMPLE, NEXT_BOOT }
enum class Gate { NONE, CONFIRM, CONFIRM_DISRUPT }   // DISRUPT also refuses while syncing

data class ConfigField(
    val key: String,
    val type: FieldType,
    val applies: Applies,
    val secret: Boolean = false,        // masked, secure window, BLE only, redacted toString
    val confirm: Gate = Gate.NONE,
    val confirmWhen: String? = null,    // e.g. "none" for ble.pair
)

// :feature:device, next to DeviceConfigController
data class ConfigFieldUi(
    val key: String,
    val dialog: DeviceConfigDialog?,    // required when the field's Gate is not NONE
    val level: DetailLevel = DetailLevel.SIMPLE,
    val labelRes: String,               // app string id, never free text from the device
)
```

- Placement. `ConfigField` holds only wire facts and lives in `:core:protocol`, which depends only on `:core:model`. `ConfigFieldUi` holds the dialog, detail level and label, and lives in `:feature:device` with `DeviceConfigDialog`, so no core module reaches into a feature or settings module.

- Confirmations come from data. `ble.pair=none` is `CONFIRM` when the value is `none`. Clearing bonds and rotating the token are `CONFIRM_DISRUPT`. Reboot stays its own action. `DeviceConfigController.confirm()` stays the single place that sends a confirmed write.
- A gated `ConfigField` without a `ConfigFieldUi.dialog` fails the table test in `:feature:device`. The table maps keys onto today's closed types, so no second list appears.

| Key | Gate | `DeviceConfigDialog` | `DeviceConfigChange` |
| --- | --- | --- | --- |
| `ble.pair` when `confirmWhen = "none"` | `CONFIRM` | `PairingNone` | `PAIRING` |
| `ble.clear_bonds` | `CONFIRM_DISRUPT` | `ForgetBonds` | `BONDS_CLEARED` |
| `lan.rotate_token` | `CONFIRM_DISRUPT` | `RotateToken` | none, the token is refetched |
| reboot, an action and not a field | `CONFIRM_DISRUPT` | `Reboot` | none |

- Secrets are marked in the schema. A secret field is never logged, drafted, sent over LAN or used as a default. `SECRET_KEYS` comes from the app allowlist plus the schema, as the next rule says. The redaction tests stay.
- Labels stay in the app. An unknown string id falls back to Developer only display.
- App floor rules win over any schema. The app owns an allowlist of every key it knows, each with its secret flag and gate. A schema can make a known key stricter, never looser. An unknown key fails closed. It is not editable, never sent, and shows read only at Developer level until an app release classifies it. `SECRET_KEYS` is the union of the allowlist's secret keys and any key a schema marks secret, and a test checks the schema against the allowlist.
- Step 1 mirrors today's builder keys in a `ConfigField` table in `:core:protocol` and a `ConfigFieldUi` table in `:feature:device`, with no firmware change. Step 2 proposes an optional `config` block on the firmware #117 module and board manifests, copied into the template, so firmware owns limits and gates and the app stops duplicating them. The typed builders stay as tested shortcuts.

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

1. Build slice 3 as specified, with steps as a `SetupStep` list and one `opensensor-board` kind.
2. `ConfigField` table driving `confirm()` and `SECRET_KEYS`.
3. Fix the `pin_set` drift and wire up mobility in the same pass.
4. Propose the `config` block on firmware #117.
