# Lessons learned — grouped by concern

**Scope:** mistakes already made and what fixed them. Read the group for the area you are touching; each line names the file that now encodes the fix.
**Related:** the concern docs linked in each heading.

## Protocol / sync

See [sync-engine.md](sync-engine.md), [protocol-quickref.md](protocol-quickref.md).

- **Trust the contract's absolute offsets.** Every `CHUNK` carries its offset, so the client verifies continuity itself rather than counting notifications — the same property that let the firmware-side host client detect a deliberately dropped frame.
- **The protocol was proven before the app was.** Verifying with a laptop client against the real board first meant the app could be unit-tested against captured payloads with no hardware; the remaining risk is Android's BLE stack, not the protocol.
- **"gap in stream: expected offset 32768, got CHUNK at 41894" was 514-byte frames > the 512-byte GATT limit** (2026-09-17, OnePlus 7 Pro): runs of 16–18 `CHUNK`s vanished at the start of every 16 KiB window. First reading "Android drops notification bursts, use 4 KiB windows" was wrong; firmware v5 sent `MTU − 3 = 514` bytes and Android silently discards oversize notifications (macOS accepts them) — 41894 = 32768 + 18 × 507. Fixed in firmware (cap 512); the phone keeps the window at `max_read` and re-reads lost windows anyway (`ReadWindow.kt`, `SyncEngineTest`).
- **Continuity belongs in the session, not the download loop.** Detecting the gap only in `SyncEngine`'s chunk callback left the rest of the window in the response channel, and the next request (`CLOSE`, then `LIST`) read those frames as its own reply. `DeviceSession.read` checks `CHUNK.offset` and finishes the window itself.
- **Push frames make "await the next frame" wrong on LAN.** `STATUS`/`LIVE` may arrive between two `CHUNK`s; demultiplex by type byte in `TcpTransport` so `DeviceSession` is identical for both pipes (`TcpTransportTest` asserts it).

## Transports

See [transports.md](transports.md).

- **Slow LAN on both phones needs a shared-path investigation.** On 2026-10-08 the owner reported iPhone and Android collection slower than BLE. Existing CoreS3 host collectors also measured about 1.8 KB/s each under concurrency. Both mobile engines use full negotiated READ windows; inspect firmware scheduling, shared storage, peer backpressure and radio coexistence before blaming a platform. Separate isolated and concurrent same-file timings, and preserve CRC/PAR1 verification ([investigation](transports.md#investigating-slow-wi-fi-collection-2026-10-08)).
- **Keep per-chunk work bounded.** Archive writes and offset checks run for every chunk, but Android progress/debug snapshots are throttled to 100 ms and iOS progress to 250 ms. Final bytes, recovered windows and request-ending debug history still flush immediately. Cancel completed native timeout timers rather than retaining one deadline callback for every network operation. Transfer speed counts newly written bytes rather than preexisting resume offsets (`FileProgressReporter`, `FrameRecorder`, `SensorTransfer`, `LanSession`).
- **An unread USB console can throttle Wi-Fi.** CoreS3 v6.6 took 16.459 s to fetch an 87,654-byte file without console draining versus 2.149 s with it. Two synchronous debug writes gated each READ. Firmware v6.7 keeps radio/archive diagnostics in LOG_TAIL without console IO; the same file then took 0.925–1.045 s in three short undrained host runs. Keep physical serial replies on their reliable path and record client/load differences rather than claiming an exact phone speed multiplier ([board evidence](../../aq-parquet/docs/boards/m5stack-cores3/bench-verified.md#2026-10-08--cores3-v67-lan-latency-and-usb-backpressure)).

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
- **Everything the run still needs from the link must happen before `running` turns false.** The post-sync `LIST` used to be launched from the `finally`, after `running = false` — the foreground service stopped 1.5 s later and, with the phone already long in the background, the socket died under the listing: "LIST failed: link lost while waiting for a response" right after "Synced n of n". The re-list now runs inside the job while it is still protected, and a failed refresh keeps the previous listing on screen (`ManifestState.last`) instead of replacing it with a red banner and a null plan. Not a timeout: "link lost" is the transport's state change, so a longer `FRAME_TIMEOUT_MS` would only have delayed the same message.
- **Wi-Fi power save halves then stalls a screen-off LAN sync**; `WIFI_MODE_FULL_HIGH_PERF` helps on API 31–33 only — on 14+ the honest protection is the automatic resume.

## Room / data

See [data-model.md](data-model.md), [history-pipeline.md](history-pipeline.md).

- **Room `@Upsert` silently does nothing on a unique-index conflict when the entity has an auto-generated primary key** (it updates by id, 0 for a new object). Symptom: the derive pass rebuilt every day on every run (≈ 50 MB GC churn per pass). Use `@Insert(onConflict = REPLACE)` for cache-like tables. Healthy log after a sync: `HistoryDeriver: derived 0 of N station-days (nothing changed)`; observed fixed on the phone 2026-09-18 02:35 ("derived 1 of 3"). The in-memory DAO fakes in tests key on the business key and **cannot reproduce this** — it is a Room semantic.
- **`openSession` rewrote every `KnownDevice` column.** Adding `autoSync` was not enough; the upsert after `info` must carry `previous?.autoSync` or every connect switches it off. Any future column needs the same line.
- **Derive passes race.** Files opening, the post-connect refresh and the History screen each start `deriveStale()`; two overlapped on the phone and both rebuilt every day. A `Mutex` in `HistoryDeriver` and `HistoryIndexer` serialises them.
- **Room's KSP processor cannot see a supertype from another Gradle module** (`[MissingType]`); the entity stays plain and maps to the pure record (`ArchivedFile.info()`). Cross-module smart casts also fail (`status.open` needs `?.let`).
- **Auto Backup has a 25 MB quota and fails whole.** Including `archive/` meant no cloud backup at all once the archive passed 25 MB. Cloud backup now carries shared preferences only. The database stays out because `known_device` holds the LAN token and extraction rules cannot redact one column (`res/xml/data_extraction_rules.xml`). Device-to-device transfer carries the archive and the database.
- **`runCatching` around a suspend call swallows cancellation.** A cancelled coroutine then carries on with a failed `Result`. Use `suspendRunCatching` (`data/coroutines/Coroutines.kt`), which rethrows `CancellationException`, and `withTimeoutOrNull` for a best-effort bounded call.
- **A check of `busy` followed by a set is two steps.** Two quick taps both passed the check and the first to finish cleared the flag for the other. The repository's operation gate (`tryBeginCommand`, `tryBeginBackground`) checks and sets under one lock.
- **Do not decrypt credentials for a list.** `observeAll` used to decrypt every LAN token on every emission and hand plaintext to ViewModels, and a transient Keystore error deleted the token for good. Rows now carry ciphertext, plaintext comes only from `LanCredentialStore` when a LAN transport is built, and only a ciphertext that can never decrypt again is cleared.
- **"No data today" at 02:00 local is usually the calendar, not the app.** UTC+3 → the local day starts at 21:00Z; check what the board logged since then (`parquet list` over serial is read-only) before suspecting the pipeline (2026-09-18: board off 19:45–02:09 local).
- **PMS concentrations are INT32 in the files**; only IMU/temperature/humidity are FLOAT. The `live` JSON shows numbers, which hid this until the reader test asked for `Float32`.

## UI / Compose

See [screens.md](screens.md).

- **An iOS Wi-Fi discovery card needs visible connection and reading feedback.** Make the card and its Connect action respond consistently; show connecting progress, authenticated connection state, waiting for fresh data, and the sensor's unavailable/warming/error hint. Do not hide every state except a valid number. Clear the sensor's readings on disconnect and ignore late callbacks (`CompanionScreen.kt`, `CompanionState.kt`). LAN LIVE is an unsolicited push under the firmware contract; do not invent a live-read opcode to hide the initial wait.
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
