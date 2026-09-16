# AGENTS.md — AQ Sync (Android)

Companion app for the CoreS3 air-quality logger in the sibling repo `../m5stack-aq-parquet`.
Read `README.md` first; it holds status, design decisions and lessons learned. This file holds the rules.

## The contract is not here

The Bluetooth protocol lives in **`../m5stack-aq-parquet/docs/ble-sync-protocol.md`** and is owned by the firmware repo. Do not change frame layouts, opcodes, UUIDs, JSON keys or error codes in this app without changing that document (and the firmware) first. If the document is ambiguous, resolve it there, then implement — the "Design decisions" table in `README.md` lists the choices already made.

Measured device behaviour lives in `../m5stack-aq-parquet/docs/bench-verified.md`. This app has **not** been run against the board from a phone yet; when it has, record it there with date, firmware SHA-256 and phone model, and update `README.md` status.

## Non-negotiables

- **Offline-first, not offline-only.** Every feature must work with no internet: the local archive is the source of truth and is filled over the device link — BLE always, LAN (protocol v2: one TCP socket to a device found via mDNS `_aqsync._tcp`, authenticated with a token obtained over BLE) when available. Both are transports behind `AqTransport`; add new ones (LoRa relays, …) there and never fork the sync engine. Cloud backup/sharing is a later **opt-in** step that runs *after* local sync, is off by default, and never gates local use. `INTERNET` exists **only** for LAN sockets to devices discovered via mDNS `_aqsync._tcp`; the code must never connect to an address it did not get from that discovery. **Any cloud endpoint still requires an explicit opt-in setting and its own review** — `INTERNET` being declared does not pre-approve it. No location permission, no analytics or crash-reporting SDKs, no accounts. `ACCESS_LOCAL_NETWORK` is requested at runtime only on API 37+ and its denial must leave BLE fully working. Follow the privacy boundaries in `../m5stack-aq-parquet/product/mobile-architecture.md`.
- **Never log the LAN token.** It is a bearer secret for anyone on the network. It lives only in `known_device.lanToken` (Room); `Frame.Token`, `KnownDevice`, the frame recorder and the raw-command view redact it. The same goes for `wifi.psk`: write-only, never echoed, never logged (`SET_CONFIG` requests are recorded as opcode only).
- **Never present unverified data.** A file is synced only after size, CRC-32 (`OPENED.crc32`) and `PAR1` head/tail all match. Absent JSON keys are `null`, never `0`. A lost `CHUNK` (offset gap) fails the window; do not trust `next_offset` over the received byte count.
- **Never ask the device to delete, rename or rewrite.** Sync is a pull of immutable files. The state-changing requests — `SET_TIME`, `FLUSH`, `SET_CONFIG`, `REBOOT` (and `ble.clear_bonds` / `lan.rotate_token` inside `SET_CONFIG`) — must be explicit user actions with an explanation, and the destructive ones (reboot, rotate token, forget bonds, pairing mode `none`) need a confirmation dialog.
- **Keep the Hive path verbatim** under `archive/<station>/…` so the phone archive mirrors the SD card and the firmware repo's `tools/export_parquet.py` output.
- **One GATT operation at a time**, serialized in `BleTransport`; one request at a time per session, serialized in `DeviceSession`. Transport work belongs in the repository/session layer, never in a composable or Activity. On LAN, `STATUS`/`LIVE` pushes may arrive between two response frames: demultiplex by type byte in the transport, never in the session.
- **Advanced mode is opt-in and cosmetic.** The Debug destination shows raw data (status JSON, frames, device log, raw commands) and exists only while the DataStore switch is on. Nothing the Simple experience needs may depend on it.
- `minSdk` stays **31+** (modern Bluetooth permission model). Do not add legacy permission branches.

## Provenance

Generated from Google's `android/architecture-templates` (`base` branch) with `customizer.sh earth.walkthru.aqsync Device "AQ Sync"`; the untouched output is commit `34f2d30`. Keep the template's structure (Hilt, Room, Navigation 3, version catalog) rather than importing another architecture.

## Build and verify

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew --no-daemon clean lintDebug assembleDebug testDebugUnitTest
```

All three must pass before a commit: lint errors fail the build by design; do not add lint warnings carelessly (7 pre-existing ones are known). Unit tests under `app/src/test/` use payloads captured from the real device (`AQ-6b40`, firmware `arduino-cores3-parquet-v4`) for v1 and hand-assembled fixtures from the contract for v2; keep them in sync with the contract and replace v2 fixtures with captures once a v2 device has been read. The `TcpTransport` loopback test is the only automated coverage of the LAN pipe. Bump `gradle/libs.versions.toml` only to versions you have verified build, and note the date in `README.md`.

## Layout

```
app/src/main/java/earth/walkthru/aqsync/
  data/protocol/  UUIDs, frame codec v1+v2, CONFIG model, LAN framing, JSON   (contract mirror)
  data/ble/       scanner, GATT transport, session (all ops), frame recorder  (Android BLE only here)
  data/lan/       TCP transport, NsdManager discovery                          (Android net only here)
  data/sync/      planner, engine, archive                                     (pure logic + files)
  data/local/     Room (schema v2)          data/settings/  DataStore          data/export/  share/zip/SAF
  data/fake/      debug-only fake device    ui/             Compose screens (scan, device, files, debug, settings)
```
