# Background sync — WorkManager, the foreground notification, WifiLock

**Scope:** everything that must outlive the screen: Auto-sync periodic work, "Sync now in background", "Collect from all loggers", the `dataSync` foreground service, the Wi-Fi lock — and the **event-driven triggers** that start a run before the period is up: companion-device presence, the device's advertising flags, Wi-Fi arrival.
**Code:** `data/background/AutoSyncScheduler.kt`, `SyncWorker.kt`, `AutoSyncRunner.kt` (pure decision logic + `AutoSyncPolicy`), `AppAutoSyncGateway.kt` (Android plumbing), `SyncForegroundService.kt`, `SyncNotifications.kt`; triggers: `CompanionPresence.kt` + `CompanionPresenceService.kt`, `AdvertFlagScan.kt` (+ `AdvertScanReceiver`), `SyncTriggers.kt` (+ `WifiArrivalReceiver`, `BootReceiver`, `SyncTrigger`); `core/protocol/.../Advertisement.kt`; `data/lan/LanWifiLock.kt`; `ui/permissions/NotificationPermission.kt`; `AndroidManifest.xml` (services, receivers, `REQUEST_COMPANION_*`, `SystemForegroundService` merge, `WorkManagerInitializer` removal).
**Tests:** `AutoSyncRunnerTest` (links, guards, "nothing new", `lanOnly`) over the fake; `AdvertisementTest` (protocol 2.1 bytes). The worker, service, scheduler, CDM and receiver plumbing are phone-only.
**Design and evidence:** `../m5stack-aq-parquet/docs/background-sync-triggers.md` (why these triggers, what Android does, what to measure) — **none of the triggers is bench-verified yet.**
**Related:** [transports.md](transports.md), [sync-engine.md](sync-engine.md).

## Rule

**WorkManager + the `dataSync` foreground notification, never a bare thread.** No `Thread`, no
`GlobalScope`, no alarm, no sticky service, no CPU wake lock. The one sanctioned `WAKE_LOCK` use is
the `WifiManager.WifiLock` below.

## Who starts what

```mermaid
flowchart TD
  SW["known_device.autoSync on (any row)"] --> P["PeriodicWorkRequest 15 min · KEEP<br/>battery not low · backoff exp 5 min"]
  M1["Files › Sync now in background"] --> O1["OneTimeWorkRequest expedited · KEEP<br/>KEY_ADDRESS"]
  M2["Home › Collect from all N loggers"] --> O2["OneTimeWorkRequest expedited · KEEP<br/>KEY_ALL_KNOWN"]
  P --> W[SyncWorker]
  O1 --> W
  O2 --> W
  W --> F["setForeground(WORKER_ID, dataSync) — may be refused in background → plain job"]
  W --> R[AutoSyncRunner.run]
  R --> L["browse mDNS ≤ 10 s once for all stations"]
  L --> D{per device}
  D --> LAN["LAN if station found AND token"] --> BLE["BLE by address if bonded<br/>15 s, allowBonding=false"]
  BLE --> S[repository session → SyncEngine → deriveStale]
  S --> N["summary notification only if new files or failures"]
```

## Triggers — starting a run before the period is up

The 15-minute periodic request is the **floor**. Three sources enqueue the same expedited one-time
`SyncWorker` request (unique name `auto-sync-trigger`, `KEEP`, input `KEY_TRIGGER`) so a trigger is
never swallowed by a manual run and at most one triggered run is pending; all existing guards apply.

| Trigger | Mechanism | Fires when | Needs from the device |
| --- | --- | --- | --- |
| **Wake when nearby** (`SyncTrigger.PRESENCE`) | Companion Device Manager. Device screen › Auto-sync › *Set up wake when nearby* → `CompanionDeviceManager.associate` with a `BluetoothLeDeviceFilter` on the logger's **address**, single-device, one system confirm dialog (the logger must be advertising, so a BLE session is dropped for it and restored; LAN stays). Then `startObservingDevicePresence` (`ObservingDevicePresenceRequest` on API 36, address string on 31–35). The system binds `CompanionPresenceService`; `EVENT_BLE_APPEARED` / `onDeviceAppeared` → `syncNow(address, PRESENCE)` if the device's Auto-sync switch is on. Disappeared → nothing. Association id in `known_device.companionAssociationId` (schema v8); *Turn off* or *Forget* disassociates. | The logger comes into BLE range (once per arrival; AOSP scans `SCAN_MODE_LOW_POWER`, `FIRST_MATCH \| MATCH_LOST`, MAC filter; disappeared ≈ 10 s after the last report). | A stable public address — the CoreS3 default. |
| **Advertising flags** (`SyncTrigger.ADVERTISEMENT`) | `AdvertFlagScan`: `BluetoothLeScanner.startScan(filters, LOW_POWER + FIRST_MATCH \| MATCH_LOST, PendingIntent)` with two offloaded filters per **bonded** Auto-sync device — `deviceAddress` AND `serviceData(SERVICE, [0, flag], mask [0, flag])` for `new_files` and for `no_utc`. `AdvertScanReceiver` decodes `Advertisement`, skips when `changeKey` (`boot16:fin:flags`) equals the last run's or within 5 min, else `syncNow(address, ADVERTISEMENT)`. Registered at process start, re-armed by every periodic run (stop + start; a Bluetooth toggle drops it), unregistered when no bonded Auto-sync device exists. Needs `isOffloadedFilteringSupported()`; otherwise absent, with a log line. | The **device** decides: it sets `new_files` when a file finalizes and clears it after a `LIST`; `no_utc` while it has no UTC anchor (reboot). | Firmware ≥ `-v6.3` (protocol 2.1 advertising payload). |
| **Wi-Fi arrival** (`SyncTrigger.WIFI`) | `SyncTriggers`: `ConnectivityManager.registerNetworkCallback(NetworkRequest{TRANSPORT_WIFI}, PendingIntent)` → `WifiArrivalReceiver` → `syncOnTrigger(WIFI, lanOnly = true)` when ≥ 1 Auto-sync device has a LAN token; throttled to one start per 10 min (the callback also fires at registration when already on Wi-Fi). `lanOnly` keeps Bluetooth out of a run that says nothing about range. Re-registered at every process start (survives process death, not reboot → `BootReceiver`). | The phone joins any Wi-Fi network (SSID is location-gated, so "home" is unknown; the 10 s mDNS browse is the cost of a miss). | Nothing. |

**Exemptions that come with CDM** (AOSP `CompanionExemptionProcessor`, *while an associated device is present*): `REQUEST_COMPANION_RUN_IN_BACKGROUND` → permanent power-save allowlist; `REQUEST_COMPANION_USE_DATA_IN_BACKGROUND` → background data allowed — the exemption the measured screen-off socket kill ("Destroyed live tcp sockets") called for; `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` → the worker's `setForeground` should stop being refused. All normal permissions, no location. Whether they hold on the OnePlus's ROM is exactly what the next phone run must show.

## Scheduling (`AutoSyncScheduler`)

| Request | Details |
| --- | --- |
| Periodic | Unique name, `KEEP`, **15 min** (`PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS`; Android picks the moment, later under Doze). Exists exactly while ≥ 1 `known_device.autoSync` row is on (the scheduler observes the count; the switch only flips the row) and is cancelled when the last goes off. Constraint: **battery not low only** — `NetworkType.CONNECTED` was dropped because the BLE path needs no network and a phone with Wi-Fi off never ran. |
| Sync now / Collect all | One unique `OneTimeWorkRequest` (`auto-sync-now`), `setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)`, `KEEP`, input `KEY_ADDRESS` or `KEY_ALL_KNOWN`. Expedited work may carry network/storage constraints only → no battery rule. |
| Triggered | Same request under `auto-sync-trigger`, `KEEP`; input `KEY_TRIGGER` (+ `KEY_ADDRESS` for presence/advertisement, `KEY_LAN_ONLY` for Wi-Fi). Two workers may overlap (manual + triggered); the second sees the repository session and skips. |
| Retry | `Result.retry()` with exponential backoff from 5 min, at most `MAX_RETRIES` 2 per period, **only** for transient conditions (a link exception, or a transfer that broke before every planned file had an outcome — `BackgroundSyncResult.incomplete`). Not reachable is not an error: one log line, next period. Per-file failures are not retried inside the period. |

## Decision logic (`AutoSyncRunner`, pure)

- **Which devices**: `autoSync` rows; `onlyAddress` for Sync now; `allKnown` visits `known_device` ordered by last connection whether or not the switch is on.
- **Links** (`AutoSyncPolicy.linksFor`): one mDNS browse per run (`LAN_BROWSE_MS` 10 s, early exit once every wanted station resolved); LAN when the station is found **and** a token is stored; else, and after a failed LAN connect, **BLE only if bonded** — direct `connectGatt` to the stored address, no scan, `BACKGROUND_BLE_CONNECT_MS` 15 s, `allowBonding = false`. Not found → finish quietly.
- **Interactive use wins**: skip any device while the repository holds a session (the same device, or another — one link at a time), re-checked between links. "Sync now in background" releases the interactive session first, then enqueues; the worker's session feeds the same `sync` state so the Files screen shows its progress.
- **Wi-Fi gate**: `AppAutoSyncGateway.browseLan` returns empty when no `TRANSPORT_WIFI` network is up (active network first, else a ≤ 1 s `NetworkRequest` probe; an offline LAN counts), so the 10 s browse never runs for nothing.
- **Never** pairs, scans, or touches a device the repository has a session with. `deriveStale()` runs after a run that brought files.

## Foreground work

| Path | Mechanism |
| --- | --- |
| User-started transfer | `SyncForegroundService` (`foregroundServiceType="dataSync"`), started by the repository **while the app is in the foreground** (API 31+ start rule). Owns no logic: mirrors `sync`/`reconnecting` into one ongoing notification (files done/total, bytes, current file, progress bar, **Stop** → `cancelSync`), paced at `NOTIFY_INTERVAL_MS` 500 ms (≤ 2 posts/s; `NotificationManager` sheds above ~5/s), stops itself `STOP_GRACE_MS` 1.5 s after `foregroundNeeded` turns false. `onTimeout` (API 35+, the 6 h / 24 h `dataSync` budget) cancels cleanly. **Not** kept alive while the link is idle. |
| Worker | `setForeground(ForegroundInfo(WORKER_ID, …, FOREGROUND_SERVICE_TYPE_DATA_SYNC))` with the same `SyncNotifications.progress`. `ForegroundServiceStartNotAllowedException` (app in background) → log one line, continue as a plain job (a running job has network access for its own duration); only the ongoing notification is missing. |
| Manifest | WorkManager's `SystemForegroundService` merged with `foregroundServiceType="dataSync"` (`tools:node="merge"`), or Android 14+ throws; default `WorkManagerInitializer` removed from `InitializationProvider` because of the Hilt worker factory. |
| Notifications | Channels `sync_progress`, `sync_summary`; ids `FOREGROUND_ID` 1001, `WORKER_ID` 1002, `SUMMARY_ID` 1003. Summary: "Synced 23 new files from AQ-6b40 · 1.1 MiB" (several devices → "from 2 loggers"), only when something arrived or failed. `POST_NOTIFICATIONS` asked once on the first Sync tap / Auto-sync switch-on (API 33+); a denial only hides the notification. |

Why the service exists (measured 2026-09-17): ~2 min after screen-off, `ConnectivityService`
logged "Destroyed live tcp sockets for uids={ours}" and `TcpTransport` saw "Software caused
connection abort" 6 ms later — the background network restriction, not the link.

## Wi-Fi lock (`LanWifiLock`)

One `WifiManager.WifiLock` from the first `OPEN` to the sync's `finally` — interactive and worker
alike (both go through `startSync`) — **only while the active transport is LAN**; BLE syncs hold
nothing and the lock is never kept over an idle link. Mode `WIFI_MODE_FULL_HIGH_PERF` on every API
on purpose: on API 31–33 it is active with the screen off, while `FULL_LOW_LATENCY` is only active
with the screen **on** and the app in the foreground (AOSP `WifiLockManager` checks
`IMPORTANCE_FOREGROUND`; a `dataSync` service does not count). On API 34+ the framework turns
HIGH_PERF into LOW_LATENCY anyway (`high_perf_lock_deprecated`). **Honest consequence: on Android
14+ this lock does not protect a screen-off LAN sync**; the automatic resume does
([sync-engine.md](sync-engine.md#resume-after-a-lost-link)). Measured before the lock: throughput
fell ~60 → ~12 files/min with the screen locked, then TCP stalled > 5 s.

## OEM battery killers

The test phone is a **OnePlus 7 Pro**; OxygenOS/ColorOS are the worst on dontkillmyapp.com:
"Don't optimize" is often not enough; may need "Allow background activity" and exclusion from
deep-optimization / sleep-standby lists; a swiped-away app may still be killed. WorkManager survives
(re-scheduled), a bare thread would not. **Nothing measured yet** — see [status.md](status.md#next).

## Uses → affects

| Edit… | Affects… |
| --- | --- |
| `AutoSyncPolicy.linksFor` | link order for worker **and** Collect all; `AutoSyncRunnerTest` |
| `AutoSyncGateway` interface | `AppAutoSyncGateway` (Android) and the test fake |
| `SyncNotifications.progress` text | both the service and the worker (same notification) |
| `known_device.autoSync` column | scheduler's observed count; every `KnownDevice` upsert must carry `previous?.autoSync` ([lesson](lessons-learned.md#room--data)) |
| `SyncForegroundService` start point | must remain a foreground-app moment (repository `startSync`), else API 31+ refuses |
| `LanWifiLock` acquire/release | `startSync` `finally`; never over BLE, never idle |
| `Advertisement` layout | mirror of the firmware contract (`ble-sync-protocol.md`, "Advertising payload"); change there first; `AdvertFlagScan` filter bytes and `AdvertisementTest` |
| `SyncTrigger` enum | `AutoSyncScheduler.syncNow/syncOnTrigger`, `SyncWorker` log line, the periodic-run detection that re-arms the flag scan |
| `companionAssociationId` column | `CompanionPresence.observe` at start, `forget()` disassociation, Device screen "wake when nearby" line |
