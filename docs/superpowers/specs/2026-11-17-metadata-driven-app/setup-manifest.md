# Setup and settings from a board manifest

Part of [Metadata-driven app](README.md). 2026-11-17. Proposal. Field names follow firmware [#183](https://github.com/walkthru-earth/opensensor-space-firmware/pull/183), which owns the final spelling.

## 1. Target

The board says which setup steps it needs and which settings sections it has. The app owns the components, the order of the app-owned steps and every safety rule. The device remembers which steps are done, so any phone sees the same state.

```mermaid
flowchart LR
  B[board.json] -->|build| T[template firmware block<br/>setup, settings, config_keys]
  T -->|desc SHA-256| A[app resolves template]
  A --> W[wizard: app steps + declared steps]
  A --> S[Device settings sections]
  D[GET_CONFIG page 1<br/>thing.setup_ack, setup_v] --> W
  W -->|SET_CONFIG| D
```

## 2. Step catalog

The app keeps a fixed catalog. The manifest picks from it by id and orders the declared part. An id outside the catalog is a generic step when it lists `keys`.

| Id | Component | Owner | Required rule | Settings section |
| --- | --- | --- | --- | --- |
| `permission` | `PermissionTriggers` binding | App | Always, never a page | none |
| `find` | Find | App | Always first page | none |
| `pair` | Pair | App | Always, not a page | none |
| `name` | Nickname form, phone only | Board declares | Never | none |
| `location` | `DeviceLocationForm` | Board declares, app adds | Fixed Thing without a cell, app floor. Added after Find when the manifest omits it | `location` |
| `mobility` | Fixed or moving choice | Board declares | When the board offers both | `location` |
| `site` | `DeviceSiteForm` | Board declares | Never | `site` |
| `wifi` | Wi-Fi join form | Board declares | Never | `wifi` |
| `pairing` | Pairing form | Board declares | Never | `pairing`, Detailed |
| `lan` | LAN card | Board declares | Never | `lan`, Developer |
| `bonds` | Bonds card | Board declares | Never | `bonds`, Detailed |
| Any other id with `keys` | Generic `ConfigField` form | Board declares | From `req` | Same id |
| `done` | Done | App | Always last page | none |

Level gates on settings sections stay app rules. A manifest cannot show LAN below Developer.

## 3. Manifest shape

In the template `firmware` block, generated from `board.json`, hashed with the template. Changing the order changes `desc`.

```json
"firmware": {
  "setup": [
    { "id": "name", "req": false },
    { "id": "location", "req": true },
    { "id": "wifi", "req": false },
    { "id": "panel", "req": false, "keys": ["panel.theme", "panel.start_page"] }
  ],
  "settings": [
    { "id": "location" },
    { "id": "wifi" },
    { "id": "panel", "keys": ["panel.theme", "panel.refresh_s", "panel.start_page", "panel.sleep_s"] },
    { "id": "sensor", "keys": ["sen6x.altitude_m", "sen6x.co2_asc", "sen6x.temp_offset_dc", "sen6x.fan_clean"] }
  ],
  "config_keys": {
    "panel.theme": { "type": "enum", "values": ["light", "dark"], "applies": "immediately", "page": 7, "label": "Theme", "label_i18n": { "ar": "المظهر" } },
    "sen6x.fan_clean": { "type": "boolean", "applies": "action", "page": 7, "label": "Clean the fan" }
  }
}
```

- Each contract type maps to one `FieldType`. `enum` to `Choice`, a range to `IntBetween`, `boolean` to `Toggle`, `applies: action` to `Action`, a string to `Text`. A decimal key either gets a new `FieldType.Decimal(min, max, step)` or stays an int in scaled units, as `sen6x.temp_offset_dc` does.
- The main metric is not part of this block. It is `opensensor:primary` at template top level, see [metrics.md 2.3](metrics.md#23-scales-and-main-metric).
- `page` is the `GET_CONFIG` page that reads the key back. Board keys live on one new page after page 6, within the 480 byte page limit. Pages 1 to 6 keep their keys.
- The template carries names, types and limits. Never a value, never a default secret.
- The first board keys are the CrowPanel ones, today knob-only in NVS `ospanel`. Theme, refresh, start page, sleep, fan cleaning, CO2 ASC, altitude and temperature offset.

## 4. Completion on the device

- Page 1 gains `thing.setup_ack`, a bitmask with bit `i` for `setup[i]`, and `thing.setup_v`, which names the step list the bits refer to.
- A `setup_v` that does not match the resolved template's step list means no step is done. The exact rule is open in firmware #183.
- The app sets a step's bit with its own `SET_CONFIG` after the step's save reads back. Skip also sets the bit of a skippable step.
- Every ack write sends `setup_ack` and the current `setup_v` together. Under a stale `setup_v` the old bits read as none, so the first write after a change carries only the new bit and the new version. Bits from an earlier step order never count for the new one.
- A required step the app knows stays derived from config, never from the bitmask alone. Location is done only while a fixed Thing has a valid `location.cell`. The one exception is a required step the app does not know but can draw as a generic form (the last item of this section). The app has no config rule for it, so its bit alone completes it. When the manifest omits `location` and a fixed Thing has no cell, the plan adds the app `location` step, which has no ack bit.
- The plan reads fixed from page 1, as `ThingSetupPolicy` does today. `thing.mobility` `fixed` is fixed. No `thing.mobility` while page 1 reports a `location` object is fixed too, so a page with a location object, no mobility and no cell keeps the `location` step. `moving` is not fixed and never needs a cell. A mobility this build does not know, or no mobility and no `location` object, claims nothing. The location floor of section 5 uses the same reading.
- Firmware that reports page 1 without `setup_ack` and `setup_v` cannot store step progress, and no phone keeps step progress of its own. Firmware and app move in lockstep ([decision 0003](https://github.com/walkthru-earth/opensensor-space-firmware/blob/main/docs/shared/decisions/0003-metadata-driven-apps.md), firmware #183), so a template that declares `firmware.setup` ships only with firmware that reports both fields. For a board that reports neither, the plan drops the declared steps and keeps only the app steps the config proves, such as `location`. Setup then ends on config alone and never waits on a bit the board cannot keep.
- An unknown optional step without typed keys is dropped from the plan. It never counts as pending, and its bit is not written.
- Setup is needed when a required step is not satisfied, or when the bit of a declared step left in the plan is clear. The app `location` step has no bit, so the saved cell alone completes it. Advert bit 6 or page 1 `thing.setup` 1 is the board saying setup is needed, and the app trusts it. Bit 6 counts only from an advert newer than the last page 1 read, the rule `ThingSetupWatch` applies in its caller today, so a cached advert never holds setup open after a fresh page 1 reads `thing.setup` 0. Nobody clears `thing.setup`. The board derives it from its own config, 1 while a fixed Thing has an empty `location.cell`, and it reads 0 once a cell is saved or the Thing is set to moving (firmware `ble-sync-protocol.md` page 1, decision 0003). Ack bits never change it. A clear bit or `thing.setup` 0 never ends setup on its own, the plan still decides, so a fixed Thing without a cell stays pending whatever the board reports.
- A required step whose id the app does not know is a generic form when it has typed keys and every one of them survives `ConfigFields.merge` (section 6). Its bit completes it once the form saves, the exception to the config rule above. A step with a declared key the merge drops reads as a step without typed keys, so its bit never marks it done with a field left out. Without typed keys the app cannot draw it, so it leaves setup pending. The wizard says to update the app and offers Leave.

## 5. Safety floors

The manifest can add protection, never remove it.

| Floor | Rule |
| --- | --- |
| Location | A fixed Thing needs a saved cell before Done, whatever the manifest says |
| Secrets | `ble.pin`, `wifi.psk` and the LAN token are never declared by a manifest, never logged, never in a frame dump. A manifest key marked secret is dropped |
| `SECRET_KEYS` | Never smaller than the app's own set |
| Reserved prefixes | `ble.`, `wifi.`, `lan.`, `location.`, `thing.`, `owner.`, `sensor.` keys come only from the app allowlist. A manifest cannot add one |
| Confirmations | Reboot, token rotation, forget bonds and pairing mode `none` always confirm. A board `action` key always confirms |
| Debug | Raw commands cannot send `REBOOT` or `SET_CONFIG` |
| Silent actions | Clock, token fetch and `lan.on` stay automatic and hidden below Developer |
| Location precision | Res 8 only, coarse fix, the original fix never stored or sent |
| Sharing | Never a setup step. Upload stays per Thing, from its settings, with confirmation |

## 6. `ConfigFields.merge` change

Today `merge` keeps a known key at the app's floor and drops every unknown key ([ConfigField.kt:169](../../../../core/protocol/src/commonMain/kotlin/space/opensensor/data/protocol/ConfigField.kt#L169)). It has no production caller yet, only `ConfigFieldTest`.

New rule.

1. A known key keeps the app's type, timing and protections, and gains any stricter flag the manifest asks for. Unchanged.
2. An unknown key outside the reserved prefixes is accepted when its type parses. It is never secret. An `action` gets `Confirm.Always` and `disrupts`.
3. An unknown key under a reserved prefix, or with a type that does not parse, is dropped.
4. `ConfigPatch.Builder` gains `field(field, value)` that checks the value against its `FieldType`, and refuses a secret or reserved key. The typed methods for app keys stay.
5. A generic form writes each known app key through its typed `ConfigPatch.Builder` method and uses `field` only for unknown keys, so a step that holds `wifi.on` and `panel.theme` saves both.
6. A generic step may hold site keys (`owner.*` and `thing.deployment_id`) next to other keys, and `ConfigPatch.Builder.build` refuses that mix. The form then saves in two `SET_CONFIG` writes, the non-site keys first, then the site keys. Each write reads back every page that holds one of its keys, and all of them must match before the next write is sent. A write that is refused or does not read back stops the save and keeps the draft. The next Save sends only the keys that still differ from the board's last answer, so a part that already read back is not sent again. The ack bit is written only after every part reads back.
7. An `action` key has no stored value to read back. It runs only from its own button, after its confirmation, and the `SET_CONFIG` OK reply is what confirms it. A generic step whose keys include an action is done when every stored key reads back and every action got its OK in this wizard run. A refused action leaves the step pending. When the ack write after it fails, the retry sends only `setup_ack` and `setup_v`, never the action again. The OK lives only in this wizard run and is not stored. If the wizard closes before the ack reads back, the step shows pending, and the action runs again only when the user taps its button and confirms it again.

## 7. What goes away

| Today | After |
| --- | --- |
| `SetupFlows.BOARD` fixed list ([SetupStep.kt:95](../../../../feature/setup/src/commonMain/kotlin/space/opensensor/setup/SetupStep.kt#L95)) | App steps around the manifest steps |
| `Fields` always draws `WifiForm` ([SetupScreen.kt:179](../../../../feature/setup/src/commonMain/kotlin/space/opensensor/setup/SetupScreen.kt#L179)) | `wifi` draws `WifiForm`, a generic id draws `GenericFieldsForm` |
| Phone `SETUP_PENDING` ([SettingsKeys.kt:47](../../../../core/settings/src/commonMain/kotlin/space/opensensor/settings/SettingsKeys.kt#L47)), `SetupPort.setPending` on both phones | Device `thing.setup_ack`. The key, `setSetupPending` and both adapters are removed |
| Site probe, page 5 `ERROR` 1 hides Site (`SetupController.siteSupported`) | Site shows when the manifest lists it |
| `ThingSetupPolicy` derived fallback for firmware without `thing.setup` ([ThingSetupPolicy.kt:35](../../../../core/sync/src/commonMain/kotlin/space/opensensor/data/sync/ThingSetupPolicy.kt#L35)) | Bit 6 from an advert newer than the last page 1 read, and page 1 `thing.setup` 1, add a need. The `SetupPlan` of section 4 decides the rest |
| Fixed settings order ([DeviceConfigSection.kt:207](../../../../feature/device/src/commonMain/kotlin/space/opensensor/device/DeviceConfigSection.kt#L207)) | Manifest `settings` order, app level gates kept |
| Sensor card gated by the `sensor_serial_code` column | A `sensor` section the board declares |

Between pairing and the first page 1 read nothing is claimed, as today.

## 8. Work packages

All UI is shared Compose in `commonMain`, one implementation for both phones. Platform code only adapts `SetupPort`.

| # | Package | Modules | Tests to add |
| --- | --- | --- | --- |
| S1 | Parse `setup`, `settings` and `config_keys` from the template `firmware` block into `SetupManifest` | `core/model` | Parse of the CrowPanel and fake board manifests, unknown step ids kept as data |
| S2 | `ConfigFields.merge` rule of section 6, `ConfigPatch.Builder.field`. Decimal type if the contract adds it | `core/protocol` | Reserved prefix refused, secret refused, action gets confirm, out of range refused |
| S3 | Page 1 `thing.setup_ack` and `setup_v`, the new board settings page | `core/protocol` (`DeviceConfig`), `core/sync` (`DeviceSession`) | Parse, page 1 without `setup_ack` and `setup_v` parses as no record, stale `setup_v` reads as nothing done, a new bit drops the bits of a stale list and is sent with the current version |
| S4 | `SetupPlan.of(manifest, config)` in shared code. Steps, required set, done set, pending | `core/sync` beside `ThingSetupPolicy` | Unknown required step without typed keys leaves pending. Unknown required step with typed keys is generic and done by its bit. Unknown required step with a normal key and a dropped reserved-prefix key leaves pending and never writes its bit. Unknown optional step with typed keys is generic. Without typed keys it is dropped and setup still reaches Done. A fixed Thing with no cell gets a `location` step when the manifest omits it, and saving the cell reaches Done. A fixed Thing with no cell stays pending while `thing.setup` is 0. No mobility with a `location` object and no cell keeps the `location` step. Bit 6 from an advert older than the last page 1 read adds no need. A board with no setup record drops its declared steps and reaches Done on config alone |
| S5 | Wizard walks `SetupPlan`, sets ack bits, `GenericFieldsForm` | `feature/setup` | Controller tests for each step kind, Skip sets the bit, Done waits for required steps. A required generic step with `wifi.on` and an unknown key saves through the typed method and `field`, and its bit marks it done. A step with `owner.title` and `wifi.on` sends the non-site write, then the site write, and a refused site write keeps the bit clear and resends only the site keys. A step with `wifi.on` and `panel.theme` whose page 7 read-back fails keeps the bit clear. A required step whose only key is `sen6x.fan_clean` is done after its OK reply, and a failed ack write retries only `setup_ack` and `setup_v` |
| S6 | Remove `SETUP_PENDING`, `setPending`, the Site probe and the derived fallback | `core/settings`, `feature/setup`, `app` (`SetupViewModel`), `ios/shared` (`SetupIos`), `core/sync` | Existing setup watch tests rewritten on device state |
| S7 | Device settings from manifest `settings`, generic sections reuse `GenericFieldsForm` | `feature/device` | Order follows the manifest, LAN stays Developer, bonds and pairing stay Detailed |
| S8 | Update `docs/screens.md`, `docs/sync-engine.md`, `docs/device-location.md`, `docs/store-compliance.md` if a permission moves | docs | none |

Each package passes `./gradlew --no-daemon verify`. The wizard on a CrowPanel and on a CoreS3 needs a dated run on both phones in `docs/status.md`.
