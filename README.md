# AQ Sync — Android companion for the CoreS3 air-quality logger

Native Android app that pairs with an **M5Stack CoreS3 (ESP32-S3)** running the
[`m5stack-aq-parquet`](../m5stack-aq-parquet) firmware, shows its live readings, gives it UTC,
and copies its finalized **Parquet** files into the phone — byte-exact, verified, offline.

**Offline-first, not offline-only.** Everything works with no internet at all: the phone's archive is
the source of truth, filled over whichever link the device offers — **Bluetooth LE** always, and since
protocol v2 a **LAN** link (one TCP socket on your own Wi-Fi network, discovered with mDNS, authenticated
with a token the phone can only obtain over the bonded BLE link). Cloud backup or sharing is a *later,
opt-in* step that runs after local sync and stays off by default; if the device or the phone has Wi-Fi
or a SIM, that is a convenience, never a requirement. The app declares `INTERNET` **only so that LAN
sockets exist**: the code connects exclusively to addresses obtained from mDNS discovery of
`_aqsync._tcp`; there is no cloud endpoint, no location permission, no analytics, no accounts. Data
can leave the phone through the system share sheet or the file picker.

## Status

**Implements protocol v2 (2026-09-17) plus Auto-sync (2026-09-17); the BLE and LAN file sync
have run against the board from a phone (OnePlus 7 Pro, Android 16) — see the "After the first
phone run" decisions — Auto-sync has not yet.** Verified on 2026-09-17:
`./gradlew assembleDebug testDebugUnitTest lintDebug` → 0 lint errors (7 pre-existing warnings),
88/88 unit tests (v1+v2 frame codec, CONFIG JSON + `SET_CONFIG` builder, LAN framing with a `LIVE`
push between two `CHUNK`s, the real `TcpTransport` against an in-JVM fake device over loopback,
JSON models, Parquet verification, sync planner, lost-window recovery, the resume-after-lost-link
decision, the auto-sync worker's decision logic over the fake device), debug APK ≈ 65 MiB unminified.

**Restructured into modules (2026-09-17):** `:core:protocol`, `:core:parquet`, `:core:history` are pure
Kotlin/JVM; `:app` is Android. A metric registry (`Metrics`) replaces hard-coded PM columns and the
tiers are one row per metric (Room v7), so a new sensor is a registry entry. `./gradlew verify` is the gate.

**History, steps 1–5 (3c folded into 3b) and §4.6 of [`docs/history-ux-and-data-layer.md`](docs/history-ux-and-data-layer.md)
(2026-09-17):** a pure-Kotlin reader for the firmware's Parquet subset (`data/history/parquet`) that
decodes all 881 columns of 21 fixture files — nine real `AQ-6b40` files (v1 73-col and v2 77-col,
UNCOMPRESSED and LZ4_RAW, dated and unsynced) and twelve firmware-v6 writer fixtures (up to 8 row
groups, statistics, `TIMESTAMP(NANOS, UTC)`) — byte-identically to PyArrow in 0.15 s total; Room v4
indexes every verified file (rows, row groups, schema version, UTC/uptime/sequence spans, PMS-valid
rows) at sync time and back-fills older rows; an Advanced-only **Metrics** screen shows a file through
that reader; Room v5 adds the 15 min / 1 h / 1 day aggregate tiers rebuilt per station-day from the
verified files (`HistoryDeriver`), which also writes the **derived daily Parquet** files
(`derived/v1/<station>/day=….parquet` + manifest) with the app's own `ParquetWriter` — validated by
PyArrow and DuckDB via `tools/validate_parquet_samples.py`; a **History** tab (day/week/month/year, `Canvas` chart with band
shading, envelope, gaps and coverage) reads those tiers, or the rows for a day, through
`HistoryRepository`; the Today screen opens with the friendly **Now card** (five moods, advice,
comparison, "today so far" from the tiers) and the Boolean Advanced switch became **Simple ·
Detailed · Developer**; Home cards summarise each logger from the archive and **Collect from all
loggers** runs the auto-sync worker over every known device; unsynced files of a boot that later got
the time are dated by `TimeEstimate` and drawn dashed. **The first file firmware v6 wrote on the board** (`data_0930_495ccef9…_2-106-2.parquet`,
2026-09-17 09:42–09:59Z, 105 rows in two row groups of 90 + 15, SHA-256 `1a06e93b…f3cf`, fetched over
serial by the firmware-side session) is a committed fixture: the reader decodes it, the index takes
its spans from the footer statistics, and every integer column's statistics agree with the decoded
rows per row group (`RealArchiveTest`); the board's second, **full 1800 s window**
(`data_1000_…_107-286-3.parquet`, 180 rows in 2 × 90, SHA-256 `a8ceaebe…`) is a fixture too, and the
deriver folds both into one derived day of 285 rows in two hourly row groups (188 B/row against 512
B/row for the device files) that DuckDB reads and buckets. The same test, where the firmware checkout
sits next to this repo, sweeps **all 766 real files** on this machine (67,272 rows, 58,868 column
chunks, 136 statistics cross-checks) in 0.2 s on the JVM. 123 unit tests (46 `:app`, 77 `:core`), 0 lint errors (7 pre-existing warnings). Not yet run *on the phone*
against a v3 file — that needs a BLE/LAN sync of the board's new files and goes into the firmware
repo's `bench-verified.md`.

The v1 file-sync part of the protocol *is* bench-verified: the same contract was exercised from a
macOS host client against the real board on 2026-09-16 (pairing, 766-file manifest, verified
transfers at 15–21 KB/s, time set, flush). The v2 additions — configuration, Wi-Fi provisioning,
reboot, log tail, token, LAN transport — are implemented from
[`docs/ble-sync-protocol.md`](../m5stack-aq-parquet/docs/ble-sync-protocol.md) and the firmware
headers, with no v2 device capture yet; the fixtures in the tests are hand-assembled from the
documented layouts. On the phone (OnePlus 7 Pro, Android 16 = API 36) `LanDiscovery` found the
board via `NsdManager` and the app chose TCP on its own; the `ACCESS_LOCAL_NETWORK` prompt is only
enforced from API 37 and is still unverified. Not yet measured from the phone: an instrumented full
776-file sync over each link, a complete Auto-sync period cycle, `ble.clear_bonds`, `lan.rotate_token`
end to end, the `fixed`/`none` pairing modes. See
[`docs/bench-verified.md`](../m5stack-aq-parquet/docs/bench-verified.md#phone-over-lan-and-ble-oneplus-7-pro-android-16)
in the firmware repo for what was measured, on which firmware and app commit.

## What it does

| Screen | Behaviour |
| --- | --- |
| **Devices** (Home) | Scans for the AQ Sync service UUID (`c0a5e9f0-0001-4b1a-9c3e-2d7f8a6b4e01`), lists `AQ-xxxx` devices with RSSI and bond state, remembers known devices. Each known logger's card says what the **phone archive** holds for it — files, last sync, and the last UTC day with valid readings as a mood + average + coverage — with no link needed. With two or more known loggers, **Collect from all N loggers** runs one background pass over every known device (LAN when discovered and a token is held, else its Bluetooth bond, one at a time, only missing files), with the same `dataSync` notification and summary as Auto-sync; a logger you have open is skipped. |
| **Today** | Opens with the **Now card**: a five-mood character (Canvas placeholder for the capybara artwork) on the PM2.5 bands, a plain title ("Fresh air" … "Thick smoke"), one line of advice, a familiar comparison, the PM2.5 number labelled as a 10-second snapshot, and **Today so far** — the UTC day's real average with its coverage from the archive tiers, or a nudge to give the logger the time. The sensor's own state (warming up, stale, error) is shown in words before any number. **Detailed** adds PM1 / PM10, particle counts, board temperature (explicitly *not* ambient), battery/USB, storage counters (firmware v6 adds the rows/row groups already on the card in the open file, `status.open`/`open_rg`, shown only when the key is present) and the device card. Always: UTC state, **Set time from phone** and **Finalize buffered rows**, each explained before it runs. |
| **History** | Day · Week · Month · Year of one logger **from the phone archive** (no session needed), whole local days. **Day**: a 24-hour **ring** (96 quarter-hour segments coloured by their band, midnight at the top, a tick for now, the day's average and mood in the middle) above quarter-hour **columns**. **Week**: hourly columns. **Month / Year**: a **calendar heatmap** (one cell per UTC day, tap to open it) above daily / weekly columns. Every column is coloured by its own band, faded by its coverage, hollow-dashed on estimated time, with a peak whisker; the y-axis follows the 95th percentile (PM2.5 always shows 0–60) and off-scale spikes become a small cap stating their value instead of flattening the day; the WHO 24-h guideline (15) is a dashed line. Touch or drag a column to read it ("13:15–13:30 · 52 µg/m³ · Getting hazy · peak 63 · 100 % covered"). Average / peak / coverage for the span, paging back in time. Metrics: PM2.5, PM10, PM1, board temperature. |
| **Files** | Device manifest grouped by Hive partition (dated / unsynced-boot / benchmarks / legacy). **Sync all** downloads every file the archive lacks, with per-file and overall progress. Per-file state: not synced · syncing · synced (verified) · mismatch. Menu: **Sync now in background** (one expedited WorkManager job — leave the app, watch the notification) and a quick **Auto-sync** toggle for the connected device. |
| **Export** | Share one file or a zip of the raw archive via the share sheet, or save a zip with *Save to…* (Storage Access Framework). **Share daily files (derived)** zips `derived/v1/<station>/day=….parquet` + manifests — the small, analysis-ready export (one Parquet per station-day, statistics, typed timestamps); the raw zip stays the byte-exact mirror. |
| **Device** | **Auto-sync** card (any protocol version): a per-device switch; while any known device has it on, a WorkManager job runs about every 15 min — app closed, screen off — finds the logger on the LAN (mDNS) or over its Bluetooth bond, pulls only the files the archive lacks under a `dataSync` progress notification, and posts one summary ("Synced 23 new files from AQ-6b40 · 1.1 MiB") only when something arrived. |
| **Device** (v2) | Bluetooth pairing mode (random passkey / fixed PIN / none) with the "Default PIN 123456 — change it" nag, pending-reboot notice and **Reboot device** (finalizes the RAM batch, restarts, app reconnects). **Wi-Fi**: state / IP / RSSI, *Scan networks* → pick → hidden passphrase → *Save and connect*, state polled while `connecting`, radio on/off. **LAN**: server on/off, whether this phone holds a token, *Fetch token* (Bluetooth only, by contract), *Rotate token*, local-network permission explanation on Android 16+. **Forget all bonds**. Every write is one validated `SET_CONFIG`. |
| **Settings** | A sheet with one choice: **Detail level** — Simple (default) · Detailed · Developer (DataStore; an old `advanced_mode = true` reads as Developer). Cosmetic: every level reads the same data. |
| **Metrics** (Detailed level, from a synced file's table icon on Files) | One archived file opened by the app's **own Parquet reader**: rows, row groups, schema/writer version, codec, footer size, UTC and uptime span, sequence range, PMS-valid rows, per-row-group spans from v6 footer statistics, every column decoded (nulls, min, max, first, last, "footer agrees") and the file's key/value metadata. Decodes all 77 columns in a few ms; the archive copy is never touched. |
| **Debug** (Developer level) | Active transport + endpoint + `payload_max`, live `status` JSON pretty-printed, last 200 raw frames (direction, type, length, first bytes; `TOKEN` and `SET_CONFIG` redacted), **Device log** (`LOG_TAIL 8192`) in a monospace box with refresh, **Raw command** (hex in → decoded frames out; `REBOOT` and `SET_CONFIG` are refused — they need the Device screen's confirmation). |

The top bar of Today, Files, Device and Debug shows a small chip naming the active link: **Bluetooth** or **Wi-Fi LAN**. It follows `DeviceConnectionRepository.activeTransport`, one flow for every screen, so a reconnect over the other pipe changes it everywhere at once.

### Transports

Both links carry the same requests and frames; only the byte pipe differs, so the sync engine,
archive and verification are shared (`AqTransport` → `DeviceSession` → `SyncEngine`).

| | Bluetooth LE (`data/ble/BleTransport`) | LAN (`data/lan/TcpTransport`) |
| --- | --- | --- |
| Discovery | GATT scan for the service UUID | mDNS `_aqsync._tcp` via `NsdManager` (`data/lan/LanDiscovery`), TXT `station`/`dev`/`fw`/`proto` |
| Auth | LE Secure Connections bond (passkey / PIN / none per `ble.pair`) | `"AQS1"` + 32-byte token, obtainable **only** over BLE (`GET_TOKEN`; `ERROR` 13 over LAN); a token the device rejects (`ERROR` 14) is dropped and fetched again on the next BLE session (`data/lan/LanTokenPolicy`) |
| Framing | one notification per frame, `payload_max = min(MTU − 3, 512)` (517 → 512; Android silently drops notifications above the 512-byte GATT attribute limit) | `len` u16 LE + frame, `payload_max` 1024 from `HELLO`; `mtu` reports 1027 so the same arithmetic holds |
| Pushes | `status` / `live` characteristics | `STATUS` 0x48 / `LIVE` 0x49 frames, demultiplexed by type byte off the single reader loop; may sit between two `CHUNK`s |
| Liveness | GATT link state | `STATUS` (0x07) keep-alive after 120 s idle; link declared lost after 180 s without any byte |
| Lost link | 3 automatic reconnect attempts, 5 s apart, with the normal transport rule; a sync that was running (or had just died of the pipe) resumes — see "After the first phone run" | same |
| Kept alive by | the `dataSync` foreground service for the duration of a transfer, nothing while idle | the same service plus a `WifiManager.WifiLock` held only while a LAN transfer runs (`data/lan/LanWifiLock`) |
| Chosen when | always available | the device's station is currently discovered on the network **and** a token is stored; any LAN failure falls back to BLE with a snackbar |

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
| DI / data | Hilt 2.60.1, Room 2.8.5 (schema v3), DataStore Preferences 1.2.1, WorkManager 2.11.2 + androidx.hilt:hilt-work 1.4.0, kotlinx.serialization 1.11.0, coroutines/Flow |
| Build | AGP 9.4.0, Gradle 9.7.1, KSP 2.3.12, version catalog |
| SDK | `compileSdk`/`targetSdk` **37**, `minSdk` **31** (no legacy Bluetooth permission model) |
| Permissions | `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_CONNECT`, `INTERNET` (LAN sockets only), `ACCESS_LOCAL_NETWORK` (runtime, API 37+ only), `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS` (runtime, API 33+), `WAKE_LOCK` (only for the `WifiLock` during LAN transfers; no CPU wake lock anywhere) |

## Build

Android Studio (latest stable) opens the project directly. From the command line, use the JBR
that ships with Studio (JDK 25) or any JDK ≥ 17:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
tools/setup-hooks.sh                  # once: git hooks (ktlint, gitleaks, hygiene on commit; verify on push)
./gradlew assembleDebug testDebugUnitTest
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Ready-made APKs:** every push to `main` refreshes the rolling
[`latest`](../../releases/tag/latest) pre-release (one universal APK — the app has no native code, so
one file serves every ABI on Android 12+ — with `SHA256SUMS.txt`), and a `v*` tag publishes a permanent
release (`.github/workflows/release.yml`), signed with the project key (`CN=AQ Sync, O=walkthru.earth`,
SHA-256 `b039d9e8…f04f3f7`). `tools/setup-signing.sh` generated that key into the git-ignored `secrets/`
(`release.jks` + `.env` with the `AQSYNC_*` variables the build reads) and uploaded the four Actions
secrets; `set -a; source secrets/.env; set +a; ./gradlew :app:assembleRelease` signs a local release the
same way. Back the keystore up — without it no future release installs over an existing one.

Debug builds include a **fake device** (menu → *Debug: fake device*) that speaks protocol v2: three
small `PAR1…PAR1` files plus one 1 MiB benchmark file (≈ 8 s at the fake's chunk pace — enough to
watch the progress notification, the Stop action and a `.partial` resume), all with correct CRCs,
live JSON every 10 s, a fake configuration (pairing,
Wi-Fi, LAN), a fake Wi-Fi scan list (`home` / `walkthru-field` / `CoffeeShop Guest` can be joined;
anything else ends in `failed`), a fake token, a fake 8 KiB serial log, and a `REBOOT` that drops the
link and applies pending `ble.*` changes. The fake persists for the process, like NVS. It also
carries the fault-injection hooks the unit tests use (`dropLeadingChunks`, `closedDelayMs`). A second entry,
*Debug: fake device (as LAN)*, is the same device behaving like the LAN pipe (`payload_max` 1024,
`GET_TOKEN` refused with `ERROR` 13) so the LAN-specific UI can be seen without a network. It is
**not** a socket: the real `TcpTransport` is covered by a loopback unit test and otherwise only
testable on hardware.

## Layout

Three pure Kotlin/JVM modules (no Android, no DI — what the iOS app transliterates and what a laptop or
hub tool can reuse) under one Android app. `./gradlew verify` builds and tests all of them.

```
core/protocol/   contract mirror: UUIDs, frame codec v1+v2, CONFIG model + SET_CONFIG builder, LAN framing, JSON models, Parquet verification
core/parquet/    the firmware's Parquet subset: memory-mapped reader, writer (statistics, TIMESTAMP, LZ4_RAW), Thrift compact, LZ4 block; test fixtures
core/history/    file index, metric registry (`Metrics`), `Aggregator` → tiers, `TimeEstimate`, `Columns` (chart binning/axis), `AirMood`, history model
app/src/main/java/earth/walkthru/aqsync/
  data/ble/        scanner, GATT transport (serialized operation queue), session (all ops), frame recorder
  data/lan/        TCP transport (AQS1 handshake, reader loop, keep-alive), mDNS discovery, WifiLock, token (re)fetch policy
  data/sync/       sync planner, engine (lost-window re-read), file archive, READ window size, resume-after-lost-link policy
  data/background/ dataSync foreground service + notifications; auto-sync WorkManager worker, scheduler, runner (decision logic)
  data/local/      Room database (known devices incl. LAN token + auto-sync switch, archived files + history index + estimated time, per-metric tiers, derived days; schema v7)
  data/history/    the Room/files side of history: HistoryIndexer (+ back-fill, time-estimate pass), HistoryDeriver (tiers + daily files per station-day), DailyDerivedBuilder, HistoryRepository
  data/settings/   DataStore preferences (Advanced mode)
  data/export/     share / zip / SAF export
  data/fake/       in-app fake device for debug builds
  ui/scan · ui/device (Today, Device) · ui/history (History chart, file Metrics) · ui/files · ui/debug · ui/settings · ui/permissions · ui/theme
app/src/test/      frame codec v1/v2, config, LAN framing, TcpTransport loopback, verification, JSON, planner, resume-after-lost-link policy, lost-window recovery + stale-frame drain against the fake device, auto-sync runner (links, guards, "nothing new") over the fake device, Parquet reader against PyArrow (`resources/parquet/`), indexer
tools/validate_parquet_samples.py  PyArrow + DuckDB oracle for files the app's ParquetWriter produced (app/build/parquet-samples/ after the tests, or a phone's derived/ copy)
tools/make_parquet_fixtures.py   regenerates `app/src/test/resources/parquet/` (9 real device files + 12 firmware v3 fixtures + PyArrow `expected.json`); run in the firmware repo's pixi env
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
| `READ_END.next_offset` ≠ bytes actually received, or a `CHUNK` skips ahead | The **window** failed, not the sync. `DeviceSession.read` keeps consuming (and discarding) that request's frames until its `READ_END`, then throws a typed `WindowLostException`; `SyncEngine` re-issues `READ` from its *own* last good offset (absolute offsets, immutable file → idempotent), up to 5 retries with a 300 ms × attempt backoff, and counts each recovered window (`FileProgress`/`SyncProgress.windowsRecovered`, shown on the Files sync card as "recovered N lost windows"). Only when one window fails 6× in a row is the *file* marked `Failed` and the next file is tried. The device's count is never trusted over ours. `ProtocolViolationException` is reserved for malformed streams (wrong handle, undecodable frame). |
| A request failed or was cancelled before its terminal frame (timeout, stray frame, a `READ` stopped mid-stream, a `CLOSED` that missed its 3 s budget) | Before the next request `DeviceSession.drainStale()` consumes frames until the failed request's own terminal frame (`READ_END`, `CLOSED`, `LIST_END`, … or an `ERROR` for it), a 2 s quiet period, or 15 s — whichever first — so a late reply is never taken for the next request's answer ("unexpected Closed frame in response to LIST" was seen on a phone). A `WindowLostException` or a device `ERROR` leave the channel clean, so nothing is waited for. |
| `READ` window size | The device's `max_read` (16 KiB) on **both** links; `data/sync/ReadWindow.kt` is the one place it lives, with the reason BLE is *not* given a smaller window (see the lesson below). |
| `info.max_read` missing | Falls back to 4096 (the firmware always sends 16384; dead code kept as a guard). |
| `bat`/`pct` in `live` | Shown raw with a "board report, unqualified" label; 16,372 → 23 mV was observed with no battery attached. |
| One outstanding GATT operation | Enforced with a `Mutex` around every read/write/descriptor call; Android's `BluetoothGatt` silently fails on concurrent operations. |
| Bonding | `createBond()` before first characteristic access if unbonded; GATT status 5 / 15 / 133 while pairing is in progress → wait for `BOND_BONDED`, then retry the operation once. |
| Downloads and rotation | Session and sync state live in a process-scoped repository, not the Activity, so a rotation mid-sync does not abort the transfer. |

Protocol v2 (2026-09-17):

| Question | Decision |
| --- | --- |
| Local-network permission on Android 16+ (API 37) | **Path B — the broad `ACCESS_LOCAL_NETWORK` runtime permission**, per [developer.android.com/privacy-and-security/local-network-permission](https://developer.android.com/privacy-and-security/local-network-permission), not the per-device picker (`DiscoveryRequest.FLAG_SHOW_PICKER`): the app manages devices persistently and reconnects to them on its own. It is in the NEARBY_DEVICES group with the Bluetooth permissions, so it is usually granted with them; the LAN card still checks and asks before any LAN action, and a denial hides LAN with an explanation while BLE keeps working. Below API 37 it is never requested. mDNS browsing runs only while the app is started (`LifecycleStartEffect`) and the permission is held. |
| Why `INTERNET` is now declared | Sockets do not exist without it. It is used for exactly one thing: a TCP connection to a host/port obtained from mDNS discovery of `_aqsync._tcp` on the current network. No cloud endpoint exists; a future one needs its own opt-in setting and review. |
| Where the LAN token lives | `known_device.lanToken` in Room, hex, nullable, keyed by the device's Bluetooth address (schema v2, explicit `ALTER TABLE` migration, no destructive fallback). Fetched once automatically after a BLE session reaches Ready if absent (or if the last LAN handshake rejected it, see "After the first phone run"); never logged, never displayed (`KnownDevice.toString`, `Frame.Token.toString`, the frame recorder and the raw-command view all redact it). The database is part of Android's device-to-device transfer and — only when the phone can end-to-end encrypt it — of cloud auto backup (see the backup row below); *Rotate token* on the device invalidates every copy. |
| When LAN is preferred | The device's `station` (from the mDNS TXT record) is currently discovered **and** a token is stored → try LAN first; any failure (unreachable, handshake timeout, `ERROR` 14 auth, `ERROR` 7 busy, malformed `HELLO`) falls back to BLE and says so in a snackbar. `GET_TOKEN` and *Fetch token* force BLE. Identity stays the Bluetooth address on both pipes, so archive, Room row and UI are one device. |
| `HELLO.payload_max` ≠ 1024 | Trusted if ≥ 64; frames longer than it are a framing error that drops the link. Before `HELLO` the cap is the documented 1024. `mtu` reports `payload_max + 3`. |
| Zero-length or oversize LAN frame | Framing has been lost, so the only safe reaction is to close the socket (a resync could deliver a wrong `CHUNK`). A stream that ends mid-frame is an error too, not a clean EOF. |
| `SET_CONFIG` line format | `key=value
` per key, trailing newline included (the firmware's `apply_lines` skips empty lines). PIN must be six ASCII digits, SSID 1–32 bytes, PSK ≤ 63 bytes and no line breaks in either, validated on the phone before sending; `ERROR` 12 from the device names the key and nothing changed. `ble.pin` arrives as a number, so `012345` is shown zero-padded. |
| `WIFI_SCAN_END.status` ≠ 0 | Treated as the scan's error (code 15 → the typed "Wi-Fi unavailable"), not as a partial success; access points received before it are discarded. |
| `LOG` slices | Kept as bytes and decoded after concatenation (a slice boundary may split a UTF-8 sequence). `LOG_END.returned` ≠ bytes received is flagged in the UI as "a slice may be missing" but the text is still shown — it is for humans, not data. |
| `REBOOT` | After `REBOOTING delay_ms` the app waits `delay_ms`, drops the session, waits 6 s and reconnects with the normal transport rule. `ble.*` changes are shown as pending (`CONFIG.flags` bit 0) until then. |
| LAN `readLive()` | Nothing can request a `LIVE` push, so it returns the latest push or waits ≤ 15 s for the next sample. `readStatus()` returns the latest push or sends `STATUS` (0x07) and waits ≤ 10 s. |
| Fake "LAN" | `FakeTransport(simulateLan = true)` is not a socket; it only reports `payload_max` 1024 and refuses `GET_TOKEN`. The real transport is unit-tested over loopback and otherwise only on hardware. |
| What Android backup carries (2026-09-17, `res/xml/data_extraction_rules.xml`) | **Cloud (Auto Backup): the Room database only, and only with `disableIfNoEncryptionCapabilities`** — Auto Backup is capped at 25 MB per app and skips the *whole* backup above it, and a full archive is ≈ 32 MB, so the old rule that included `archive/` had silently stopped backing up anything. The database (known devices, index, tiers, LAN token) is what a new phone needs to find the logger and pull the files again; the token is LAN-scoped and rotatable, and restoring it is what keeps LAN sync alive since Bluetooth bonds never transfer. **Device-to-device transfer** (cable / local, no quota, no server) carries the archive too. `fullBackupContent`/`backup_rules.xml` was removed: Android 12+ (our minSdk) reads only `dataExtractionRules`. |

Auto-sync (2026-09-17):

| Question | Decision |
| --- | --- |
| How often, and by what | One WorkManager `PeriodicWorkRequest` (`data/background/AutoSyncScheduler`), unique name, `KEEP`, **15 minutes** — the platform minimum (`PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS`); Android picks the moment inside each period, so "every 15 min" means "roughly, and later under Doze". It exists exactly while at least one `known_device.autoSync` row is on (the scheduler observes the count; the switch only flips the row) and is cancelled when the last one goes off. No alarms, no bare threads, no always-on service: the logger writes a file every 15 min anyway. |
| Constraints | **Battery not low only** (2026-09-17, after the first phone run). The original `NetworkType.CONNECTED` constraint was dropped: it needs *some* network, so a phone with Wi-Fi off and no mobile data never ran the periodic job even though the Bluetooth path needs no network at all. The LAN side is now skipped quickly inside the run instead: `AppAutoSyncGateway.browseLan` returns empty when no network with `TRANSPORT_WIFI` is up (active network first, else a ≤ 1 s `NetworkRequest` probe — an offline Wi-Fi LAN counts, internet is not required), so the 10 s mDNS browse never runs for nothing and Bluetooth is tried at once. "Sync now" carries no constraint either. |
| Which link, in which order | Same rule as an interactive connect (`AutoSyncPolicy.linksFor`): browse mDNS `_aqsync._tcp` up to 10 s (early exit once every wanted station is resolved); LAN when the device's station is found **and** a token is stored; else, and after a failed LAN connect, **Bluetooth only if the phone is bonded** — a direct `connectGatt` to the stored address, no scan, 15 s budget, `allowBonding = false` so a lost bond is a failure, never a passkey dialog nobody can answer. Not found → finish quietly until the next period. One mDNS browse per run serves every device. |
| The app is in use | The worker skips any device while the repository holds a session — the very device (interactive use wins) or another one (one link at a time) — and re-checks between links. "Sync now in background" releases the interactive session first, then enqueues; the worker's own session then feeds the same `sync` state, so the Files screen shows the worker's progress if it is still open. |
| Foreground work | `setForeground(ForegroundInfo(WORKER_ID, …, FOREGROUND_SERVICE_TYPE_DATA_SYNC))` with the same `SyncNotifications.progress` notification as the user-started service ("AQ-6b40 · 12/776 files · 1.8 MiB", Stop = WorkManager cancel intent), updated at ≤ 2/s. WorkManager's `SystemForegroundService` is merged into the manifest with `foregroundServiceType="dataSync"`, otherwise Android 14+ refuses the type. On Android 12+ `setForeground` may throw `ForegroundServiceStartNotAllowedException` when the app is in the background; the worker logs one line and continues as a plain job (a running job has network access for its own duration), so the transfer still happens and only the ongoing notification is missing. |
| Summary notification | Posted only when a run brought new files or left failures ("Synced 23 new files from AQ-6b40 · 1.1 MiB", tap opens the app; several devices → "from 2 loggers"). Nothing new → nothing posted. `POST_NOTIFICATIONS` is asked once when Auto-sync is switched on (same helper as the first Sync tap); a denial changes nothing but visibility. |
| Failures and retries | A device that is not reachable is not an error: one log line, next period. `Result.retry()` (exponential backoff from 5 min, at most 2 retries per period) only for transient conditions: an exception from a link, or a transfer that broke off before every planned file had an outcome (`BackgroundSyncResult.incomplete`). Per-file failures are not retried inside the period — those files are still missing from the archive, so the next run picks them up. |
| "Collect from all loggers" (2026-09-17) | The same one-time expedited request with `KEY_ALL_KNOWN = true`; `AutoSyncRunner.run(allKnown = true)` visits `known_device` ordered by last connection whether or not the Auto-sync switch is on (`AutoSyncGateway.allKnownDevices`). Everything else — LAN-then-bond order, one link at a time, "interactive use wins", summary only when something arrived — is the existing runner. Chosen over concurrent sessions (M2 in the evaluation): no new failure modes on BLE, and the engine, archive and Room are already per-file. |
| "Sync now in background" | One unique `OneTimeWorkRequest`, `setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)`, `KEEP`, with the target address as input data (works for a device whose switch is off). Expedited work may carry network/storage constraints only, so it has no battery rule. |
| OEM battery killers | The test phone is a **OnePlus 7 Pro**; OxygenOS/ColorOS are the worst offenders on dontkillmyapp.com: "Battery optimization → Don't optimize" is often not enough, the app may also need "Allow background activity" and to be excluded from the "deep optimization"/"sleep standby" lists, and a swiped-away app may still be killed. WorkManager survives that (the job is re-scheduled), a bare thread would not. Nothing is measured yet: the first field test should note how many of the 15-min periods actually ran. |
| Room v3 | `known_device.autoSync INTEGER NOT NULL DEFAULT 0`, explicit `MIGRATION_2_3`, schema JSON exported; the switch is preserved across every upsert the connect path does. |

After the first phone run (2026-09-17, OnePlus 7 Pro, Android 16):

| Question | Decision |
| --- | --- |
| The LAN sync died when the screen went off | Measured: ~2 min after screen-off, `ConnectivityService` logged "Destroyed live tcp sockets for uids={ours}" and `TcpTransport` saw "Software caused connection abort" 6 ms later — the background network restriction, not the link. Every user-started transfer now runs under `data/background/SyncForegroundService` (`foregroundServiceType="dataSync"`, started by the repository while the app is still in the foreground, so the API 31+ start rule holds). It owns no logic: it mirrors `sync`/`reconnecting` into one ongoing notification (files done/total, bytes, current file, progress bar, **Stop** → `cancelSync`), paced at ≤ 2 posts/s (`NotificationManager` sheds above ~5/s), and stops itself 1.5 s after nothing needs protecting. `onTimeout` (API 35+, the 6 h/24 h `dataSync` budget) cancels cleanly. It is **not** kept alive while the link is idle (battery); `POST_NOTIFICATIONS` is asked once on the first Sync tap (API 33+) and a denial only hides the notification. |
| A link that drops on its own | Before this there was no automatic path out of `Failed`, only the button. The repository watches each Ready session: `Failed`, or `Disconnected` without a `disconnect()` call (`closeSessionLocked` clears the slot first, so deliberate closes are never mistaken), starts **3 reconnect attempts 5 s apart** with the normal transport rule (LAN if still discovered, else BLE), then an error snackbar. The Today/Files banners say "Link lost (…). Reconnecting automatically, attempt n of 3…"; Stop on the notification cancels it. The foreground service stays up across the attempts (`foregroundNeeded = sync.running || reconnecting != null`). |
| The stored token was rotated by another client | Seen: `SET_CONFIG lan.rotate_token=1` from the Mac → the phone's LAN handshake got `ERROR` 14, fell back to Bluetooth as designed — and never fetched a new token, because one was still stored, so LAN would have stayed dead until reinstall. `tryLan` now treats `LanFailure.AUTH_FAILED` as proof the stored token is dead: it clears `known_device.lanToken` and marks the address stale (`data/lan/LanTokenPolicy`, pure, unit-tested); the BLE session that follows fetches `GET_TOKEN` through the existing one-time path and the snackbar says "LAN token renewed". Deliberate rotation and Forget clear the mark. Any other LAN failure keeps the token. |
| Wi-Fi power save during a LAN sync | Measured: with the screen locked, throughput fell from ~60 to ~12 files/min, then TCP stalled > 5 s and the transfer broke. `data/lan/LanWifiLock` holds one `WifiManager.WifiLock` from the first `OPEN` to the sync's `finally` — interactive sync and `SyncWorker` alike, both go through `startSync` — and **only while the active transport is LAN**; Bluetooth syncs hold nothing and the lock is never kept over an idle link. Mode is `WIFI_MODE_FULL_HIGH_PERF` on every supported API on purpose: on API 31–33 it is active with the screen off, while `WIFI_MODE_FULL_LOW_LATENCY` is only active with the screen **on** and the app in the foreground (AOSP `WifiLockManager` checks `IMPORTANCE_FOREGROUND`; a `dataSync` foreground service does not count). On API 34+ the framework turns a HIGH_PERF request into a LOW_LATENCY lock anyway (`high_perf_lock_deprecated`, default on), so requesting LOW_LATENCY first would only lose the 31–33 case. Honest consequence: **on Android 14+ this lock does not protect a screen-off LAN sync**; what does is the automatic resume after a lost link (next row). Needs the `WAKE_LOCK` permission; the app still holds no CPU wake lock. |
| Link lost right *after* the sync already died of the pipe | Seen: the device stopped answering, the sync ended with `TransportException("no response from device within 60 s")`, the link was declared lost a moment later, the auto-reconnect succeeded — with `resumeSync=false`, because the sync was no longer `running`; the user had to press Sync all again. `SyncProgress.endCause` now records how a run ended (`COMPLETED`, `STOPPED`, `TRANSPORT_FAILURE`, `FAILURE`) and `SyncResumePolicy.shouldResume` (pure, unit-tested) tells the link watcher to resume when the sync was running **or** ended with a transport-level failure (no response, link lost, socket error, transport closed) within the last 5 min (`TcpTransport`'s 180 s dead-link deadline plus margin). A user Stop, a completed run (device `ERROR`s and mismatches are outcomes, not a broken pipe) or any other failure is left alone. `SyncPlanner` skips verified files, so the resume only pulls what is still missing. |
| Who may send `REBOOT` (0x0B) | Audited after a `REBOOT` reached the board right after two `LIST`s during a phone session, with no certainty the owner had tapped anything. Result: `DeviceSession.reboot()` has exactly one caller, `DeviceConnectionRepository.rebootAndReconnect()`, whose only caller is the Device screen's "Reboot device" confirmation dialog — no sync-complete path, retry, `LaunchedEffect`, snackbar action or the foreground service's Stop intent can reach it. Two gaps were closed anyway: (1) the Reboot / Rotate token / Forget all bonds buttons were enabled **during a running sync** (`canEdit` did not look at the transfer) — now `canDisrupt = canEdit && !syncRunning`, and the repository refuses `rebootAndReconnect()` while `sync.running` and takes `busy` with a synchronous compare-and-set so a re-fired confirm cannot send a second `REBOOT`; the dialog's confirm also checks its own flag before calling. (2) Advanced → Debug's raw-command box could send any opcode, including `0b`; it now refuses `REBOOT` and `SET_CONFIG` (the ones that need a confirmation dialog) and points to the Device screen. Not a phone-side cause of an *unsolicited* reboot was found. |

History (2026-09-17, steps 1–2; the evaluation and the remaining steps are in `docs/history-ux-and-data-layer.md`):

| Question | Decision |
| --- | --- |
| How to read Parquet on the phone | **Own reader for the firmware's subset** (`data/history/parquet`): Thrift-compact footer, any number of row groups, Data Page V1, PLAIN, RLE definition levels (max level 1), INT32/INT64/FLOAT, UNCOMPRESSED or LZ4_RAW (own ~60-line block decoder), footer statistics and the TIMESTAMP logical type. Everything else — dictionary pages, Data Page V2, other codecs/types, nested schemas — is refused with a typed `ParquetFormatException` naming the feature. Kotlin DataFrame cannot read Parquet on Android (its Arrow Dataset JNI has no Android build) and DuckDB for Android is an experimental self-build; both are documented in the evaluation. |
| Zero-copy, concretely | `FileChannel.map` + absolute reads; only the requested column chunks are touched (3 of 77 for a PM2.5 chart); PLAIN pages are decoded once into `IntArray`/`LongArray`/`FloatArray` with a `BooleanArray` validity mask (`ColumnValues`), no boxing, no per-row objects. |
| Oracle for the reader | PyArrow, via `tools/make_parquet_fixtures.py` run in the firmware repo's pixi env: it copies real device files, compiles the firmware's own fixture writers for v3 files, and writes `expected.json` (footer facts, per-row-group statistics decoded from raw bytes, per-column null count/first/last/min/max/sum). The Kotlin tests compare every column of every file. Regenerate when the firmware writer changes; commit the result. |
| What the index stores (Room v4) | Derived from the file, never from its name: rows, row groups, `schema_version`, `boot_id`, `purpose`, UTC row count and span, monotonic span, sequence span, `pms_status == 4` count, plus `indexedAt`/`indexError`. Computed in `SyncEngine` right after verification (same upsert as the row) and back-filled lazily for older rows by `HistoryIndexer.backfill` (batches of 100, failures retried once per pass, never-indexed rows first). v6 footers answer the spans from statistics without decoding (`fromStatistics`); v1/v2 files decode three columns. |
| Duplicate rows across files | Both the tiers (`Aggregator`) and the daily file skip a row whose `(boot_id_hi, boot_id_lo, sequence)` was already counted for the day, so the two always agree. Device files never overlap; this guards against a re-synced copy under another name. |
| Benchmark copies | `purpose = codec-comparison-duplicate-rows` files are indexed like any other but excluded from every history query (`withUtcBetween` filters on `purpose`). |
| Modules (2026-09-17) | `:core:protocol`, `:core:parquet`, `:core:history` are pure Kotlin/JVM (`kotlin("jvm")`, Java 17 bytecode, kotlinx.serialization at most); `:app` keeps Android, Hilt, Room, files under `filesDir` and the UI. The boundary is enforced by the build: a `:core` class cannot import `android.*`. Package names did not change (`earth.walkthru.aqsync.data.*` spans modules) so the move was a `git mv`. The pure side receives plain records, never entities (`ArchivedFile.info()` → `IndexedFileInfo`); Room's KSP processor also refuses an entity that implements an interface from another module, which settled that question. |
| Metric registry (2026-09-17) | `Metrics` in `:core:history` lists every quantity as a `MetricSpec` — id, column, label, unit, kind, validity `Gate` (`pms_status == 4` for particles), display `Band`s (PM2.5 only; the same edges the capybara uses), chartable, envelope, axis floor. `Aggregator` accumulates every registered metric per bucket in one pass; the History picker is `Metrics.chartable`; charts colour by `spec.moodOf(value)`. Adding CO₂/VOC/noise = one line here plus a firmware dictionary column. Specs for columns the board does not populate yet (`AMBIENT_TEMP`, `HUMIDITY`) are already listed: they yield no buckets, never zeros. |
| Aggregate tiers (Room v7, was v5 wide) | `metric_bucket` at **15 min / 1 h / 1 day** (`Resolution`), **one row per metric**, identity `(station, metricId, resolution, start)`: rows in the slot, rows that passed the metric's gate, estimated-time rows, mean, min, max. `coverage = validRows / expected` (90 / 360 / 8,640) is carried so a bucket built from three readings says so. Rows without UTC are not on the timeline unless dated by `TimeEstimate`. Built per UTC station-day by `HistoryDeriver` (`Aggregator`, pure), replaced atomically per day. The wide `aggregate_bucket` is dropped by an `AutoMigration` `DeleteTable` spec and `HistoryDeriver.DERIVE_VERSION` (part of every day's signature) forces a rebuild — the tiers are a cache. |
| Derived daily Parquet (step 3b) | `derived/v1/<station>/day=YYYY-MM-DD.parquet` + `.manifest.json`, written by the app's own `ParquetWriter` (the mirror of the reader: PLAIN, RLE levels, LZ4_RAW with an own ~60-line block compressor, per-chunk `min_value`/`max_value` + exact flags + `null_count` + `nan_count`, TYPE_ORDER column orders, `TIMESTAMP(NANOS, UTC)` on the two `*_utc_ns` columns, `sorting_columns`, row-group `file_offset`/`ordinal`). Rows: every UTC row of the day from the verified device files, time-ordered, de-duplicated on `(boot_id_hi, boot_id_lo, sequence)`; **one row group per UTC hour**. Columns: the union of the sources (v1 files leave the four v2 columns null) plus `source_file_index` (INT32 into the manifest's `sources`). **Deviation from the evaluation's spec:** no `station_id` string column — the file stays flat numeric so the app's reader reads it back; the station is in the path and the metadata, as on the SD card. Metadata copies the invariant device keys and adds `derived_by`, `derived_version`, `source_count`, `coverage_rows`, `complete` (every known file in and the day ended > 30 min ago), `day`, `purpose = telemetry-derived-day`. Written atomically (temp + rename), never inside `archive/`. Validated by `tools/validate_parquet_samples.py` (PyArrow 25.0.0 + DuckDB 1.5.5 in the firmware repo's pixi env) on the writer's samples and on a day built from the board's v6 file: every statistic matches the values, DuckDB reads `TIMESTAMPTZ` and prunes a range query, `time_bucket` works. |
| No separate `DeriveWorker` | The evaluation planned a WorkManager `DeriveWorker` chained after sync. Not needed: `deriveStale()` (tiers **and** daily files) already runs inside `SyncWorker` after a run that brought files, in the repository's sync `finally`, and when Files/History open; a day costs milliseconds. A missing daily file marks its `derived_day` signature stale so the next pass retries it. |
| When a day is rebuilt | `derived_day` stores a SHA-256 over the day's sorted `name:crc32` source list; `deriveStale()` rebuilds exactly the days whose set changed (late file, re-synced mismatch, deleted file) and drops days with no sources left. Triggered after every interactive sync (repository `finally`), inside `SyncWorker` when a run brought files, and when Files opens (after the index back-fill). Both tables are caches: drop them and derive again → identical rows. |
| Friendly layer | `AirMood` (pure): five moods on the same PM2.5 bands the chart shades (edges 15 · 35 · 55 · 150 µg/m³; WHO 2021 24-h guideline = 15), each with a title, one line of advice and a comparison — colour is never the only carrier. `NowReading.of(live)` puts the sensor's state first: `pms_status` ≠ 4 shows words ("warming up"), never a number. "Right now" is labelled a 10-second snapshot; "Today so far" is the UTC-day tier's weighted average with its coverage. No "AQI" anywhere: a named index needs validated averaging rules (product notes). The face is a Canvas placeholder with the character's behaviour; the capybara artwork replaces `MoodFace` one-for-one on both platforms. |
| Detail levels | `DetailLevel` Simple · Detailed · Developer replaces the Boolean Advanced switch; `AppSettings.advancedMode` (= Developer) and `detailed` (≥ Detailed) stay as flows so existing consumers did not change. Simple: Now card, clock/actions. Detailed: every measurement card, the device card, the file Metrics inspector. Developer: the Debug destination and the History source card. Migration: `detail_level` unset and `advanced_mode = true` → Developer. |
| Chart redesign (2026-09-17, after the first phone run) | The first line chart failed on real data: one 379 µg/m³ spike set the axis to 600 and squashed a normal day into a flat grey line, and the eyebrow uppercased "µg/m³" to "MG/M³" (the lesson below, again). Replaced by three `Canvas` views that share `Columns` (pure, unit-tested): `rebin` folds a series into fixed slots with means weighted by covered seconds and `axisMax` takes the 95th percentile of column means with a floor (60 for PM2.5, so a hazy afternoon is never clipped) — spikes above are capped and labelled (one label per run). Colour carries the value (each column's own band), opacity carries coverage, a dashed outline carries "estimated time". No chart library: the same spec is Swift Charts `BarMark`s and a `LazyVGrid` on iOS. The ring is the shape the iOS widget can take. |
| History screen data path | Day → the rows (`withUtcBetween` + reader, 3 columns), binned to minutes above 3,000 points (a full day is 8,640 rows, so a day always bins to ≤ 1,440 points); Week → 15-min tier; Month → 1-h tier; Year → 1-day tier. Means over a span are weighted by valid rows, never averages of averages. The chart is a Compose `Canvas` (no chart library): the same spec is what Swift Charts will draw. Time labels in the phone's zone, buckets UTC-aligned. |
| Time for rows logged before the clock (§4.6 of the evaluation; Room v6) | `TimeEstimate` (pure): the **earliest fully-timed file of a boot** is its anchor; for that boot's unsynced files `utc ≈ anchor_utc + (monotonic_us − anchor_mono)`, never across a reboot, never overwriting `event_time_utc_ns`. Verified on the real boot `e452563e…` of 2026-09-08: the estimate reproduces the firmware's own stamp 43 min after the anchor **exactly**, and dates the two unsynced files to 19:29–19:40Z. Stored as `archived_file.estFirstUtcNs/estLastUtcNs/estAnchorFile` (index only, no decode), computed by `HistoryIndexer.estimateTimes()` at the start of every derive pass; the day signature includes the anchor, so a new anchor re-dates and rebuilds. Tiers carry `estimatedRows`; the daily file adds `event_time_utc_estimated_ns` (TIMESTAMP) with `event_time_utc_ns` left null and a `time_estimate` metadata note; the manifest names each source's `time_anchor`. The chart draws estimated stretches **dashed** and says so, with the drift assumption (±50 ppm) spelled out. Boots that never got the time stay off the timeline. |
| Sensor warm-up and settling (2026-09-17, researched against AirGradient `firmware-one-openair`, Sensor.Community `airrohr-firmware`, the Plantower datasheet and OpenAQ) | Firmware already nulls PM for uptime < 30 s (`pms_status = 1`, `pms_warmup_us = 30 000 000`) — the datasheet's minimum and stricter than both references: Sensor.Community wakes the sensor 15 s before a 5 s reading window every 145 s and discards the first cycle; AirGradient samples continuously, keeps moving averages, shows "Warming Up" only on the display, and applies the EPA-2021 humidity compensation to PM2.5 (piecewise in PM2.5 and RH — impossible here until an RH sensor works); OpenAQ ingests provider values and has no warm-up logic at all (that layer *is* this phone). **Measured on our archive** (DuckDB over 766 files, 14 boots with ≥ 10 min): after the gate, minute 0 deviates from minutes 5–10 by 2.5 µg/m³ mean-absolute (median +0.3), minute 3 by 1.7, minute 7 by 0.6 — a small drift, no spikes, inside one band. Decision: **no more data is discarded**; particle metrics carry `settleSeconds = 180` in the registry and `MetricAgg.settling` counts those rows per bucket, so an analysis or a future UI hint can show "n settling" the way "estimated" is shown today. Averages are not corrected for humidity (no RH) and no "AQI" is computed. |
| A file the reader refuses | Keeps its `archived_file` row (Files, Export, sync identity unaffected) with `indexError`; the Metrics screen says so. The archive is the source of truth, the index only describes it. |

## Lessons learned

- **`customizer.sh` cannot take a spaced application name.** `"AQ Sync"` was substituted into identifiers (`class AQ Sync`, `Theme.AQ Sync`, `AQ Sync.kt`). Use a single token (`AqSync`) or fix by hand right after generation; the raw output is kept as the first commit so the fix is visible.
- **macOS ships bash 3; the generator needs 4+.** `brew install bash`, then run it as `/opt/homebrew/bin/bash customizer.sh …`.
- **Material 3 Expressive is not usable from the pinned BOM.** `MaterialExpressiveTheme`, `MotionScheme` and `expressiveLightColorScheme` are `internal` in material3 1.4.0 (Compose BOM 2026.09.00). Use `MaterialTheme` + dynamic color; add expressive shapes/typography yourself.
- **API 33+ `writeCharacteristic`/`writeDescriptor` return `BluetoothStatusCodes`, not GATT status.** Comparing against `BluetoothGatt.GATT_SUCCESS` compiled and worked only because both constants are 0; lint's `WrongConstant` caught it. Compare with `BluetoothStatusCodes.SUCCESS`.
- **AGP 9.4 + Kotlin 2.4.20 + KSP 2.3.12 + compileSdk 37 build cleanly today.** The explicit Compose strong-skipping flag is deprecated (default on); `nonTransitiveRClass` stays on from the template.
- **Lint `NonObservableLocale`:** `Locale.getDefault()` inside a composable is flagged; `String.uppercase()` (root locale) is fine for eyebrow labels.
- **Trust the contract's absolute offsets.** Every `CHUNK` carries its offset, so the client verifies continuity itself rather than counting notifications — the same property that let the firmware-side host client detect a deliberately dropped frame in tests.
- **The protocol was proven before the app was.** Verifying the contract with a laptop client against the real board first (see the firmware repo's bench record) meant the app could be built and unit-tested against captured payloads with no hardware in the loop; the remaining risk is Android's BLE stack behaviour, not the protocol.
- **`String.uppercase()` is not unit-safe.** The particles card said "(MM)" because `Eyebrow` uppercased its label and `"µ".uppercase()` is `"Μ"` (Greek capital mu). `Eyebrow` now has an `uppercase` flag; labels that carry units pass `false`.
- **Keep conditional items out of a `LazyColumn` and give the rest keys.** The connection banner used to be the list's first item; anything that flipped the link state inserted or removed index 0 above the user's scroll position. It now sits above the list, every item has a stable key and the `LazyListState` is hoisted to the screen. `BleTransport` also left the state at `EnablingNotifications` after a runtime auth retry (never back to `Ready`), which would have kept the banner visible and disabled every button after a re-authenticated write. Neither could be reproduced here (no emulator was running during this pass); both are fixed structurally.
- **A runtime permission can be missing while its group is granted.** `ACCESS_LOCAL_NETWORK` shares the NEARBY_DEVICES group with the Bluetooth permissions, so on Android 16 it is usually silently granted — but "usually" is not "always", and on API < 37 `Manifest.permission.ACCESS_LOCAL_NETWORK` does not exist at all. `LanPermission` returns "granted" below 37 and asks above it.
- **`NsdManager` needs two code paths.** `registerServiceInfoCallback` (continuous updates, `getHostAddresses`) exists from API 34; API 31–33 have only the one-shot `resolveService`, which rejects concurrent calls (`FAILURE_ALREADY_ACTIVE`), so resolves are queued one at a time there.
- **Push frames make "await the next frame" wrong on LAN.** `STATUS`/`LIVE` may arrive between two `CHUNK`s. Demultiplexing by type byte in the transport keeps `DeviceSession` byte-for-byte identical for both pipes; the loopback test asserts a `LIVE` in the middle of a `READ` is delivered to the live flow and the `CHUNK`s stay contiguous.
- **`android.util.Log` in a class under JVM test** needs `testOptions.unitTests.isReturnDefaultValues = true`; otherwise the first `Log.i` throws "not mocked".
- **"gap in stream: expected offset 32768, got CHUNK at 41894" turned out to be 514-byte frames > the 512-byte GATT limit, dropped by Android; the client now resumes lost windows anyway.** Measured 2026-09-17 on a OnePlus 7 Pro (Android 16): runs of 16–18 consecutive `CHUNK`s vanished at the start of every 16 KiB window, a retry reproduced it, and the whole sync aborted. First reading: "Android BT stacks drop notification bursts, use 4 KiB windows over BLE". Root cause: firmware v5 sent full chunks of `MTU − 3 = 514` bytes, but a GATT attribute value is at most 512 bytes; Android silently discards oversize notifications (macOS accepts them), so every *full* chunk was lost and only the short last one arrived — 41894 = 32768 + 18 × 507. Fixed in the firmware (BLE frames capped at 512). Kept on the phone, because the contract prescribes it and a lossy link is still possible: a window is re-read from the phone's own offset instead of aborting the sync, and a failed request's stragglers are drained before the next request. The window stays at `max_read` on BLE — halving it would halve throughput for a problem that was not the radio's. `FakeTransport.dropLeadingChunks` / `closedDelayMs` reproduce both symptoms in `WindowRecoveryTest`.
- **WorkManager's foreground worker needs the manifest's help twice.** Its `SystemForegroundService` ships without a `foregroundServiceType`; merge it with `dataSync` (`tools:node="merge"`) or Android 14+ throws on `setForeground`. And with a Hilt worker factory the default `WorkManagerInitializer` must be removed from `androidx.startup.InitializationProvider`, otherwise WorkManager initializes before the `Configuration.Provider` and the worker cannot be constructed.
- **Expedited work is picky.** `setExpedited` rejects any constraint but network/storage (so no "battery not low" on the manual run) and, on API 31+, runs as an expedited job whose `getForegroundInfo()` is not consulted; `setForeground` is still called explicitly so both request kinds show the same notification.
- **A worker cannot answer a passkey dialog.** `BleTransport` got `allowBonding = false` and a configurable connect timeout: the unattended path connects straight to the bonded address (`connectGatt`, no scan) and treats "not bonded" as a failure to log, never as a reason to call `createBond()` from the background.
- **`openSession` rewrote every `KnownDevice` column.** Adding `autoSync` to the entity was not enough; the upsert after `info` had to carry `previous?.autoSync` or every connect would have switched auto-sync off again. Any future column needs the same line.
- **`ByteBuffer.position(int)` returns `Buffer`, not `ByteBuffer`.** `buf.duplicate().position(n).get(array)` resolved to an unrelated `get` extension and failed to compile; use absolute `get(index)` or slice first. And `LazyListScope.items(list, key)` needs the explicit `androidx.compose.foundation.lazy.items` import or it silently resolves to `items(count)`.
- **PMS concentrations are INT32 in the files** (`pm25_atmospheric_ug_m3` is an integer µg/m³ from the sensor); only the IMU/temperature/humidity columns are FLOAT. The `live` JSON shows them as numbers, which hid this until the reader test asked for a `Float32`.
- **Room's KSP processor cannot see a supertype from another Gradle module.** Making the `ArchivedFile` entity implement an interface declared in `:core:history` failed with `[MissingType]: … references a type that is not present` even though the module compiled; the entity now stays plain and maps to the pure record (`info()`). Cross-module smart casts also fail (`status.open` from `:core:protocol` needs `?.let`).
- **`/build` in the template's `.gitignore` matches the root only.** The `:core` modules' `build/` and `bin/` trees (1,100 files, 13 MB) were committed for a while without anyone noticing; the ignore file now uses `build/`, `bin/`, `.kotlin/` and `*.jks`.
- **ktlint's default style is not Android Studio's.** `ktlint_official` produced 6,700 findings here, almost all line-wrapping driven by its 140-column limit; `intellij_idea` with `max_line_length = off` and a handful of house-style rules disabled left 176 mechanical fixes, applied once. Its `function-signature` rule is the one to keep off: with no line limit it joins multi-line `= withLock { … }` bodies onto the signature, and its `multiline` mode breaks `fun f() = when (x) {`.
- **Auto Backup has a 25 MB quota and fails whole.** Including `archive/` in `<cloud-backup>` meant no cloud backup at all once the archive passed 25 MB; only the database goes to the cloud now (see the backup decision row).
- **Derive passes race.** Files opening, the post-connect refresh and the History screen each start `deriveStale()`; on the phone two passes overlapped and both rebuilt every day. A `Mutex` in `HistoryDeriver` and `HistoryIndexer` serialises them.
- **One flow for the transport chip.** On the phone the chip kept saying "Wi-Fi LAN" after the repository had reconnected over Bluetooth, until the app was killed. Each screen derived the kind from the `DeviceSession` object inside its own eight-way `combine`, so the chip depended on a session-identity change propagating through three ViewModels (with `WhileSubscribed` restarts while the screen was off), and the Files screen had no chip at all. The repository's `activeTransport` — re-evaluated on every session swap *and* every connection-state change, de-duplicated — is now the single source, and Files shows the chip too. Not reproduced without hardware; fixed structurally.
- **Continuity belongs in the session, not the download loop.** When the gap was detected only in `SyncEngine`'s chunk callback, the exception left the rest of the window in the response channel, and the next request (`CLOSE`, then `LIST`) read those frames as its own reply. Checking `CHUNK.offset` against the expected offset inside `DeviceSession.read` and finishing the window there keeps the "one request, one ordered reply" invariant intact for every caller.

## Next

1. Instrumented phone runs, recorded in the firmware repo's `docs/bench-verified.md` (date, firmware
   SHA-256, app commit, phone): a full 776-file sync over BLE and over LAN with the foreground-service
   build (the first phone runs, commit `a993268`, were partial and died of Android's background
   socket kill), BLE/Wi-Fi coexistence over a long soak, energy, `lan.rotate_token` and
   `ble.clear_bonds` end to end from the phone, `fixed`/`none` pairing (needs a display-less board).
2. Auto-sync on the OnePlus: switch it on, close the app, leave the phone overnight next to the
   logger on the home Wi-Fi, then count how many 15-min periods produced a run (`adb logcat -s
   AutoSync SyncWorker`), whether `setForeground` was refused in the background, and whether the
   BLE fallback connects by address with the screen off. Record the OEM battery settings that were
   needed.
3. Verify the API 37 local-network permission flow on a real Android 17 device (grant, deny,
   revoke while connected; the OnePlus is API 36, where it is not enforced) and `NsdManager`
   behaviour across API 31–37.
4. Further transports behind the same `AqTransport` interface as the firmware grows them (LoRa
   relays between devices). The sync engine, archive and verification do not change; only the
   byte pipe does.
5. Opt-in cloud step **after** local sync: encrypted backup and/or community contribution, off by
   default, with the privacy boundaries in `../m5stack-aq-parquet/product/mobile-architecture.md`.
   It needs its own setting and review; `INTERNET` being declared for LAN does not pre-approve it.
   A device with its own Wi-Fi/SIM uploading directly is a parallel path, not a replacement for the
   phone archive.
6. On-phone history, friendly UX, many loggers, location/map, iOS parity — evaluated in
   [`docs/history-ux-and-data-layer.md`](docs/history-ux-and-data-layer.md) (2026-09-17, for
   decision, nothing implemented). Key findings: Kotlin DataFrame cannot read Parquet on Android
   (`dataframe-arrow` needs Arrow's native Dataset library), so the proposal is an own reader/writer
   for the firmware's Parquet subset, a *derived* one-file-per-UTC-day layer beside the untouched raw
   archive (measured 5–9× smaller than the device files), aggregate tiers in Room, an opaque
   `deployment_id` to the board instead of coordinates, and an amendment to the "no location
   permission" rule that has to be agreed before any code.
