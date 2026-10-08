# Audit B. Transports, pairing, LAN, connection management and the sync engine

Repository `opensensor-space-mobile`, branch `main` at `180d1ee`, read only, 2026-10-09.
Paths below are relative to the repo root. `A/` means `app/src/main/java/earth/walkthru/aqsync/data/`.
Contract source is `../aq-parquet/docs/shared/ble-sync-protocol.md` (cited as `contract:LINE`).
Everything here is from reading code. No build, simulator or phone run was done for this audit.

## 0. Summary of the shape today

- Android has one Kotlin stack, `AqTransport` (byte pipe) under `DeviceSession` (request and
  terminal-frame logic) under `SyncEngine` (per file) under `DeviceConnectionRepository` (1597
  lines, everything else). Almost all of it is pure Kotlin that only lives in `app/` by accident.
- iOS has a second, independent engine in Swift. `SensorTransfer.swift` reimplements LIST, OPEN,
  window READ, retry and CLOSE. `SensorArchive.swift` reimplements CRC-32, PAR1, name rules and its
  own partial and receipt layout. `LanSession.swift` reimplements the LAN handshake checks,
  liveness and framing. Kotlin is used only for frame encode and decode (`SyncWire.kt`).
- iOS collects files over LAN only. BLE on iOS is live readings plus GET_TOKEN. There is no iOS BLE
  file sync, no Wi-Fi provisioning, no SET_CONFIG, no SET_TIME and no auto reconnect.
- The two engines already disagree on max_read default, OPEN timeout, station id charset, failure
  isolation per file, LIST validation, CLOSE handling and partial layout (section 2).
- `docs/production-readiness-plan.md:177` (W6) already names `:core:sync` and the ports. This
  report gives the concrete inventory and steps for that wave.

## 1. Capability parity matrix

Legend yes, partial, no. Line numbers are 1-based.

| Capability | Android | iOS | Notes |
| --- | --- | --- | --- |
| BLE scan | yes `A/ble/BleScanner.kt:46` (service filter, bonded flag) | yes `ios/App/BluetoothCompanion.swift:43-64` (15 s timer, service filter) | iOS creates the central lazily on first scan, Android has a BluetoothStatus receiver `A/ble/BluetoothStatus.kt` |
| BLE connect | yes `A/ble/BleTransport.kt:200-270` (30 s, `CONNECT_TIMEOUT_MS` :509) | yes `BluetoothCompanion.swift:73-87` (30 s timer) | Same budget |
| MTU 517 | yes `BleTransport.kt:232` `requestMtu(AqUuids.MTU_REQUEST)` | n/a, iOS negotiates itself. Only checks `maximumWriteValueLength >= 1` `BluetoothCompanion.swift:212` | contract:206 asks for 517 |
| Bond and pairing | yes `BleTransport.kt:284-310` createBond, 120 s bond wait `:511`, auth retry `:362-390`, `BleBondAwaiter.kt` | partial. OS-driven pairing on first encrypted read or subscribe, error text only `BluetoothCompanion.swift:259-261, 289-291` | No bond state or repair guidance on iOS (Android has `BleConnectionFailure.kt:22`) |
| PIN entry and pairing mode config | yes, system passkey dialog plus SET_CONFIG pin/pair mode via `DeviceConnectionRepository.kt:1343` | partial. OS passkey sheet only, no PIN or pairing mode config | |
| Unattended BLE (bonded, no prompt) | yes `A/TransportFactory.kt:52-59`, `allowBonding=false` | no | |
| Live readings over BLE | yes `A/ble/DeviceSession.kt:113`, notifications | yes `BluetoothCompanion.swift:303-308`, 35 s freshness | |
| Live readings over LAN | yes `A/lan/TcpTransport.kt:235-241` push routing | yes `ios/App/LanSession.swift:229-233`, `LanDiscovery.swift:138-147` | |
| GET_TOKEN | yes, automatic on BLE Ready when none stored `A/DeviceConnectionRepository.kt:617-622`, explicit `:1472`, `A/lan/LanTokenPolicy.kt:33` | partial. Explicit user tap only `BluetoothCompanion.swift:209-223`, reply parse `:271-288` | iOS does not reject a TOKEN whose length is not 32 until Keychain save `ios/App/LanCredentials.swift:16` |
| Token rotate, renewal after ERROR 14 | yes `DeviceConnectionRepository.kt:474-478, 1492-1517` | partial. Deletes credential on auth failure `LanDiscovery.swift:132`, no rotate | |
| Token at rest | yes, encrypted store, but hex `String` copies `DeviceConnectionRepository.kt:507-520, 643` | yes Keychain, ThisDeviceOnly `LanCredentials.swift:22-23`, JSON so base64 copy | Both create String copies of the secret |
| Wi-Fi config (scan, set SSID/PSK, LAN on/off) | yes `DeviceSession.kt:299-316`, `DeviceConnectionRepository.kt:1343-1411` | no | |
| LAN mDNS discovery | yes NSD `A/lan/LanDiscovery.kt`, keyed by station `A/lan/LanDiscoveryState.kt:56` | yes NWBrowser `ios/App/LanDiscovery.swift:34-83`, keyed by instance name, 40 s browse | Different keys and TXT filters, see 2.12 |
| LAN TCP session and handshake | yes `TcpTransport.kt:134-214` | yes `LanSession.swift:77-98` | Different timeouts and checks, see 2.3 |
| LAN keepalive and dead link | yes `TcpTransport.kt:248-261` (120 s idle ping, 180 s dead) | yes `LanSession.swift:283-307`, `SensorTransfer.swift:220-226` (same numbers, hard coded) | |
| BLE to LAN transport choice and fallback | yes `DeviceConnectionRepository.kt:377-501` (prefer LAN, fall back to BLE) | no. Two separate UIs, LAN only for sync | contract:323 |
| File LIST | yes `DeviceSession.kt:131-143` | yes `SensorTransfer.swift:82-96` | iOS rejects the whole run on one bad entry |
| Plan and diff (partials, synced, mismatch) | yes `A/sync/SyncPlanner.kt:83-124` | partial. `archive.completed` receipt check only `SensorTransfer.swift:97-101` | No MISMATCH or INVALID_NAME states on iOS |
| OPEN and identity of OPENED | yes `A/sync/SyncEngine.kt:109-124` | yes `SensorTransfer.swift:107-113` | Empty echo accepted on Android only |
| READ windows | yes `SyncEngine.kt:161-223`, window = `max_read` `A/sync/ReadWindow.kt:29` | yes `SensorTransfer.swift:118-184` | |
| Window retry from own offset | yes 5 retries, 300 ms x attempt `SyncEngine.kt:193-206, 271-274` | yes same numbers `SensorTransfer.swift:53-54, 171-176` | Same policy, two copies |
| Resume from partial | yes `A/sync/FileArchive.kt:91-105` (meta "size crc") | yes `ios/App/SensorArchive.swift:156-176` (size and crc in file name) | Different layouts |
| Verify size, CRC-32, PAR1 | yes `core/protocol/.../ParquetVerify.kt:58-70` via `ParquetVerifyJvm.kt:25` | yes, Swift copy `SensorArchive.swift:16-19, 178-196` | Different order, duplicate CRC table |
| Corrupt prefix recovery | yes, one re-read from zero `SyncEngine.kt:231-243` | no. Partial deleted, run aborts `SensorArchive.swift:229-234` | |
| SHA-256 and index row | yes `SyncEngine.kt:244-257` | no. Receipt `{size, crc}` only `SensorArchive.swift:118-129` | |
| CLOSE | best effort, 3 s, NonCancellable `SyncEngine.kt:262-266` | required, CLOSED awaited, error aborts `SensorTransfer.swift:187-191` | |
| Stale frame drain after a failed request | yes `DeviceSession.kt:442-518` | no. Socket closed on any timeout `LanSession.swift:261-279` | Equivalent safety on LAN, not portable to BLE |
| Concurrent writer lease per (station, name) | yes `FileArchive.kt:39-54` | no | iOS relies on SensorClaims per hardware id only |
| Multi-device concurrency | yes, controller per address `A/DeviceConnectionManager.kt:30-34`, budget 2 that waits `A/DeviceIdentityClaims.kt:24-28` | partial. Session per Bonjour name, 2 transfers, third refused `LanDiscovery.swift:161-166` | |
| Identity and continuity claims | yes `A/DeviceContinuityPolicy.kt:31-57`, hardware claims `A/DeviceIdentityClaims.kt:9-20` | partial. HELLO station and dev must equal Keychain credential `LanSession.swift:92-94`, `SensorClaims` `SensorTransfer.swift:229-243` | No station-change flow on iOS |
| Reconnect and backoff | yes 3 attempts x 5 s, LAN then BLE `DeviceConnectionRepository.kt:770-800, 1587-1588`, resume policy `A/sync/SyncResumePolicy.kt:39-45` | partial. Only resumes collections paused by background expiry on foreground return `LanDiscovery.swift:153-156, 252-266`. No reconnect after a foreground drop | |
| Background collection | yes WorkManager path `DeviceConnectionRepository.kt:1090-1169` | partial. `beginBackgroundTask` only `LanDiscovery.swift:268-292` | Covered by the settings/background audit |
| Fake device | yes, debug `A/fake/FakeTransport.kt` (LAN and BLE flavours, `dropLeadingChunks`, `closedDelayMs` :155-158) | no in app. Scripted `ArchivePipe` fake in tests only `ios/tests/TransferChecks.swift:20-40` | |
| Cancellation | yes coroutine cancel, `ensureActive` `SyncEngine.kt:171`, Stop `DeviceConnectionRepository.kt:908-930` | yes `Task.cancel` and `checkCancellation` `SensorTransfer.swift:99,105,119,129`, `LanDiscovery.swift:211-225` | |
| Debug raw command denylist | yes `DeviceSession.kt:366-384, 555` | n/a (no raw command on iOS) | |
| SET_TIME, FLUSH, REBOOT, LOG_TAIL | yes `DeviceSession.kt:227-346` | no | |

## 2. Duplicated logic and behavioural drift

Each row is one decision implemented twice. "Drift" marks a behavioural difference.

### 2.1 READ window size and max_read default (drift, likely bug on one side)
- Android `A/sync/ReadWindow.kt:21,29` uses device `max_read`, default **4096** when absent, no upper clamp.
- iOS `ios/shared/src/commonMain/kotlin/earth/walkthru/opensensor/SyncWire.kt:34-35` defaults to
  **16384** and clamps to 1..16384. `SensorTransfer.swift:80` then refuses anything outside 1..16384.
- iOS also clips the last window to `size - start` (`SensorTransfer.swift:121`), Android always asks
  for `maxRead` and lets the device clip (contract:214 allows both).

### 2.2 Request timeouts (drift, real risk on iOS)
- Android `DeviceSession.kt:528,531` uses 15 s between frames and **60 s for OPEN** because the
  device CRCs the whole file before answering (`:146-148`).
- iOS `LanSession.swift:63` uses one 10 s `requestTimeout` for every reply, including OPENED, and a
  timeout **closes the socket** (`:261-268`). A large file on a slow card can time out every OPEN on
  iOS, which then looks like "did not respond in time". Needs a real-phone check with a large file.

### 2.3 LAN connect and HELLO (drift)
- Connect timeout Android 5 s `TcpTransport.kt:344`, handshake 8 s `:345`. iOS 10 s TCP plus 10 s
  connect deadline plus 10 s HELLO `LanPolicy.swift:9`, `LanSession.swift:63,80`.
- HELLO checks. Android requires only `payload_max >= 64` `TcpTransport.kt:175`. iOS requires
  `proto == 2` and `64...1024` and station plus dev equal to the credential `LanSession.swift:89-94`.
  Android does the identity check later through `DeviceContinuityPolicy` on the HELLO info JSON
  (`DeviceConnectionRepository.kt:563-573`) and pre-checks the TXT `dev` (`:459`).
- Android accepts a HELLO `payload_max` above 1024 and reads frames up to it (`TcpTransport.kt:222`),
  iOS caps the frame buffer at the HELLO value but refuses above 1024.

### 2.4 Keepalive and dead link (duplicate, same numbers)
- Android `TcpTransport.kt:248-261` with `LanProtocol.KEEPALIVE_AFTER_IDLE_MS` (`LanProtocol.kt:25`)
  and `DEAD_AFTER_MS = 180_000` (`TcpTransport.kt:347`).
- iOS `SensorTransfer.swift:220-226` hard codes 120 s, `LanSession.swift:64` hard codes 180 s.
  iOS also skips the ping while collecting. Same outcome, but the Swift copy does not read the
  shared constant.

### 2.5 LAN frame splitting (duplicate)
- Android `core/protocol/src/jvmMain/.../LanFraming.kt:52-77` over `InputStream`.
- iOS `SensorTransfer.swift:246-280` `LanFrameBuffer`. A common incremental splitter over `ByteArray`
  chunks would serve both.

### 2.6 Station id charset (drift)
- Android `[0-9A-Za-z._-]+` in `A/DeviceContinuityPolicy.kt:60` and `A/sync/FileArchive.kt:131`.
- iOS `[A-Za-z0-9_-]+` (no dot) in `SyncWire.kt:33`, `SensorArchive.swift:73-74`,
  `LanCredentials.swift:46`. A station id containing `.` works on Android and is refused on iOS.

### 2.7 Hardware id rule (duplicate)
- `DeviceContinuityPolicy.kt:59`, `SyncWire.kt:32`, `LanCredentials.swift:43-44`. Same rule, three copies.

### 2.8 Archive name rule (duplicate)
- `core/protocol/.../SafeName.kt:18-25` (canonical, commonMain, tested).
- iOS `SensorArchive.swift:72-98` reimplements the charset, `..`, length 399 and `.parquet`, then adds
  empty-component and symlink checks. `SensorTransfer.swift:92` checks 399 again. The Swift copy is
  stricter only through the filesystem checks, which should stay in the adapter.

### 2.9 LIST validation and failure isolation (drift, behaviour bug on iOS)
- Android marks an unsafe name `INVALID_NAME` and continues (`SyncPlanner.kt:96-102`). A size
  mismatch is `MISMATCH` and skipped (`:111-117`).
- iOS throws for the whole collection on one unsafe name (`SensorTransfer.swift:94`), a duplicate
  name, an entry below 12 bytes, or more than 65535 entries (`:91-93`). One odd file on the card
  blocks every other file on iOS.

### 2.10 Per-file outcomes (drift, behaviour bug on iOS)
- Android returns `Mismatch`, `DeviceError` or `Failed` for that file and goes on
  (`SyncEngine.kt:116-123, 198-216`, loop in `DeviceConnectionRepository.kt:990-1024`).
- iOS throws on OPENED mismatch (`SensorTransfer.swift:109-113`), READ_END status (`:168`), window
  give-up (`:173`) and any `ERROR` frame (`LanSession.swift:156-160`). The run stops at the first bad file.
- iOS also ignores `ERROR.op` (`LanSession.swift:156`), Android checks it (`DeviceSession.kt:397-400`).

### 2.11 OPENED name echo (drift)
- Android accepts an empty echoed name (`SyncEngine.kt:122`). iOS requires equality
  (`SensorTransfer.swift:109`). contract:206 says OPENED.name is the full requested name, so iOS is
  closer to the contract.

### 2.12 mDNS TXT filtering and keying (drift)
- Android keeps services with host, port 1..65535 and a `station` TXT (`A/lan/LanDiscovery.kt:34` `isConnectable`),
  keyed by station (`LanDiscoveryState.kt:56`), no `proto` check, prefers IPv4.
- iOS needs `station`, `dev` and `proto == "2"`, keyed by instance name
  (`ios/App/LanDiscovery.swift:65-72`). Two instances with one station collapse on Android and do
  not on iOS.

### 2.13 Window loss detection (duplicate, same rule)
- Android `DeviceSession.kt:169-215` (gap detection, discard to READ_END, next_offset check only when
  status ok) then retry in `SyncEngine.kt:193-206`.
- iOS `SensorTransfer.swift:127-176` (same gap rule, keeps reading to READ_END, compares next_offset
  with own count). Both follow contract:206. iOS additionally refuses a CHUNK past the requested
  window end (`:137`), Android has no such bound and relies on final verify.

### 2.14 CRC-32 and PAR1 verify (duplicate, order differs)
- Kotlin `ParquetVerify.kt:58-70` size, CRC, magic. Swift `SensorArchive.swift:178-196` size, magic,
  CRC with its own table `:16-19`. Same verdict, different failure reason and cost. Swift skips
  hashing a file with bad magic, Kotlin hashes first.

### 2.15 Corrupt partial recovery (drift)
- Android re-reads once from zero with the handle still open (`SyncEngine.kt:231-243`).
- iOS deletes the partial and throws `invalidFile` (`SensorArchive.swift:231-234`). The user must sync again.

### 2.16 CLOSE (drift)
- Android best effort, 3 s, NonCancellable, never fails the file (`SyncEngine.kt:262-266, 277`).
- iOS requires CLOSED for the same handle and aborts otherwise (`SensorTransfer.swift:187-191`), and
  sends no CLOSE on the error path (the socket is closed instead).

### 2.17 Partial and receipt layout (drift)
- Android `filesDir/archive-partial/<station>/<name>.partial` plus `.partial.meta` "size crc"
  (`FileArchive.kt:17-21, 72-73, 95-103`), Room row is the index.
- iOS `archive/<station>/<name>.<size>-<crc hex>.partial` next to the final file plus
  `<name>.verified.json` receipt (`SensorArchive.swift:104, 165`).

### 2.18 Already-archived check (drift)
- Android plans from Room by name and bytes (`SyncPlanner.kt:111-118`), and if a destination exists
  at download time verifies it against OPENED, SHA-256 and the Room row (`SyncEngine.kt:128-157`).
- iOS trusts a receipt plus a local CRC and never OPENs (`SensorArchive.swift:102-116`), and in
  `prepare` re-verifies an existing final file against OPENED (`:158-161`).

### 2.19 Claims and concurrency budget (duplicate, drift in policy)
- `DeviceIdentityClaims.kt:9-20` vs `SensorClaims` `SensorTransfer.swift:229-243`.
- `CollectionBudget` waits for a permit (`DeviceIdentityClaims.kt:24-28`). iOS refuses a third
  transfer with a message (`LanDiscovery.swift:161-166`).

### 2.20 Progress and rate (duplicate, drift)
- Android throttles to 100 ms (`A/sync/FileProgressReporter.kt:26`) and shows a 3 s sliding rate
  (`DeviceConnectionRepository.kt:1558-1577`).
- iOS throttles to 250 ms and shows the average since start of run (`SensorTransfer.swift:202-217`).

### 2.21 Resume decision (duplicate, drift)
- Android `SyncResumePolicy.shouldResume` (running, or TRANSPORT_FAILURE within 5 min) with automatic
  reconnect (`SyncResumePolicy.kt:37-45`, `DeviceConnectionRepository.kt:738-800`).
- iOS `canResume` disconnected or timeout, only while backgrounded (`LanDiscovery.swift:153, 205, 306-311`).

### 2.22 Token handling (duplicate, drift)
- Android: auto fetch on BLE Ready when none stored, invalidate on ERROR 14, announce renewal
  (`LanTokenPolicy.kt:16-46`, `DeviceConnectionRepository.kt:617-622, 641-660`).
- iOS: explicit fetch, delete on auth failure, no renewal flow (`BluetoothCompanion.swift:209-288`,
  `LanDiscovery.swift:132`). Validation of a stored record is duplicated in `LanCredentials.swift:41-47`.

### 2.23 Error classification (duplicate)
- Android `LanFailure` enum `TcpTransport.kt:52-58` and `DeviceErrorException.of` `DeviceSession.kt:31-38`.
- iOS `LanFailure` `LanSession.swift:5-20`, `SensorTransfer.Failure` `SensorTransfer.swift:38-49`.

## 3. What can move to commonMain today

Checked imports in every file in scope. "Pure" means no `android.*`, no Hilt annotation needed in
the moved code, and only trivial JVM calls to replace.

### Movable now (pure Kotlin, at most trivial edits)

| Class | File | Edit needed |
| --- | --- | --- |
| `AqTransport`, `ConnectionState`, `TransportException`, `TransportKind` | `A/ble/AqTransport.kt` | none |
| `DeviceSession` and its exceptions, `ConfigSnapshot`, `WifiScanResult`, `LogTail` | `A/ble/DeviceSession.kt` | `System.currentTimeMillis` (:292, :474, :479) to an injected clock or `TimeSource.Monotonic`, `java.io.ByteArrayOutputStream` (:333) to a `ByteArray` builder |
| `FrameRecorder` | `A/ble/FrameRecorder.kt` | `System.*` time and `synchronized` (:38, :60-86) to injected clock and a Mutex or atomicfu lock |
| `SyncEngine`, `FileProgress`, `FileOutcome` | `A/sync/SyncEngine.kt` | drop `javax.inject`, replace `FileArchive`, `ArchivedFileDao`, `HistoryIndexer`, `File` with ports (needs ports, see below) |
| `SyncPlanner`, `HivePartition`, `ManifestEntry`, `SyncPlan`, `FileSyncState`, `ArchivedIdentity` | `A/sync/SyncPlanner.kt` | `"%04d".format` (:16) to `padStart` |
| `SyncResumePolicy`, `SyncEndCause` | `A/sync/SyncResumePolicy.kt` | none |
| `SmartSyncPolicy` and enums | `A/sync/SmartSyncPolicy.kt` | none |
| `ReadWindow` | `A/sync/ReadWindow.kt` | take `TransportKind` instead of `AqTransport` |
| `FileProgressReporter` | `A/sync/FileProgressReporter.kt` | `System.nanoTime` default to `TimeSource.Monotonic` |
| `ManifestState` | `A/ManifestState.kt` | none |
| `DeviceContinuityPolicy` | `A/DeviceContinuityPolicy.kt` | none |
| `DeviceIdentityClaims`, `CollectionBudget` | `A/DeviceIdentityClaims.kt` | `@Synchronized` to a lock, drop `javax.inject` (keep `@Inject` on a thin Android binding) |
| `ConnectionIntent` | `A/ConnectionIntent.kt` | `AtomicLong` to `kotlin.concurrent.atomics.AtomicLong` (opt-in on Kotlin 2.4.20) or atomicfu |
| `LanTokenPolicy` | `A/lan/LanTokenPolicy.kt` | `synchronized` to a lock |
| `LanFailure`, `LanConnectException` | `A/lan/TcpTransport.kt:52-60` | split out of the socket class |
| `LanDevice` minus `InetAddress` | `A/lan/LanDiscovery.kt` | host becomes a `String` or an opaque endpoint, TXT parsing becomes common |
| `LanTargets` and the filter in `LanDiscoveryState` | `A/lan/LanDiscoveryState.kt:18-21, 56` | the filter and keying rule become a pure function, the NSD lifecycle stays |
| `DeviceMessage`, `DeviceEvent` | `A/DeviceMessage.kt` | depends on `LanFailure` and `ClockQuality`, move together |
| `RateMeter`, `collectionProgress`, `SyncProgress`, `ReconnectState` | `DeviceConnectionRepository.kt:98-160, 1558-1577`, `DeviceConnectionManager.kt:76-84` | none |
| `BleBondAwaiter` (pure state machine) | `A/ble/BleBondAwaiter.kt` | `@Synchronized`. Only Android uses bonding, so moving is optional |
| `FakeTransport` | `A/fake/FakeTransport.kt` | `java.nio.ByteBuffer` to `FrameCodec` encoders or a public wire writer, `String.format` (:116, :518) to manual formatting, `System.currentTimeMillis` to clock |
| `SensorLabel` | `A/SensorLabel.kt` | `java.time` to kotlinx-datetime 0.8.0 (already used by `:core:history`) |

### Needs a port first

| Logic | Why | Port |
| --- | --- | --- |
| Partial, commit, discard, existing-file checks in `SyncEngine` and `FileArchive` | `java.io.File`, `RandomAccessFile`, `Context.filesDir` | `ArchiveStore` |
| Room upsert, history indexing, SHA-256 recording | `ArchivedFileDao`, `HistoryIndexer`, `MessageDigest` | `ArchiveIndex`, plus `expect class Sha256` in `:core:protocol` |
| Token load, save, clear | Room plus Keystore (`LanCredentialStore`) on Android, Keychain on iOS | `CredentialStore` |
| LAN handshake and HELLO validation inside `TcpTransport.connect` | socket I/O mixed with the decision | `FrameLink` for LAN plus a common `LanHandshake` |
| Transport choice in `connectLocked` and `tryLan` | Room `KnownDevice`, `InetAddress` | `KnownDeviceStore` (or pass a snapshot) plus pure `TransportSelector` |
| Reconnect loop and link watch | repository state and jobs | `LinkSupervisor` in common over `DeviceLinkFactory` |
| Wi-Fi lock lease | `WifiManager` | keep `WifiLeases` interface (`A/lan/LanWifiLock.kt:12`) as a port |

### Stays platform

`BleTransport`, `BleScanner`, `BluetoothStatus`, `PendingGattOperation`, `BleConnectionFailure`
(GATT codes), `LanDiscovery` NSD callbacks, `LanWifiLock`, `TransportFactory`, foreground
service wiring. On iOS `BluetoothCompanion` Core Bluetooth callbacks, `LanSession` NWConnection,
`LanDiscovery` NWBrowser and UIKit background time, `LanPolicy`, Keychain, FileManager.

## 4. Shared engine design

### 4.1 Module

`:core:sync`, plugin `opensensor.kmp.library` (jvm and iOS targets like `:core:protocol`).
Dependencies `:core:protocol`, `kotlinx-coroutines-core` (version 1.11.0 in
`gradle/libs.versions.toml:15`), `kotlinx-datetime` only if needed. `commonTest` uses
`kotlinx-coroutines-test`. `jvmMain` holds a `java.io` `ArchiveStore` used by Android and host tests,
and the socket `TcpFrameLink` (it has no Android dependency apart from `Log`, which becomes a
logger lambda). `iosMain` holds the `NSData` bridges.

Packages
- `link` FrameLink, DeviceLink, LinkState, LanHandshake, LanFrameSplitter
- `session` DeviceSession, exceptions, FrameRecorder
- `engine` SyncEngine, SyncPlanner, ReadWindow, FileProgressReporter, CollectionRun
- `archive` ArchiveLayout, ArchiveStore, ArchiveIndex, WriterLeases
- `policy` DeviceContinuityPolicy, SyncResumePolicy, SmartSyncPolicy, LanTokenPolicy,
  TransportSelector, ReconnectPolicy, Claims, CollectionBudget, LanTargetFilter
- `fake` FakeDevice (the current FakeTransport, usable from Android debug, iOS simulator and tests)

### 4.2 Ports (Kotlin signatures)

```kotlin
// link
enum class LinkKind { BLE, LAN, FAKE }

sealed interface LinkState {
    data object Idle : LinkState
    data object Connecting : LinkState
    data object Authenticating : LinkState
    data class Ready(val payloadMax: Int) : LinkState
    data class Lost(val reason: String) : LinkState
}

/** One ordered response pipe. Pushes (STATUS, LIVE) never reach receive(). */
interface FrameLink {
    val kind: LinkKind
    val state: StateFlow<LinkState>
    /** Frames in arrival order, none dropped. Closed when the link ends. */
    val responses: ReceiveChannel<ByteArray>
    /** One request body, 1..512 bytes. LAN adapters add the length envelope. */
    suspend fun send(request: ByteArray)
    fun close()
}

/** What DeviceSession needs beyond file transfer. */
interface DeviceLink : FrameLink {
    val address: String
    val pushes: Flow<Push>               // Push.Status(json bytes) or Push.Live(json bytes)
    suspend fun connect()
    suspend fun readInfo(): ByteArray    // BLE read, or HELLO info JSON on LAN
    suspend fun readStatus(): ByteArray
    suspend fun readLive(): ByteArray
}

// archive. Byte and path based. Paths are relative and come only from ArchiveLayout.
interface ArchiveStore {
    suspend fun length(path: String): Long?                       // null when absent
    suspend fun isRegularFile(path: String): Boolean
    suspend fun writeAt(path: String, offset: Long, bytes: ByteArray)
    suspend fun sync(path: String)
    suspend fun read(path: String, offset: Long, max: Int): ByteArray   // empty at EOF
    suspend fun writeSmallAtomic(path: String, bytes: ByteArray)   // receipts, meta
    suspend fun readSmall(path: String, max: Int): ByteArray?
    /** Rename that never replaces an existing destination. */
    suspend fun moveNoReplace(from: String, to: String)
    suspend fun delete(path: String)
    suspend fun list(prefix: String): List<String>
}

data class ArchivedRecord(
    val station: String, val name: String, val bytes: Long, val crc32: Long,
    val sha256: String, val syncedAtEpochMs: Long, val sourceDevice: String,
)

interface ArchiveIndex {
    suspend fun get(station: String, name: String): ArchivedRecord?
    suspend fun all(station: String): List<ArchivedIdentity>
    /** Android indexes history in the same upsert. iOS writes the receipt. */
    suspend fun record(record: ArchivedRecord)
}

class LanCredential(val station: String, val device: String, val port: Int, val token: ByteArray)

interface CredentialStore {
    suspend fun find(device: String): LanCredential?   // caller zeroes token after use
    suspend fun save(credential: LanCredential)
    suspend fun remove(device: String)
}
```

Common code built on the ports
- `ArchiveLayout` defines `archive/<station>/<name>`, `archive-partial/<station>/<name>.partial`,
  `<...>.partial.meta` and the receipt name in one place (`docs/data-model.md` per rule 4). Pick one
  layout. Per the plan (W6) the old layouts are dropped with no migration.
- `ArchiveVerifier` streams `ArchiveStore.read` in 64 KiB slices through `Crc32` and
  `ParquetVerify.verify` (`ParquetVerify.kt:58`). SHA-256 via a new `expect class Sha256` in
  `:core:protocol` (JVM `MessageDigest`, iOS CommonCrypto `CC_SHA256`).
- `WriterLeases` is the current `FileArchive.withWriterLease` with a Mutex map.
- `SyncEngine.download(session, station, source, entry, maxRead, onProgress): FileOutcome` keeps
  today's Android semantics (per-file outcome, retry, corrupt prefix retry, best-effort CLOSE).
- `CollectionRun.run(session, station, plan, budget, claims, onProgress): CollectionResult` is the
  loop now inside `DeviceConnectionRepository.startSync` (`:946-1075`, loop at `:990`) without the Android service
  and WifiLock (those stay as hooks).
- `LanHandshake.perform(link, token): Frame.Hello` and `LanFrameSplitter` (incremental, cap from
  HELLO) replace `TcpTransport.connect` body and Swift `LanFrameBuffer` and HELLO checks.
- `TransportSelector.choose(known, discovered, preference, hasToken): Choice` with the rule from
  contract:323 and `DeviceConnectionRepository.kt:455-466`.

### 4.3 Swift adapters

Rule 3 forbids per-byte crossings, and the Kotlin ObjC export shows `ByteArray` as `KotlinByteArray`.
So Swift never sees `ByteArray` and never implements a Kotlin `suspend` function.

- `iosMain` provides concrete Kotlin classes that implement the ports and expose a callback style
  to Swift, with `NSData` converted once with the existing pinned `memcpy` helpers
  (`ios/shared/src/iosMain/.../SyncWireData.kt:16-24`).

```kotlin
// iosMain
interface NativeSocket {                         // implemented in Swift by LanSession
    fun send(data: NSData, completion: (NSError?) -> Unit)
    fun close()
}

class IosLanLink(private val socket: NativeSocket, override val address: String) : DeviceLink {
    // Swift calls these from the NWConnection queue, in order.
    fun onBytes(data: NSData)          // feeds LanFrameSplitter, routes pushes and responses
    fun onClosed(reason: String)
    override suspend fun send(request: ByteArray) =
        suspendCancellableCoroutine { c -> socket.send(LanProtocol.encode(request).toNSData()) { e -> /* resume */ } }
    ...
}

interface NativeFiles { /* same shape as ArchiveStore, NSData, completion handlers */ }
class IosArchiveStore(files: NativeFiles) : ArchiveStore

/** Swift entry. Plain callbacks, explicit cancel, no Flow across the boundary. */
class IosCollector(link: IosLanLink, store: IosArchiveStore, index: ArchiveIndex, credentials: CredentialStore) {
    fun start(observer: CollectionObserver): CollectionHandle   // handle.cancel()
}
interface CollectionObserver { fun progress(p: CollectionUpdate); fun finished(r: CollectionResult) }
```

- `suspend` to `async`. Swift calls Kotlin `suspend` functions as `async` through the ObjC export,
  but Swift `Task` cancellation does not cancel the Kotlin coroutine. That is why the Swift entry is
  `start` plus `CollectionHandle.cancel()`, which cancels a Kotlin `Job`. Calls from a non-main
  thread need checking against the Kotlin 2.4.20 export rules in this repo before relying on them
  (check `kotlin.native.binary.objcExportSuspendFunctionLaunchThreadRestriction` in Gradle properties).
  SKIE or KMP-NativeCoroutines stay out per rule 7.
- Isolation. `IosLanLink` lives inside the `LanSession` actor and is called only from its queue.
  `IosCollector` lives in `LanDiscovery` on the main actor. Observer callbacks hop to the main actor
  in Swift, throttled in Kotlin.
- `ByteArray` and `NSData`. One `memcpy` per received socket chunk (up to 64 KiB) and one per sent
  request. File writes are one `NSData` per READ window, as today.

### 4.4 What happens to the current iOS files

| File | Fate |
| --- | --- |
| `ios/App/SensorTransfer.swift` | Deleted. `collect` becomes `CollectionRun`, `TransferProgress` becomes `FileProgressReporter` plus `RateMeter`, `LanLiveness` goes into `IosLanLink`/`LinkLiveness`, `SensorClaims` becomes `Claims`, `LanFrameBuffer` becomes `LanFrameSplitter`. `ArchivePipe` and `ArchiveReply` disappear. |
| `ios/App/LanSession.swift` | Shrinks to an NWConnection adapter implementing `NativeSocket`, calling `onBytes` and `onClosed`. Handshake, HELLO checks, reply queue, deadlines per request and identity check move to Kotlin. Keep `LanPolicy` (interface prohibition, TN3179). |
| `ios/App/SensorArchive.swift` | Becomes the FileManager implementation of `NativeFiles`, keeping file protection, backup exclusion and symlink rejection. CRC table, PAR1, name rule, partial naming and receipt decisions are deleted. |
| `ios/App/LanCredentials.swift` | Implements `CredentialStore` (Keychain). Record validation moves to common. Store the token as its own item to avoid the base64 copy (`ios/AGENTS.md`, last bullet of the Swift 6 list). |
| `ios/App/LanDiscovery.swift` | Keeps NWBrowser, UIKit background time and per-device task ownership. Resume decisions call `SyncResumePolicy`, budget calls `CollectionBudget`. |
| `ios/App/BluetoothCompanion.swift` | Later implements `DeviceLink` for BLE (Core Bluetooth `NativeGatt`), which brings BLE file sync and config to iOS for free. |
| `ios/shared/.../SyncWire.kt`, `SyncWireData.kt` | `SyncWire` request builders and `decode` vanish once Swift no longer frames requests. `identity()` moves to `DeviceContinuityPolicy` and a common `SyncIdentity` parser. The `toNSData` and `toByteArray` helpers move into `:core:sync` iosMain. |
| `ios/tests/TransferChecks.swift`, `ArchiveChecks.swift` | Transfer cases move to `:core:sync` `commonTest`. Swift keeps only FileManager adapter checks (protection, symlink, atomic move). |

### 4.5 How Android shrinks

- `A/sync/` keeps nothing but Hilt bindings. `FileArchive` becomes `JvmArchiveStore` in `:core:sync`
  jvmMain (base dir injected). `RoomArchiveIndex` in `app/` wraps `ArchivedFileDao` plus
  `HistoryIndexer`.
- `A/ble/AqTransport.kt`, `DeviceSession.kt`, `FrameRecorder.kt` move. `BleTransport` implements
  `DeviceLink` (rename of `AqTransport`, same members).
- `A/lan/TcpTransport.kt` becomes `TcpDeviceLink` in jvmMain using `LanHandshake` and
  `LanFrameSplitter`. `LanDiscovery` stays (NSD), `LanDevice` parsing goes common.
- `DeviceConnectionRepository` loses `tryLan`, `openSession` identity logic, `reconnectLoop`,
  `startSync` loop and `syncInBackground` transfer body to common `LinkSupervisor`, `DeviceEnrollment`
  and `CollectionRun`. This lines up with W7 in the plan.

## 5. Migration steps

Sizes S (under a day), M (1 to 3 days), L (more). Steps in the same wave touch disjoint files and
can run in parallel worktrees. Each step ends with `./gradlew --no-daemon verify`, plus the iOS
gate where iOS files change.

### Wave A. Decide the drift (owner decision, S, no code)

A0. Pick one answer for each drift in section 2 and record it in `docs/sync-engine.md` and, where it
touches the wire (OPEN timeout, max_read default, OPENED echo), confirm against contract:206-214.
Recommended defaults, Android semantics for per-file isolation, corrupt prefix retry and best-effort
CLOSE, 60 s OPEN timeout, max_read default 16384 per contract:101, iOS strictness for OPENED echo and
CHUNK bounds, station charset `[0-9A-Za-z._-]` (or tighten both if the firmware never emits a dot,
check `../aq-parquet`), budget that waits.

### Wave B. Create the module and move pure code (parallel)

B1 (M). Create `:core:sync` with `link` and `session`. Move `AqTransport` (as `DeviceLink`),
`DeviceSession`, `FrameRecorder`. Replace `System.*` and `ByteArrayOutputStream`.
Files `A/ble/AqTransport.kt`, `A/ble/DeviceSession.kt`, `A/ble/FrameRecorder.kt`, new module.
Tests move `DeviceSessionPollingTest`, `FrameRecorderTest` to `commonTest`. Add commonTest for
terminal-frame mapping, ERROR op matching, stale drain, raw denylist.

B2 (S). Move policies to `:core:sync/policy`. `DeviceContinuityPolicy`, `SyncResumePolicy`,
`SmartSyncPolicy`, `LanTokenPolicy`, `ConnectionIntent`, `DeviceIdentityClaims`, `CollectionBudget`,
`ReadWindow` (taking `LinkKind`), `LanFailure`. Tests `DeviceContinuityPolicyTest`,
`SyncResumePolicyTest`, `SmartSyncPolicyTest`, `LanTokenPolicyTest`, `ConnectionIntentTest` to
`commonTest`. Conflicts only with B1 on `ReadWindow`'s import, so land B1 first or keep
`ReadWindow` in B3.

B3 (S). Move `SyncPlanner`, `ManifestState`, `FileProgressReporter`, `SyncProgress`, `RateMeter`,
`collectionProgress`. Tests `SyncPlannerTest`, `ManifestStateTest`, `FileProgressReporterTest`.

B4 (S). `expect class Sha256` in `:core:protocol` with JVM and iOS actuals. commonTest with known
vectors on both targets. Independent of B1 to B3.

B5 (S). Common `LanFrameSplitter` and `LanHandshake` validation in `:core:protocol` or `:core:sync/link`.
commonTest ports `LanFramingTest` cases plus the Swift `frameBufferChecks` (`TransferChecks.swift:110`).

### Wave C. Ports and engine (after B)

C1 (L). `ArchiveStore`, `ArchiveIndex`, `ArchiveLayout`, `ArchiveVerifier`, `WriterLeases`, and
`SyncEngine` on the ports. `JvmArchiveStore` in jvmMain. An in-memory `FakeArchiveStore` in
commonTest. Android `FileArchive` and `SyncEngine` become bindings. Tests `WindowRecoveryTest`,
`ConcurrentArchiveTest` move to `commonTest` with the fake store, and add cases for the iOS-only
rules adopted in A0 (CHUNK past window, duplicate LIST names, size < 12). Must run on JVM and iOS
simulator (`verifyIos`).

C2 (M, parallel with C1). `FakeDevice` in common from `FakeTransport`, removing `ByteBuffer` and
`String.format`. Debug app binds it. Becomes the transport for all engine tests.

C3 (M, parallel with C1). `CredentialStore` port. Android adapter over `LanCredentialStore`
storing raw bytes, not hex. Swift Keychain adapter later in D2.

### Wave D. iOS adopts the engine (after C)

D1 (M). `iosMain` bridges `IosLanLink`, `IosArchiveStore`, `IosCollector`, `CollectionHandle`.
Swift `LanSession` becomes `NativeSocket`. `SensorArchive` becomes `NativeFiles`.
Tests: commonTest already covers logic. Add iosTest for `NSData` round trips (extend
`ios/shared/src/iosTest/.../SyncWireDataTest.kt`) and cancellation through `CollectionHandle`.

D2 (S). Swift `LanCredentials` implements the Kotlin `CredentialStore` protocol, token as raw item.

D3 (S). Delete `SensorTransfer.swift`, the CRC table, `SyncWire` request builders, and move the
remaining `TransferChecks` cases to commonTest. Keep `ArchiveChecks` for FileManager behaviour.
Update `ios/AGENTS.md` test section and the Xcode test target file list.

### Wave E. Connection management (after C, parallel with D)

E1 (L). `TransportSelector`, `LinkSupervisor` (watch, reconnect 3 x 5 s, resume) and
`DeviceEnrollment` in common, extracted from `DeviceConnectionRepository.kt:377-660, 738-830`.
Overlaps W7, so run as part of it. commonTest with the fake device and virtual time.

E2 (M). `TcpDeviceLink` in jvmMain on `LanHandshake`. Android `TcpTransport` deleted.
`TcpTransportTest` moves to jvmTest of `:core:sync`.

E3 (L, later). iOS BLE `DeviceLink` over Core Bluetooth (`NativeGatt`). Brings BLE file sync,
Wi-Fi config and the BLE then LAN choice to iOS.

### Real-phone evidence needed

- 2.2 OPEN timeout on iPhone with a large file (current 10 s), before and after the change.
- Every change to `LanSession`, `TcpTransport` or the handshake. LAN radio behaviour, Local Network
  prompt, background expiry and resume on iPhone, Wi-Fi lock on Android.
- D1 to D3 end to end. iPhone LAN collection with window loss, resume after background expiry,
  cancel mid window, two sensors at once. Record in `docs/status.md`.
- E3 is radio work on both phones (bond, encrypted subscribe, MTU on iOS, notification loss).
- Throughput comparison after the engine swap (owner already reported slow LAN on both phones,
  `docs/status.md:371-375`).
- Host and simulator gates (commonTest on JVM and iOS simulator) cover A0 to D3 logic but do not
  establish any of the above.
