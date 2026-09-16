# AGENTS.md — AQ Sync (Android)

Companion app for the CoreS3 air-quality logger in the sibling repo `../m5stack-aq-parquet`.
Read `README.md` first; it holds status, design decisions and lessons learned. This file holds the rules.

## The contract is not here

The Bluetooth protocol lives in **`../m5stack-aq-parquet/docs/ble-sync-protocol.md`** and is owned by the firmware repo. Do not change frame layouts, opcodes, UUIDs, JSON keys or error codes in this app without changing that document (and the firmware) first. If the document is ambiguous, resolve it there, then implement — the "Design decisions" table in `README.md` lists the choices already made.

Measured device behaviour lives in `../m5stack-aq-parquet/docs/bench-verified.md`. This app has **not** been run against the board from a phone yet; when it has, record it there with date, firmware SHA-256 and phone model, and update `README.md` status.

## Non-negotiables

- **Offline-first, not offline-only.** Every feature must work with no network: the local archive is the source of truth and is filled over the device link (BLE now; the firmware may add Wi-Fi or LoRa device-to-device later — add a transport behind `AqTransport`, do not fork the sync engine). Cloud backup/sharing is a later **opt-in** step that runs *after* local sync, is off by default, and never gates local use. Today the app declares no `INTERNET` permission, no location permission, no analytics or crash-reporting SDKs, no accounts — only `BLUETOOTH_SCAN` (`neverForLocation`) and `BLUETOOTH_CONNECT`. When cloud sync is added: request `INTERNET` then, keep it behind an explicit setting, and follow the privacy boundaries in `../m5stack-aq-parquet/product/mobile-architecture.md`.
- **Never present unverified data.** A file is synced only after size, CRC-32 (`OPENED.crc32`) and `PAR1` head/tail all match. Absent JSON keys are `null`, never `0`. A lost `CHUNK` (offset gap) fails the window; do not trust `next_offset` over the received byte count.
- **Never ask the device to delete, rename or rewrite.** Sync is a pull of immutable files. `SET_TIME` and `FLUSH` are the only state-changing requests and must be explicit user actions with an explanation.
- **Keep the Hive path verbatim** under `archive/<station>/…` so the phone archive mirrors the SD card and the firmware repo's `tools/export_parquet.py` output.
- **One GATT operation at a time**, serialized in `BleTransport`; BLE work belongs in the repository/session layer, never in a composable or Activity.
- `minSdk` stays **31+** (modern Bluetooth permission model). Do not add legacy permission branches.

## Provenance

Generated from Google's `android/architecture-templates` (`base` branch) with `customizer.sh earth.walkthru.aqsync Device "AQ Sync"`; the untouched output is commit `34f2d30`. Keep the template's structure (Hilt, Room, Navigation 3, version catalog) rather than importing another architecture.

## Build and verify

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew --no-daemon clean lintDebug assembleDebug testDebugUnitTest
```

All three must pass before a commit: lint errors fail the build by design. Unit tests under `app/src/test/` use payloads captured from the real device (`AQ-6b40`, firmware `arduino-cores3-parquet-v4`); keep them in sync with the contract. Bump `gradle/libs.versions.toml` only to versions you have verified build, and note the date in `README.md`.

## Layout

```
app/src/main/java/earth/walkthru/aqsync/
  data/protocol/  UUIDs, frame codec, JSON models, Parquet verification   (contract mirror)
  data/ble/       scanner, GATT transport, session                        (Android BLE only here)
  data/sync/      planner, engine, archive                                (pure logic + files)
  data/local/     Room                                                    data/export/  share/zip/SAF
  data/fake/      debug-only fake device                                  ui/           Compose screens
```
