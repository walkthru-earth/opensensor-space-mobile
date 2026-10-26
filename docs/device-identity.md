# Device identity and recovery

**Scope:** which values identify a sensor, what the app does when one changes, and the names
that stay fixed under the opensensor.space brand.
**Code:** `data/DeviceLinkOwner.kt`, `data/ble/BleLinkDriver.kt`, `data/ble/BleStaleBondDetector.kt`,
`data/local/database/` (`thing_epoch`), `core/protocol/.../JsonModels.kt`.
**Related:** [transports.md](transports.md), [sync-engine.md](sync-engine.md),
[data-model.md](data-model.md), [devices-and-discovery.md](devices-and-discovery.md).

## Names that stay fixed

| Concern | Rule |
| --- | --- |
| App identity | Application ID and iOS bundle ID `space.opensensor.app` and the release certificate. Changing either installs a new app with an empty archive |
| Wire | Service UUIDs, `_ossync._tcp`, `OSS1`, framing and opcodes. The firmware owns them and they change only in lockstep with aq-parquet `docs/shared` |
| Supported device | Any device that implements the shared sync contract. An ESP32-S3 radio alone does not imply it |
| Unknown schema | Verified files are collected and exported. Live cards stay unavailable instead of guessing |
| One owner per device | Firmware has one open file across BLE and LAN, so each physical device has one controller |

Never show or log a stored PIN, Wi-Fi PSK or LAN token.

## Identity values

| Value | Meaning | After a reset or reflash |
| --- | --- | --- |
| BLE address | Public, stable transport address. The enrollment key | Scan filters and the Android presence association use it |
| `info.dev` | Full 12-hex Wi-Fi thing MAC | Normally stable. Correlation evidence, not a credential |
| `info.thing` | Random Thing UUID in NVS `parquet/thing` | Survives a normal reset, recreated after an NVS erase |
| `info.boot` | Random 128-bit boot ID | Changes every boot, never means a new device |
| `info.fw` | Firmware version text | Supported versions are accepted automatically |
| `info.dict` and `schema` | Measurement dictionary digest and schema ID | Refresh interpretation only |
| Bond and LAN token | Enrollment keys and bearer credential | An erase can remove both while the phone keeps its bond |

Room keeps one `thing_epoch` row per enrolled address and thing, so an earlier thing's
history stays reachable after a thing change. Missing identity fields never match every device.

## What the app does

| Event | App behaviour |
| --- | --- |
| Same device, same `dev` and thing, new boot | Reconnects, reopens files, verifies partial size and CRC, resumes from the received offset |
| Firmware or dictionary change | Refreshes the profile. Unknown interpretation stays unavailable |
| Device away longer than the retries | Three reconnects five seconds apart (`RECONNECT_ATTEMPTS`), then the device waits for presence, periodic work or an explicit connect |
| Same device and `dev`, new thing | Adds an epoch, drops the stale LAN token and fetches a new one over bonded BLE |
| Token refused or unreadable ciphertext | Clears only that device's token and refreshes it over BLE |
| Firmware lost its bond keys | `BleStaleBondDetector` stops retries and the page shows the pairing repair message. Other devices keep collecting |
| `dev` changes, or a changed address without continuity | Rejected as an identity mismatch. The user enrolls the replacement |

The app never re-pairs silently, never uses hidden bond APIs, never forgets a bond on its own and
never sends configuration or reset commands to recover. A name, its `OS-xxxx` suffix, advert boot
field or mDNS TXT never authorises replacing trust. The LAN bearer token authenticates the phone
to the device, not the reverse, so TXT and INFO comparisons are consistency checks only.

## Files from another thing on the same SD card

After an NVS erase the card can hold thing A files while INFO reports thing B. The contract
places every listed file under `archive/<INFO.thing>/`. The app keeps earlier epochs, and
`DailyParquetBuilder` refuses a source whose Parquet metadata names another thing, so it never
joins the new thing's daily files. The verified file stays in the archive for export. Raw paths and bytes never move. Changing the outer namespace or
deduplicating old copies needs a firmware contract change first.

## Measurement capabilities

Interpretation comes from bundled, reviewed schema and dictionary descriptors. The app never
fetches the dictionary URI. LIVE `t` is board temperature on CoreS3 and ambient temperature on
Waveshare, and `rh` is humidity only on the reviewed Waveshare profile. Unknown profiles show no
guessed labels or units. History availability comes from the file's columns and validity gates,
not from a dictionary entry. Only PM values get health bands. The metric model is in
[devices-and-discovery.md](devices-and-discovery.md).
