# Agent map — opensensor.space Android

Hardware-neutral Android companion for `../aq-parquet`. This checkout is named
`opensensor-space-android`; its remote is `opensensor-space-android`. Start with this
file, then open only the concern you are changing in `docs/README.md`.

| Task | Open next | Code |
| --- | --- | --- |
| Current status and real-phone evidence | `docs/status.md`, then `../aq-parquet/docs/boards/*/bench-verified.md` | — |
| App layers and dependencies | `docs/architecture.md`, `core/AGENTS.md` | `core/`, `app/` |
| BLE, LAN, sync, pairing, background work | `docs/transports.md`, `docs/sync-engine.md`, `docs/background-sync.md`, `app/src/main/java/earth/walkthru/aqsync/data/AGENTS.md` | `data/` |
| Room, history, Parquet | `docs/data-model.md`, `docs/history-pipeline.md`, `docs/parquet.md` | `data/history/`, `data/local/`, `core/` |
| Screens and interaction | `docs/screens.md`, `app/src/main/java/earth/walkthru/aqsync/ui/AGENTS.md` | `ui/` |
| Build, hooks and release | `docs/build-and-release.md` | `tools/`, `.github/` |

## Boundaries

- The wire and telemetry contracts belong to `../aq-parquet/docs/shared/`. Resolve
  ambiguity there before changing this app's mirror. Preserve frame layouts,
  opcodes, UUIDs, JSON keys and Hive archive paths across both repositories.
- The phone archive is local and works without internet. BLE is always available;
  LAN connects only to devices found through `_aqsync._tcp` mDNS, using a token
  obtained over bonded BLE. No cloud connection without a separate explicit
  opt-in. No analytics, accounts or location permission.
- A downloaded file becomes synced only after size, CRC-32 and `PAR1` head/tail
  checks pass. Missing measurements stay null. Never ask firmware to delete,
  rename or rewrite archive files.
- Keep PIN, Wi-Fi PSK and LAN token out of logs, frames, screenshots and docs.
  LAN tokens remain encrypted at rest. Device-changing actions are explicit UI
  actions; reboot, token rotation, forgetting bonds and pairing mode `none`
  require confirmation. Debug raw commands cannot invoke `REBOOT` or `SET_CONFIG`.
- `:core:*` stays plain JVM and reusable without Android, Hilt or Room.
  Transport and storage work stays out of composables. Sensor semantics come
  from the metric registry, never fabricated defaults.
- Treat host tests and phone observations separately. Record new phone results
  in the board's `bench-verified.md` and update `docs/status.md`; do not present
  a host fixture as hardware qualification.

## Gate

Use the bundled toolchain and run `./gradlew --no-daemon verify` before a commit
(lint, debug APK and all unit tests). The installed pre-commit hook checks hygiene,
secrets and ktlint; the pre-push hook runs `verify`. For setup and CI details see
`docs/build-and-release.md`. Phone behavior requires a phone run and dated evidence.
