# docs/, the shared mobile project guide, by concern

One file per concern. Each starts with a **Scope / Code / Related** header so you can decide in a
few lines whether it is the right file, then a compact Mermaid diagram, then the facts and the
decisions that belong to that concern only. Current verification is recorded in [status.md](status.md); dated results describe their original revision.

## Product direction in one paragraph

opensensor.space supports open sensors of every kind, and air quality is the first family. A new
user lands on a public H3 discover map (sample data until the S3 and source.coop feed is live),
then pairs and collects their own devices over BLE or LAN into a verified local archive. Simple
screens explain readings in plain words. Technical detail lives behind the Detailed and
Developer levels. Android and iOS share rules, sync and design. Read
[devices-and-discovery.md](devices-and-discovery.md) and [cross-platform-guide.md](cross-platform-guide.md)
before planning new work. Use [architecture.md](architecture.md), [sync-engine.md](sync-engine.md) and
[ios.md](ios.md) for current source ownership.

## Routing table

| I need to… | Read | Then open the code in |
| --- | --- | --- |
| Know what is verified on Android, iOS simulator, and real devices | [status.md](status.md) | `../aq-parquet/docs/boards/*/bench-verified.md` |
| Keep design and sync behaviour the same on iOS and Android, see what still differs and why, add a sensor family or transport | [cross-platform-guide.md](cross-platform-guide.md) | `core/*/src/commonMain/`, `ios/App/`, `app/` |
| Understand the product direction, device families, the public H3 discover map and sharing rules | [devices-and-discovery.md](devices-and-discovery.md) | `core/publicfeed/`, `feature/discover/` |
| Know which values identify a sensor and how the app recovers after a reset or reflash | [device-identity.md](device-identity.md) | `data/DeviceLinkOwner.kt`, `data/ble/`, `data/local/database/` |
| Build or extend the iOS app and shared Kotlin core | [ios.md](ios.md), `../ios/AGENTS.md` | `core/*/src/commonMain/`, `ios/` |
| Understand modules, layers, who depends on whom, "what a change touches" | [architecture.md](architecture.md) | `core/*/build.gradle.kts`, `app/build.gradle.kts` |
| Change a screen, the nav graph, detail levels | [screens.md](screens.md) | `app/…/ui/`, `data/settings/AppSettings.kt` |
| Permissions, privacy manifest, Play Data safety and App Store label | [store-compliance.md](store-compliance.md) | `app/src/main/AndroidManifest.xml`, `ui/permissions/`, `ios/App/` |
| Look up an opcode, frame type, error code, UUID, LAN framing constant | [protocol-quickref.md](protocol-quickref.md) | `core/protocol/…/Frames.kt`, `AqUuids.kt`, `LanFraming.kt` |
| Touch BLE / LAN pipes, pairing, the LAN token, mDNS, reconnect | [transports.md](transports.md) | `data/ble/`, `data/lan/`, `data/DeviceLinkOwner.kt`, `core/sync/link/` |
| Touch file download, verification, lost windows, resume, archive paths | [sync-engine.md](sync-engine.md) | `core/sync/`, `data/archive/`, `data/DeviceFacade.kt`, `ios/shared/` |
| Touch auto-sync, WorkManager, the foreground notification, WifiLock, or the triggers that start a run early (companion presence, advertising flags, Wi-Fi arrival) | [background-sync.md](background-sync.md) | `data/background/`, `data/lan/LanWifiLock.kt`, `core/protocol/.../Advertisement.kt` |
| Touch Room tables, migrations, DataStore keys, on-disk layout, backup | [data-model.md](data-model.md) | `core/database/` (`data/local/database/`, `schemas/`), `res/xml/data_extraction_rules.xml` |
| Touch the index → tiers → derived-day → chart pipeline, metrics, moods, time estimate | [history-pipeline.md](history-pipeline.md) | `core/history/`, `data/history/`, `ui/history/` |
| Reproduce history host benchmarks and understand their limits | [perf/history-2026-10-11.md](perf/history-2026-10-11.md) | `tools/history-benchmark/` |
| Recover UTC after reboot, automatic clock sync, corrected day partitions | [time-reconciliation.md](time-reconciliation.md) | `core/archive/`, `core/history/`, `data/history/`, `ios/` |
| Touch the Parquet reader/writer, GeoParquet, codecs, fixtures, oracles | [parquet.md](parquet.md) | `core/parquet/`, `tools/*.py` |
| Build, sign, release, run hooks/CI, use the fake device | [build-and-release.md](build-and-release.md) | `tools/`, `.github/workflows/`, `data/fake/` |
| Avoid a mistake that was already made | [lessons-learned.md](lessons-learned.md) | (grouped by the concern above) |

Rules (non-negotiables and AI delegation guidance) live in [`../AGENTS.md`](../AGENTS.md).
The iOS app boundary is documented in [`../ios/AGENTS.md`](../ios/AGENTS.md). The protocol
contract lives in the firmware repo, `../aq-parquet/docs/shared/ble-sync-protocol.md`, never here.

## How the concerns connect

```mermaid
flowchart LR
  F[devices-and-discovery] --> U[screens]
  F --> D
  P[protocol-quickref] --> T[transports]
  T --> S[sync-engine]
  S --> B[background-sync]
  S --> D[data-model]
  D --> H[history-pipeline]
  Q[parquet] --> S
  Q --> H
  H --> U
  T --> U
  B --> U
  X[cross-platform-guide] -.rules for.-> S & U & F
```

Read left to right: a byte on the wire (`protocol`) travels a pipe (`transports`), is verified and
archived (`sync-engine`), interactively or by the worker (`background-sync`), into files + Room
(`data-model`), which the `history-pipeline` turns into tiers and charts for the `screens`.
`parquet` is the codec both the sync verifier and the history pipeline read with.
`devices-and-discovery` defines what a device and a metric are, and the public map that sits
beside your own devices. `cross-platform-guide` holds the rules that keep both phones the same.

## Conventions in these files

- **Code paths** in Android docs are relative to `app/src/main/java/earth/walkthru/aqsync/`;
  shared KMP rules live in `core/<module>/src/commonMain/`, with JVM adapters in `jvmMain/`;
  iOS app and Apple adapters live under `ios/App/`, with Kotlin presentation code in
  `ios/shared/`. Android/core packages are `earth.walkthru.aqsync`; the iOS presentation
  package is `earth.walkthru.opensensor`.
- **"Uses → affects"** sections say what a file consumes and what breaks or changes downstream if
  you edit it — search there before editing.
- Dates are the day a decision was made or a fact was measured; "unverified" means not in
  `bench-verified.md`.
- Host/JVM tests, iOS simulator builds, Android phone runs, and iPhone runs are separate
  evidence. A result for one platform does not qualify another.

Private station setup: [device-location.md](device-location.md) describes tap-only H3 provisioning, precision and country policy.
