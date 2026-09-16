# AQ Sync — Android companion for the CoreS3 air-quality logger

Native Android app that pairs with an **M5Stack CoreS3 (ESP32-S3)** running the
[`m5stack-aq-parquet`](../m5stack-aq-parquet) firmware, shows its live readings, gives it UTC,
and copies its finalized **Parquet** files into the phone — byte-exact, verified, offline.

Air-gapped by design: the app declares **no `INTERNET` permission**, no location permission,
no analytics, no accounts. Everything it knows lives in app-private storage and can be shared
out through the system share sheet or saved with the system file picker.

## Status

**Built against protocol v1; not yet tested against hardware from a phone.** Verified so far: `./gradlew clean lintDebug assembleDebug testDebugUnitTest` → 0 lint errors, 29/29 unit tests (frame codec, JSON models, Parquet verification, sync planner), debug APK ≈ 64 MB unminified. The protocol itself
*is* bench-verified: the same contract was exercised from a macOS host client against the real
board on 2026-09-16 (pairing, 766-file manifest, verified transfers at 15–21 KB/s, time set,
flush). See
[`docs/ble-sync-protocol.md`](../m5stack-aq-parquet/docs/ble-sync-protocol.md) and
[`docs/bench-verified.md`](../m5stack-aq-parquet/docs/bench-verified.md#board-1-bluetooth-le-sync)
in the firmware repo. The frame codec and JSON parsers here are unit-tested against payloads
captured in that session.

## What it does

| Screen | Behaviour |
| --- | --- |
| **Devices** | Scans for the AQ Sync service UUID (`c0a5e9f0-0001-4b1a-9c3e-2d7f8a6b4e01`), lists `AQ-xxxx` devices with RSSI and bond state, remembers known devices. |
| **Today** | Live PM1 / PM2.5 / PM10, particle counts, board temperature (explicitly *not* ambient), battery/USB, freshness, storage counters, UTC state. **Set time from phone** and **Finalize buffered rows** actions, each explained before it runs. |
| **Files** | Device manifest grouped by Hive partition (dated / unsynced-boot / benchmarks / legacy). **Sync all** downloads every file the archive lacks, with per-file and overall progress. Per-file state: not synced · syncing · synced (verified) · mismatch. |
| **Export** | Share one file or a zip of the archive via the share sheet, or save a zip with *Save to…* (Storage Access Framework). |

### Pairing

The device is display-only: on first connection it shows a random six-digit passkey full-screen.
Android asks for that code. Only someone who can read the device screen can pair; after that,
reconnects are silent (bonded). Every characteristic requires an encrypted, authenticated link.

### Integrity

A file counts as synced only when the phone holds exactly `size` bytes, its own CRC-32 equals the
device's `OPENED.crc32`, and the first and last four bytes are `PAR1`. Files are written atomically
(temp + rename) under the **original Hive path** (`filesDir/archive/<station>/<relative name>`),
then SHA-256'd and recorded in Room. Partial downloads resume by absolute offset after re-`OPEN`
confirms `size`/`crc32` are unchanged. Nothing incomplete is ever presented as data. The device is
never asked to delete, rename or rewrite anything.

## Stack

Generated, not hand-written, from Google's official
[architecture-templates](https://github.com/android/architecture-templates) (`base` branch):

```sh
git clone --depth 1 --branch base https://github.com/android/architecture-templates.git m5stack-aq-android
cd m5stack-aq-android && rm -rf .git
bash customizer.sh earth.walkthru.aqsync Device "AQ Sync"   # bash ≥ 4
```

The generated baseline is the first commit (`Generate from android/architecture-templates base`);
everything after it is diffable against that.

| | |
| --- | --- |
| Language / UI | Kotlin 2.4.20, Jetpack Compose, Material 3 (dynamic color), Navigation 3 |
| DI / data | Hilt 2.60.1, Room 2.8.5, kotlinx.serialization 1.11.0, coroutines/Flow |
| Build | AGP 9.4.0, Gradle 9.7.1, KSP 2.3.12, version catalog |
| SDK | `compileSdk`/`targetSdk` **37**, `minSdk` **31** (no legacy Bluetooth permission model) |
| Permissions | `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_CONNECT` — nothing else |

## Build

Android Studio (latest stable) opens the project directly. From the command line, use the JBR
that ships with Studio (JDK 25) or any JDK ≥ 17:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug testDebugUnitTest
adb install app/build/outputs/apk/debug/app-debug.apk
```

Debug builds include a **fake device** (menu → *Use fake device*) that serves three small
`PAR1…PAR1` files with correct CRCs and emits live JSON every 10 s, so the UI can be exercised with
no hardware.

## Layout

```
app/src/main/java/earth/walkthru/aqsync/
  data/protocol/   UUIDs, binary frame codec, JSON models, Parquet verification
  data/ble/        scanner, GATT transport (serialized operation queue), session
  data/sync/       sync planner, engine, file archive
  data/local/      Room database (known devices, archived files)
  data/export/     share / zip / SAF export
  data/fake/       in-app fake device for debug builds
  ui/scan · ui/device · ui/files · ui/permissions · ui/theme
app/src/test/      frame codec, verification, JSON, planner tests
```

## Design decisions

Choices the protocol left open, and what this app does (mirrored into the firmware repo's
`docs/ble-sync-protocol.md` on 2026-09-16):

| Question | Decision |
| --- | --- |
| Archive root for names without a station (`benchmarks/…`, `legacy-parquet/…`) | Everything is stored under `archive/<station uuid from info>/<name verbatim>`, so dated files sit at `…/<uuid>/station=<uuid>/year=…`. Redundant on purpose: two devices can never collide. |
| `STATUS` (0x07) has no `response` frame | Fire-and-forget; the `status` notification is the acknowledgement. Never awaited on `response`. |
| `ERROR.op` for unparseable requests | Accepted as "mine" when it equals the request opcode (the firmware echoes the first byte); anything else is a protocol violation. |
| `OPENED.name` differs from the requested name | Treated as a mismatch outcome, not silently accepted. |
| `READ_END.next_offset` ≠ bytes actually received | Window fails as a protocol violation. A lost `CHUNK` must never be papered over by trusting the device's offset. |
| `info.max_read` missing | Falls back to 4096 (the firmware always sends 16384; dead code kept as a guard). |
| `bat`/`pct` in `live` | Shown raw with a "board report, unqualified" label; 16,372 → 23 mV was observed with no battery attached. |
| One outstanding GATT operation | Enforced with a `Mutex` around every read/write/descriptor call; Android's `BluetoothGatt` silently fails on concurrent operations. |
| Bonding | `createBond()` before first characteristic access if unbonded; GATT status 5 / 15 / 133 while pairing is in progress → wait for `BOND_BONDED`, then retry the operation once. |
| Downloads and rotation | Session and sync state live in a process-scoped repository, not the Activity, so a rotation mid-sync does not abort the transfer. |

## Lessons learned

- **`customizer.sh` cannot take a spaced application name.** `"AQ Sync"` was substituted into identifiers (`class AQ Sync`, `Theme.AQ Sync`, `AQ Sync.kt`). Use a single token (`AqSync`) or fix by hand right after generation; the raw output is kept as the first commit so the fix is visible.
- **macOS ships bash 3; the generator needs 4+.** `brew install bash`, then run it as `/opt/homebrew/bin/bash customizer.sh …`.
- **Material 3 Expressive is not usable from the pinned BOM.** `MaterialExpressiveTheme`, `MotionScheme` and `expressiveLightColorScheme` are `internal` in material3 1.4.0 (Compose BOM 2026.09.00). Use `MaterialTheme` + dynamic color; add expressive shapes/typography yourself.
- **API 33+ `writeCharacteristic`/`writeDescriptor` return `BluetoothStatusCodes`, not GATT status.** Comparing against `BluetoothGatt.GATT_SUCCESS` compiled and worked only because both constants are 0; lint's `WrongConstant` caught it. Compare with `BluetoothStatusCodes.SUCCESS`.
- **AGP 9.4 + Kotlin 2.4.20 + KSP 2.3.12 + compileSdk 37 build cleanly today.** The explicit Compose strong-skipping flag is deprecated (default on); `nonTransitiveRClass` stays on from the template.
- **Lint `NonObservableLocale`:** `Locale.getDefault()` inside a composable is flagged; `String.uppercase()` (root locale) is fine for eyebrow labels.
- **Trust the contract's absolute offsets.** Every `CHUNK` carries its offset, so the client verifies continuity itself rather than counting notifications — the same property that let the firmware-side host client detect a deliberately dropped frame in tests.
- **The protocol was proven before the app was.** Verifying the contract with a laptop client against the real board first (see the firmware repo's bench record) meant the app could be built and unit-tested against captured payloads with no hardware in the loop; the remaining risk is Android's BLE stack behaviour, not the protocol.

## Next

Test against the board from a real phone and record the result in the firmware repo's
`docs/bench-verified.md`; measure a full 766-file sync; then decide on on-phone querying
(the product notes in `../m5stack-aq-parquet/product/` discuss DuckDB, deliberately out of scope
for this first version).
