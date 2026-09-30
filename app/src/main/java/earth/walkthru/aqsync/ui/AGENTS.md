# Android UI

Open root `docs/screens.md` for navigation and screen roles; open
`docs/history-pipeline.md` for metric meaning. Composables render ViewModel
state and dispatch intent. BLE, file, Room and long-running work belong in the
data layer. Reuse shared components in `components/` when multiple screens
need the same behavior.

- Known devices are devices the owner connected to; a nearby advertisement is
  only a scan result. Forget must stay forgotten until explicit reconnection.
- Simple mode must explain the current reading and archive state without
  Debug. Advanced mode only reveals extra information; it must not alter sync.
- Distinguish stale, unavailable and unverified readings from zero. Keep
  archived history usable when a device is disconnected or a worker owns it.
- Device-changing actions require an explanation, and destructive ones require
  confirmation. Preserve the explicit reboot path in root `AGENTS.md`.
