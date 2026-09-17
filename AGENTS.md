# AGENTS.md — AQ Sync (Android)

Companion app for the CoreS3 air-quality logger in the sibling repo `../m5stack-aq-parquet`.
Read `README.md` first; it holds status, design decisions and lessons learned. This file holds the rules.

## Where to look first (read this instead of grepping)

| Need | Go to |
| --- | --- |
| Status, what is verified, every design decision and lesson | `README.md` (Status → What it does → Design decisions tables → Lessons learned) |
| The plan and its progress: history, UX, data layer, iOS parity, open decisions D1–D10 | `docs/history-ux-and-data-layer.md` (progress note under §6) |
| Protocol frames / JSON / CONFIG (mirror only; owner is the firmware repo) | `core/protocol/src/main/kotlin/.../data/protocol/` ← `../m5stack-aq-parquet/docs/ble-sync-protocol.md` |
| Parquet reader / writer, fixtures, PyArrow oracle | `core/parquet/` · fixtures `core/parquet/src/test/resources/parquet/` · `tools/make_parquet_fixtures.py`, `tools/validate_parquet_samples.py` (run with `cd ../m5stack-aq-parquet && pixi run python …`) |
| Metrics, bands, gates, settle window (add a sensor here) | `core/history/.../Metrics.kt` |
| Tiers, daily derived files, time estimate, chart binning | `core/history/.../Aggregation.kt`, `app/.../data/history/HistoryDeriver.kt` + `DailyDerived.kt`, `core/history/.../TimeEstimate.kt`, `Columns.kt` |
| Friendly layer (moods, copy), Now card, detail levels | `core/history/.../AirMood.kt`, `app/.../ui/device/NowCard.kt`, `app/.../data/settings/AppSettings.kt` (`DetailLevel`) |
| Charts (columns, day ring, calendar) | `app/.../ui/history/BandColumnChart.kt`, `DayRing.kt`, `CalendarHeatmap.kt`, `HistoryScreen.kt` |
| Sync engine, transports, background | `app/.../data/sync/SyncEngine.kt`, `data/ble/`, `data/lan/`, `data/background/AutoSyncRunner.kt` |
| Room schema (v7) and migrations | `app/.../data/local/database/AppDatabase.kt`, `History.kt`, `ArchivedFile.kt`, JSON under `app/schemas/` |
| Test phone | OnePlus 7 Pro, Android 16, Wi-Fi ADB `adb connect 192.168.100.173:38457` (port changes after reboot); `run-as` is refused on this ROM, so inspect via logcat tags `HistoryIndexer HistoryDeriver SyncEngine`, not files |
| Board files fetched over serial by the firmware-side session | `../m5stack-aq-parquet/firmware/arduino-m5unified/artifacts/output/` (v6 files); full SD export `../m5stack-aq-parquet/exports/sd-20260916T173205Z/` (764 files) |

Settled questions — do not re-research: Kotlin DataFrame cannot read Parquet on Android (Arrow Dataset JNI has no Android build) and boxes values; DuckDB for Android is an experimental self-build (Swift is official); MapLibre Compose 0.17 / PMTiles `file://` for maps; Rive (MIT, Compose API) over Lottie for the data-driven character; sensor warm-up: firmware nulls uptime < 30 s (`pms_status = 1`), the next ~3 min drift ~2.5 µg/m³ on this hardware (`Metrics.PMS_SETTLE_S`), Sensor.Community wakes 15 s before a 5 s window, AirGradient gates nothing numerically and applies EPA-2021 humidity compensation (needs RH, which this board lacks). Sources and numbers are in the README decisions.

## The contract is not here

The Bluetooth protocol lives in **`../m5stack-aq-parquet/docs/ble-sync-protocol.md`** and is owned by the firmware repo. Do not change frame layouts, opcodes, UUIDs, JSON keys or error codes in this app without changing that document (and the firmware) first. If the document is ambiguous, resolve it there, then implement — the "Design decisions" table in `README.md` lists the choices already made.

Measured device behaviour lives in `../m5stack-aq-parquet/docs/bench-verified.md`. This app has run against the board from one phone (OnePlus 7 Pro, Android 16 / API 36, 2026-09-17, app commit `a993268`; BLE and LAN file sync, partial runs) and that is recorded there under "Phone over LAN and BLE". Every further phone run — and especially the first instrumented full sync and the first Auto-sync period cycle — goes there too, with date, firmware SHA-256, app commit and phone model, and `README.md` status is updated to match. Anything not in that file is unverified: say so in the docs rather than assuming it works.

## Non-negotiables

- **Offline-first, not offline-only.** Every feature must work with no internet: the local archive is the source of truth and is filled over the device link — BLE always, LAN (protocol v2: one TCP socket to a device found via mDNS `_aqsync._tcp`, authenticated with a token obtained over BLE) when available. Both are transports behind `AqTransport`; add new ones (LoRa relays, …) there and never fork the sync engine. Cloud backup/sharing is a later **opt-in** step that runs *after* local sync, is off by default, and never gates local use. `INTERNET` exists **only** for LAN sockets to devices discovered via mDNS `_aqsync._tcp`; the code must never connect to an address it did not get from that discovery. **Any cloud endpoint still requires an explicit opt-in setting and its own review** — `INTERNET` being declared does not pre-approve it. No location permission, no analytics or crash-reporting SDKs, no accounts. `ACCESS_LOCAL_NETWORK` is requested at runtime only on API 37+ and its denial must leave BLE fully working. Follow the privacy boundaries in `../m5stack-aq-parquet/product/mobile-architecture.md`.
- **Never log the LAN token.** It is a bearer secret for anyone on the network. It lives only in `known_device.lanToken` (Room); `Frame.Token`, `KnownDevice`, the frame recorder and the raw-command view redact it. The same goes for `wifi.psk`: write-only, never echoed, never logged (`SET_CONFIG` requests are recorded as opcode only).
- **Never present unverified data.** A file is synced only after size, CRC-32 (`OPENED.crc32`) and `PAR1` head/tail all match. Absent JSON keys are `null`, never `0`. A lost `CHUNK` (offset gap) or a `READ_END.next_offset` that disagrees with our byte count fails the **window**, never the sync: `DeviceSession.read` drains that request to its `READ_END` and throws `WindowLostException`, `SyncEngine` re-reads from *our* last good offset (≤ 5 retries) and only then marks the *file* failed. Do not trust `next_offset` over the received byte count, and do not shrink the BLE `READ` window without a measurement — `data/sync/ReadWindow.kt` records why.
- **Never ask the device to delete, rename or rewrite.** Sync is a pull of immutable files. The state-changing requests — `SET_TIME`, `FLUSH`, `SET_CONFIG`, `REBOOT` (and `ble.clear_bonds` / `lan.rotate_token` inside `SET_CONFIG`) — must be explicit user actions with an explanation, and the destructive ones (reboot, rotate token, forget bonds, pairing mode `none`) need a confirmation dialog. `REBOOT` has exactly one call path — `DeviceSession.reboot()` ← `DeviceConnectionRepository.rebootAndReconnect()` ← the Device screen's confirmation dialog — and is refused while a sync runs; the Debug raw-command box refuses `REBOOT` and `SET_CONFIG`. Keep it that way (a `REBOOT` of uncertain origin reached the board once; see the README audit row).
- **Keep the Hive path verbatim** under `archive/<station>/…` so the phone archive mirrors the SD card and the firmware repo's `tools/export_parquet.py` output.
- **One GATT operation at a time**, serialized in `BleTransport`; one request at a time per session, serialized in `DeviceSession`. Transport work belongs in the repository/session layer, never in a composable or Activity. On LAN, `STATUS`/`LIVE` pushes may arrive between two response frames: demultiplex by type byte in the transport, never in the session.
- **Background sync = WorkManager + the `dataSync` foreground notification, never a bare thread.** Anything that must outlive the screen (auto-sync, "Sync now in background") is a `SyncWorker` run scheduled by `AutoSyncScheduler` (periodic 15 min, the platform minimum, or one expedited one-time request) that calls `setForeground` with `SyncNotifications.progress` — the same notification the user-started `SyncForegroundService` shows. No `Thread`, no `GlobalScope`, no alarm, no sticky service, no CPU wake lock — the one sanctioned use of the `WAKE_LOCK` permission is the `WifiManager.WifiLock` in `data/lan/LanWifiLock`, held only while a transfer runs over the LAN pipe (never over Bluetooth, never while idle) and released in the sync's `finally`. Decision logic stays in `AutoSyncRunner` (pure, unit-tested over the fake device); Android plumbing stays in the worker and `AppAutoSyncGateway`. The worker never pairs (`allowBonding = false`), never scans, never touches a device the repository has a session with, and posts a summary only when something new arrived.
- **Advanced mode is opt-in and cosmetic.** The Debug destination shows raw data (status JSON, frames, device log, raw commands) and exists only while the DataStore switch is on. Nothing the Simple experience needs may depend on it.
- `minSdk` stays **31+** (modern Bluetooth permission model). Do not add legacy permission branches.

## Provenance

Generated from Google's `android/architecture-templates` (`base` branch) with `customizer.sh earth.walkthru.aqsync Device "AQ Sync"`; the untouched output is commit `34f2d30`. Keep the template's structure (Hilt, Room, Navigation 3, version catalog) rather than importing another architecture.

## Build and verify

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
tools/setup-hooks.sh                 # once per clone: installs the git hooks from .pre-commit-config.yaml
./gradlew --no-daemon clean verify   # = :app lintDebug + assembleDebug + testDebugUnitTest + every :core module's tests
```

**Hooks and CI.** `.pre-commit-config.yaml` runs on every commit (seconds): whitespace/EOL, YAML/JSON/TOML
syntax, no large files, no private keys, **gitleaks** secret scan, and **ktlint** (format + lint on the
staged Kotlin files with the rules in `.editorconfig`: Android Studio style, no line-length limit, the
house-style rules — `{ a; b }` one-liners, compact `when`, one-line enums, `_x` backing fields —
switched off; a hook that reformats a file fails the commit once, re-stage and commit again). The
**pre-push** hook runs the full `./gradlew verify`. `.github/workflows/verify.yml` runs both on every push
and PR and uploads the debug APK; `release.yml` publishes a universal release APK to the rolling
`latest` pre-release on every push to `main` and to a permanent release on a `v*` tag (signed with the
project key only when the `KEYSTORE_*` secrets exist, otherwise with the debug key and it says so). CI
has no phone and no emulator: BLE, mDNS, the foreground service and WorkManager plumbing are verified
only on hardware and recorded in the firmware repo's `bench-verified.md`.

Everything in `verify` must pass before a commit: lint errors fail the build by design; do not add lint warnings carelessly (7 pre-existing ones are known). Unit tests under `app/src/test/` use payloads captured from the real device (`AQ-6b40`, firmware `arduino-cores3-parquet-v4`) for v1 and hand-assembled fixtures from the contract for v2; keep them in sync with the contract and replace v2 fixtures with captures (a v2 device *has* been read from the phone, but no capture was taken yet). Behaviour tests run the real `DeviceSession`/`SyncEngine`/`AutoSyncRunner` against `FakeTransport` and its fault hooks (`dropLeadingChunks`, `closedDelayMs`) — extend the fake rather than mocking. The `TcpTransport` loopback test is the only automated coverage of the LAN pipe; `LanDiscovery`, `LanWifiLock`, the foreground service and the worker plumbing are only testable on a phone. Bump `gradle/libs.versions.toml` only to versions you have verified build, and note the date in `README.md`.

## Layout — three pure modules under one Android app

```
core/protocol/   pure JVM  data/protocol: UUIDs, frame codec v1+v2, CONFIG model, LAN framing, JSON, Parquet verify  (contract mirror)
core/parquet/    pure JVM  data/history/parquet: reader, writer, Thrift compact, LZ4 block; src/test/resources/parquet = the fixtures
core/history/    pure JVM  data/history: file index, metric registry (Metrics), Aggregator → tiers, TimeEstimate, Columns, AirMood, history model
app/src/main/java/earth/walkthru/aqsync/
  data/ble/       scanner, GATT transport, session (all ops), frame recorder  (Android BLE only here)
  data/lan/       TCP transport, NsdManager discovery, WifiLock, token policy  (Android net only here)
  data/sync/      planner, engine, archive, READ window, resume policy         (files + Room)
  data/history/   HistoryIndexer, HistoryDeriver, DailyDerivedBuilder, HistoryRepository  (Room + files around the pure core)
  data/background/ dataSync foreground service, notifications, auto-sync worker + scheduler + runner
  data/local/     Room (schema v7)          data/settings/  DataStore          data/export/  share/zip/SAF
  data/fake/      debug-only fake device    ui/             Compose screens (scan, device, history, files, debug, settings)
```

Rules that keep the modules honest:

- **`:core:*` never imports Android, Hilt, Room or `android.util.Log`.** They are plain Kotlin/JVM with kotlinx.serialization at most; their tests are plain JUnit. If a class needs a DAO, a `Context` or a `File` under `filesDir`, it belongs in `:app` (pattern: `HistoryIndexer` wraps `ParquetIndexer`; `ArchivedFile.info()` maps the Room row to the pure `IndexedFileInfo`). These modules are what the iOS app transliterates and what a hub or laptop tool can reuse unchanged.
- **A new sensor type is a registry entry, not code.** `Metrics` (`:core:history`) lists every quantity as a `MetricSpec` (column, unit, validity gate, display bands, chartable, envelope, axis floor). Tiers (`metric_bucket`, one row per metric), daily derived files, the History picker and the charts iterate the registry and never name a column. Column names follow the firmware's field dictionary; a spec whose column a file lacks yields no values, never zeros, so the registry may be ahead of the hardware (`AMBIENT_TEMP`, `HUMIDITY` are listed while the SHT20 is unresolved).
- **A new analysis is a pure object in `:core:history`** with a test over the real fixtures (`core/parquet/src/test/resources/parquet`, regenerated by `tools/make_parquet_fixtures.py`, PyArrow as oracle), fed by `:app` from Room/files — like `Aggregator`, `TimeEstimate`, `Columns`. Never a query inside a composable.
- **Firmware updates (OTA), when the contract exists,** follow the same seams as every other device action: opcodes/frames in `:core:protocol` mirroring `../m5stack-aq-parquet/docs/ble-sync-protocol.md` (owned there — do not invent them here), the session op in `data/ble/DeviceSession`, a `data/firmware/` updater in `:app` that verifies the image (size, SHA-256 from a signed manifest, target model/version from `info`) **before** any byte reaches the board, and a Device-screen confirmation dialog like `REBOOT`/rotate token. It is refused while a sync runs, never runs from the worker, and never over an unverified link. Gate features on `info` (`proto`, `fw`), not on guesses.
