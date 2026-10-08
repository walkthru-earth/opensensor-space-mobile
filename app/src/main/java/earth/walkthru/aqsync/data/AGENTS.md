# Android data layer

Read only the matching concern in repository-root `docs/README.md` before
editing. Core codec and analysis
logic belongs in `:core:*`; this layer adapts Android BLE, LAN, files, Room and
WorkManager. One `AqTransport` and one `DeviceSession` serve both foreground and
background sync; do not fork transfer logic.

| Change | Owner | Contract / test |
| --- | --- | --- |
| GATT, pairing, frames | `ble/`, `DeviceConnectionRepository.kt` | `docs/transports.md`, `docs/protocol-quickref.md`, fake transport tests |
| TCP, discovery, token, WifiLock | `lan/` | `docs/transports.md`; mDNS addresses only, token from bonded BLE |
| Plan, read windows, resume, archive | `sync/` | `docs/sync-engine.md`; fake faults and CRC/PAR1 verification |
| Worker, foreground lifetime, triggers | `background/` | `docs/background-sync.md`; all triggers enqueue the same worker |
| Index, derived history, Room | `history/`, `local/` | `docs/history-pipeline.md`, `docs/data-model.md` |

- Serialize GATT operations and session requests. A lost chunk or bad
  `READ_END.next_offset` retries from locally verified bytes; never trust a
  device-reported offset over received bytes. Keep read windows measured.
- No secret is logged. `SET_CONFIG` is recorded as opcode only. Pairing and
  state-changing calls need the explicit paths and confirmations in root
  `AGENTS.md`; `DeviceSession.rawCommand` refuses `REBOOT` and `SET_CONFIG`.
- Plaintext LAN tokens stay in the data layer. DAO rows carry ciphertext; only
  `LanCredentialStore` decrypts, when a LAN transport is built.
- Coroutines: wrap suspend calls in `suspendRunCatching`, never `runCatching`.
  Inject dispatchers, `@ApplicationScope`, `WallClock` and `MonotonicTime`
  (`data/coroutines/`, `data/di/CoroutinesModule.kt`). Commands and background
  runs take the repository's operation gate; job handles change under its lock.
- Work that outlives a screen uses WorkManager and its `dataSync` foreground
  notification. The worker does not pair or scan. A LAN WifiLock is held only
  during an active LAN transfer and released in `finally`.
- Preserve immutable Hive paths and station epochs. Room migrations must keep
  old archives and be validated against exported schemas.
