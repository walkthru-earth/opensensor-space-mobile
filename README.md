# AQ Sync — Android companion for the CoreS3 air-quality logger

Native Android app that pairs with an **M5Stack CoreS3 (ESP32-S3)** running the
[`m5stack-aq-parquet`](../m5stack-aq-parquet) firmware, shows its live readings, gives it UTC,
and copies its finalized **Parquet** files into the phone — byte-exact, verified, offline.

Air-gapped by design: the app declares **no `INTERNET` permission**, no location permission,
no analytics, no accounts. Everything it knows lives in app-private storage and can be shared
out through the system share sheet or saved with the system file picker.

## Status

**Built against protocol v1; not yet tested against hardware from a phone.** The protocol itself
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

## Next

Test against the board from a real phone and record the result in the firmware repo's
`docs/bench-verified.md`; measure a full 766-file sync; then decide on on-phone querying
(the product notes in `../m5stack-aq-parquet/product/` discuss DuckDB, deliberately out of scope
for this first version).
