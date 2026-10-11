# Android data layer

Read only the matching concern in repository-root `docs/README.md` before
editing. Core codec and analysis
logic belongs in `:core:*`; this layer adapts Android BLE, LAN, files, Room and
WorkManager. Each physical connection has one shared `LinkSession`; `DeviceSession` is
a typed view. `DeviceFacade` routes interactive and worker collection through one
`CollectionController` per address. `DeviceLinkOwner` owns connections, identity, manual
commands and idle reconnect. `DeviceConnectionRepository` is a compatibility typealias.
The shared `ArchiveCollector` plans and the shared `SyncEngine` downloads through archive ports.

| Change | Owner | Contract / test |
| --- | --- | --- |
| GATT, pairing, frames | `ble/`, `DeviceLinkOwner.kt` | `docs/transports.md`, `docs/protocol-quickref.md`, fake transport tests |
| TCP, discovery, token, WifiLock | `lan/` | `docs/transports.md`; mDNS addresses only, token from bonded BLE |
| Plan, read windows, resume, archive | `DeviceFacade.kt`, `core/sync/` | `docs/sync-engine.md`; fake faults and CRC/PAR1 verification |
| Worker, foreground lifetime, triggers | `background/` | `docs/background-sync.md`; all triggers enqueue the same worker |
| Index, derived history, Room | `history/`, `local/` | `docs/history-pipeline.md`, `docs/data-model.md` |

- Serialize GATT operations and session requests. A lost chunk or bad
  `READ_END.next_offset` retries from locally verified bytes; never trust a
  device-reported offset over received bytes. Keep read windows measured.
- No user-facing sentences here. Snackbar events carry a `DeviceMessage` code with typed
  arguments (`DeviceMessage.kt`); the UI maps it to a string resource. Exception text and
  protocol detail go to the log or a Developer-level field only.
- No secret is logged. `SET_CONFIG` is recorded as opcode only. Pairing and
  state-changing calls need the explicit paths and confirmations in root
  `AGENTS.md`; `DeviceSession.rawCommand` refuses `REBOOT` and `SET_CONFIG`.
- Plaintext LAN tokens stay in the data layer. DAO rows carry ciphertext; only
  `LanCredentialStore` decrypts, when a LAN transport is built.
- Coroutines: wrap suspend calls in `suspendRunCatching`, never `runCatching`.
  Inject dispatchers, `@ApplicationScope`, `WallClock` and `MonotonicTime`
  (`data/coroutines/`, `data/di/CoroutinesModule.kt`). Commands and collection reservations
  check and set under the link owner's lock. `CollectionAdmission` atomically excludes
  interactive and worker collections process-wide. `CollectionBudget` shares two transfer
  permits across both. Ownership remains held through cleanup.
- Interactive PROCESS leases start the `connectedDevice` foreground service from the tap,
  with `dataSync` as the permission fallback. They cover preparation, retry waiting and
  finalization. Active retries belong to `CollectionController`, idle reconnect to the link owner.
  Workers use their own `dataSync` foreground notification or bounded ordinary job, never
  a second app service. Each attempt has an eight-minute deadline including preparation and
  finalization. `SyncWorker.notificationIdFor` derives a stable ID from the WorkRequest.
  Workers do not pair or scan. LAN WIFI leases cover LIST/PLAN, transfer and finalization
  after a permit is acquired, and are released in `finally`.
- Network use outside BLE and LAN is limited to the planned anonymous, read-only
  discover feed and basemap GETs, and per-device opt-in uploads
  (`docs/devices-and-discovery.md`). No other remote call belongs in this layer.
- Preserve immutable Hive paths and station epochs. Room migrations must keep
  old archives and be validated against exported schemas.
