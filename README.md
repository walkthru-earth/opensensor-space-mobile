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
| **Devices** | Scans for the AQ Sync service UUID (`c0a5e9f0-0001-4b1a-9c3e-2d7f8a6b4e01`), lists `AQ-xxxx` devices with RSSI and bond state, remembers known devices. |
| **Today** | Live PM1 / PM2.5 / PM10, particle counts, board temperature (explicitly *not* ambient), battery/USB, freshness, storage counters (firmware v6 adds the rows/row groups already on the card in the open file, `status.open`/`open_rg`, shown only when the key is present), UTC state. **Set time from phone** and **Finalize buffered rows** actions, each explained before it runs. |
| **Files** | Device manifest grouped by Hive partition (dated / unsynced-boot / benchmarks / legacy). **Sync all** downloads every file the archive lacks, with per-file and overall progress. Per-file state: not synced · syncing · synced (verified) · mismatch. Menu: **Sync now in background** (one expedited WorkManager job — leave the app, watch the notification) and a quick **Auto-sync** toggle for the connected device. |
| **Export** | Share one file or a zip of the archive via the share sheet, or save a zip with *Save to…* (Storage Access Framework). |
| **Device** | **Auto-sync** card (any protocol version): a per-device switch; while any known device has it on, a WorkManager job runs about every 15 min — app closed, screen off — finds the logger on the LAN (mDNS) or over its Bluetooth bond, pulls only the files the archive lacks under a `dataSync` progress notification, and posts one summary ("Synced 23 new files from AQ-6b40 · 1.1 MiB") only when something arrived. |
| **Device** (v2) | Bluetooth pairing mode (random passkey / fixed PIN / none) with the "Default PIN 123456 — change it" nag, pending-reboot notice and **Reboot device** (finalizes the RAM batch, restarts, app reconnects). **Wi-Fi**: state / IP / RSSI, *Scan networks* → pick → hidden passphrase → *Save and connect*, state polled while `connecting`, radio on/off. **LAN**: server on/off, whether this phone holds a token, *Fetch token* (Bluetooth only, by contract), *Rotate token*, local-network permission explanation on Android 16+. **Forget all bonds**. Every write is one validated `SET_CONFIG`. |
| **Settings** | A sheet with one switch: **Advanced mode** (DataStore). Off by default. |
| **Debug** (Advanced only) | Active transport + endpoint + `payload_max`, live `status` JSON pretty-printed, last 200 raw frames (direction, type, length, first bytes; `TOKEN` and `SET_CONFIG` redacted), **Device log** (`LOG_TAIL 8192`) in a monospace box with refresh, **Raw command** (hex in → decoded frames out; `REBOOT` and `SET_CONFIG` are refused — they need the Device screen's confirmation). |

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
./gradlew assembleDebug testDebugUnitTest
adb install app/build/outputs/apk/debug/app-debug.apk
```

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

```
app/src/main/java/earth/walkthru/aqsync/
  data/protocol/   UUIDs, binary frame codec (v1+v2), CONFIG model + SET_CONFIG builder, LAN framing, JSON models, Parquet verification
  data/ble/        scanner, GATT transport (serialized operation queue), session (all ops), frame recorder
  data/lan/        TCP transport (AQS1 handshake, reader loop, keep-alive), mDNS discovery, WifiLock, token (re)fetch policy
  data/sync/       sync planner, engine (lost-window re-read), file archive, READ window size, resume-after-lost-link policy
  data/background/ dataSync foreground service + notifications; auto-sync WorkManager worker, scheduler, runner (decision logic)
  data/local/      Room database (known devices incl. LAN token + auto-sync switch, archived files)
  data/settings/   DataStore preferences (Advanced mode)
  data/export/     share / zip / SAF export
  data/fake/       in-app fake device for debug builds
  ui/scan · ui/device (Today, Device) · ui/files · ui/debug · ui/settings · ui/permissions · ui/theme
app/src/test/      frame codec v1/v2, config, LAN framing, TcpTransport loopback, verification, JSON, planner, resume-after-lost-link policy, lost-window recovery + stale-frame drain against the fake device, auto-sync runner (links, guards, "nothing new") over the fake device
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
| Where the LAN token lives | `known_device.lanToken` in Room, hex, nullable, keyed by the device's Bluetooth address (schema v2, explicit `ALTER TABLE` migration, no destructive fallback). Fetched once automatically after a BLE session reaches Ready if absent (or if the last LAN handshake rejected it, see "After the first phone run"); never logged, never displayed (`KnownDevice.toString`, `Frame.Token.toString`, the frame recorder and the raw-command view all redact it). The database is part of Android's end-to-end-encrypted auto backup / device transfer like the archive is; *Rotate token* on the device invalidates every copy. |
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
