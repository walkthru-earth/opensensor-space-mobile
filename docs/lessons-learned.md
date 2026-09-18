# Lessons learned — grouped by concern

**Scope:** mistakes already made and what fixed them. Read the group for the area you are touching; each line names the file that now encodes the fix.
**Related:** the concern docs linked in each heading.

## Protocol / sync

See [sync-engine.md](sync-engine.md), [protocol-quickref.md](protocol-quickref.md).

- **Trust the contract's absolute offsets.** Every `CHUNK` carries its offset, so the client verifies continuity itself rather than counting notifications — the same property that let the firmware-side host client detect a deliberately dropped frame.
- **The protocol was proven before the app was.** Verifying with a laptop client against the real board first meant the app could be unit-tested against captured payloads with no hardware; the remaining risk is Android's BLE stack, not the protocol.
- **"gap in stream: expected offset 32768, got CHUNK at 41894" was 514-byte frames > the 512-byte GATT limit** (2026-09-17, OnePlus 7 Pro): runs of 16–18 `CHUNK`s vanished at the start of every 16 KiB window. First reading "Android drops notification bursts, use 4 KiB windows" was wrong; firmware v5 sent `MTU − 3 = 514` bytes and Android silently discards oversize notifications (macOS accepts them) — 41894 = 32768 + 18 × 507. Fixed in firmware (cap 512); the phone keeps the window at `max_read` and re-reads lost windows anyway (`ReadWindow.kt`, `WindowRecoveryTest`).
- **Continuity belongs in the session, not the download loop.** Detecting the gap only in `SyncEngine`'s chunk callback left the rest of the window in the response channel, and the next request (`CLOSE`, then `LIST`) read those frames as its own reply. `DeviceSession.read` checks `CHUNK.offset` and finishes the window itself.
- **Push frames make "await the next frame" wrong on LAN.** `STATUS`/`LIVE` may arrive between two `CHUNK`s; demultiplex by type byte in `TcpTransport` so `DeviceSession` is identical for both pipes (`TcpTransportTest` asserts it).

## Transports

See [transports.md](transports.md).

- **API 33+ `writeCharacteristic`/`writeDescriptor` return `BluetoothStatusCodes`, not GATT status.** Comparing with `BluetoothGatt.GATT_SUCCESS` worked only because both are 0; lint `WrongConstant` caught it. Compare with `BluetoothStatusCodes.SUCCESS`.
- **A runtime permission can be missing while its group is granted.** `ACCESS_LOCAL_NETWORK` shares NEARBY_DEVICES with the Bluetooth permissions and is usually silently granted on Android 16 — "usually" is not "always", and below API 37 the constant does not exist (`LanPermission`).
- **`NsdManager` needs two code paths.** `registerServiceInfoCallback` from API 34; API 31–33 only `resolveService`, which rejects concurrent calls (`FAILURE_ALREADY_ACTIVE`) → queue resolves.
- **`BleTransport` left the state at `EnablingNotifications` after a runtime auth retry** (never back to `Ready`), which would keep the banner visible and every button disabled. Fixed structurally (not reproduced).
- **A worker cannot answer a passkey dialog.** `allowBonding = false` + configurable connect timeout: the unattended path connects straight to the bonded address and treats "not bonded" as a failure to log, never a reason to `createBond()`.
- **One flow for the transport chip.** The chip kept saying "Wi-Fi LAN" after a BLE reconnect because each screen derived the kind from the session object in its own eight-way `combine`. `DeviceConnectionRepository.activeTransport` is the single source.

## Background

See [background-sync.md](background-sync.md).

- **WorkManager's foreground worker needs the manifest's help twice.** Merge its `SystemForegroundService` with `foregroundServiceType="dataSync"` (`tools:node="merge"`) or Android 14+ throws on `setForeground`; with a Hilt worker factory remove the default `WorkManagerInitializer` from `InitializationProvider` or the worker cannot be constructed.
- **Expedited work is picky.** `setExpedited` rejects any constraint but network/storage (no battery rule on the manual run) and on API 31+ its `getForegroundInfo()` is not consulted — `setForeground` is called explicitly so both request kinds show the same notification.
- **Android kills background sockets ~2 min after screen-off** ("Destroyed live tcp sockets for uids=…"). A user-started transfer needs the `dataSync` foreground service; a lock does not help.
- **Wi-Fi power save halves then stalls a screen-off LAN sync**; `WIFI_MODE_FULL_HIGH_PERF` helps on API 31–33 only — on 14+ the honest protection is the automatic resume.

## Room / data

See [data-model.md](data-model.md), [history-pipeline.md](history-pipeline.md).

- **Room `@Upsert` silently does nothing on a unique-index conflict when the entity has an auto-generated primary key** (it updates by id, 0 for a new object). Symptom: the derive pass rebuilt every day on every run (≈ 50 MB GC churn per pass). Use `@Insert(onConflict = REPLACE)` for cache-like tables. Healthy log after a sync: `HistoryDeriver: derived 0 of N station-days (nothing changed)`; observed fixed on the phone 2026-09-18 02:35 ("derived 1 of 3"). The in-memory DAO fakes in tests key on the business key and **cannot reproduce this** — it is a Room semantic.
- **`openSession` rewrote every `KnownDevice` column.** Adding `autoSync` was not enough; the upsert after `info` must carry `previous?.autoSync` or every connect switches it off. Any future column needs the same line.
- **Derive passes race.** Files opening, the post-connect refresh and the History screen each start `deriveStale()`; two overlapped on the phone and both rebuilt every day. A `Mutex` in `HistoryDeriver` and `HistoryIndexer` serialises them.
- **Room's KSP processor cannot see a supertype from another Gradle module** (`[MissingType]`); the entity stays plain and maps to the pure record (`ArchivedFile.info()`). Cross-module smart casts also fail (`status.open` needs `?.let`).
- **Auto Backup has a 25 MB quota and fails whole.** Including `archive/` meant no cloud backup at all once the archive passed 25 MB; only the database goes to the cloud now.
- **"No data today" at 02:00 local is usually the calendar, not the app.** UTC+3 → the local day starts at 21:00Z; check what the board logged since then (`parquet list` over serial is read-only) before suspecting the pipeline (2026-09-18: board off 19:45–02:09 local).
- **PMS concentrations are INT32 in the files**; only IMU/temperature/humidity are FLOAT. The `live` JSON shows numbers, which hid this until the reader test asked for `Float32`.

## UI / Compose

See [screens.md](screens.md).

- **The board speaks UTC everywhere; every date a person reads must be converted, and each screen is a separate place to forget.** The Hive path (`day=`), the file name (`data_HHMM_`), `event_time_utc_ns` and the daily derived files are all UTC by contract. The Today card (fixed 2026-09-18 02:00: showed yesterday), then the Files list (fixed later the same day: headers were the SD `day=` folder, so after 21:00 local everything sat under tomorrow) each needed their own fix. Rule: a `LocalDate` shown to the user comes from an `Instant` + `ZoneId.systemDefault()`, never from a path or name; keep the conversion in a pure object with `zone` as a parameter (`FileGrouping`, `Columns`) and pin `Asia/Riyadh` / `America/Los_Angeles` / `UTC` in its test. Keep the UTC folder visible as a subtitle so the archive on disk stays traceable.
- **`String.uppercase()` is not unit-safe.** `"µ".uppercase()` is `"Μ"` (Greek capital mu) → "(MM)". `Eyebrow` has an `uppercase` flag; labels with units pass `false`.
- **The first line chart failed on real data**: one 379 µg/m³ spike set the axis to 600 and flattened a normal day. Replaced by `Canvas` columns with `Columns.axisMax` (95th percentile + floor, capped spikes labelled).
- **Keep conditional items out of a `LazyColumn` and give the rest keys.** The connection banner as item 0 shifted the user's scroll position on every link change; it now sits above the list, items have stable keys, `LazyListState` is hoisted.
- **~3 dp bars are not tappable** — Day went from 96 quarter-hours to 24 hourly columns (2026-09-18); the peak whisker keeps short spikes visible.
- **Material 3 Expressive is not usable from the pinned BOM** (`MaterialExpressiveTheme`, `MotionScheme`, `expressiveLightColorScheme` are `internal` in material3 1.4.0). Use `MaterialTheme` + dynamic color.
- **Lint `NonObservableLocale`:** `Locale.getDefault()` inside a composable is flagged; `String.uppercase()` (root locale) is fine for eyebrow labels.
- **`LazyListScope.items(list, key)` needs the explicit `androidx.compose.foundation.lazy.items` import** or it silently resolves to `items(count)`.

## Build / tooling

See [build-and-release.md](build-and-release.md).

- **`customizer.sh` cannot take a spaced application name**; **macOS ships bash 3, the generator needs 4+** (`/opt/homebrew/bin/bash`).
- **AGP 9.4 + Kotlin 2.4.20 + KSP 2.3.12 + compileSdk 37 build cleanly** (2026-09-17). The explicit Compose strong-skipping flag is deprecated (default on); `nonTransitiveRClass` stays on.
- **`android.util.Log` in a class under JVM test** needs `testOptions.unitTests.isReturnDefaultValues = true`.
- **`ByteBuffer.position(int)` returns `Buffer`, not `ByteBuffer`** — `buf.duplicate().position(n).get(array)` resolved to an unrelated extension; use absolute `get(index)` or slice first.
- **`/build` in the template's `.gitignore` matches the root only.** The `:core` modules' `build/` and `bin/` trees (1 100 files, 13 MB) were committed for a while; the ignore file now uses `build/`, `bin/`, `.kotlin/`, `*.jks`.
- **ktlint's default style is not Android Studio's.** `ktlint_official` produced 6 700 findings (140-column wrapping); `intellij_idea` with `max_line_length = off` and a handful of house rules disabled left 176 mechanical fixes. Keep `function-signature` off: it joins multi-line `= withLock { … }` bodies onto the signature and breaks `fun f() = when (x) {`.
