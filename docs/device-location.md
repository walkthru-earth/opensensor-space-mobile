# Private device location

Device settings can provision the monitoring thing's H3 area over the existing
authenticated BLE or LAN session. This is available in shared Android/iOS UI on
firmware that supports GET_CONFIG page 1. Legacy firmware shows unsupported.

1. Stand beside the sensor and open Configure location.
2. Optionally enter the owner-declared assigned ISO alpha-2 country code,
   such as EG. The shared area is always one H3 resolution 8 cell, about 0.74 km². A country code that is not two letters A to Z says so under the
   field and keeps Save off.
3. Tap Use phone location. Only this tap requests foreground approximate OS
   access and a one-shot fix. While it waits, Stop cancels it, and it gives up
   after 25 seconds. Cancel, denial or timeout leaves a failed-fix note. Stop keeps
   nothing and shows no note.
4. Review and tap Save on device. Readback must match before the app says saved.
   Clear location and Save removes the cell. This flow does not enable uploads.

The platform adapter converts the temporary fix with the existing official H3
binding before returning a cell. UI/controller state and wire requests contain
only the cell, cap and declared country. The exact fix is never written to app
storage, logged, sent to firmware or reverse-geocoded through a remote service.
There are no background updates. Dismissing the dialog cancels the request.
After a read or save the phone keeps the readback cell of a fixed Thing in `thing_place`, so
Discover can show the user's own pin there. It keeps the cell only, never the published centre.
A moving Thing keeps no cell on the phone, and Forget deletes the cell of every Thing the sensor named.
Android retains only ACCESS_COARSE_LOCATION. iOS retains When In Use and reduced
accuracy by default. A small chosen cell cannot improve an approximate OS fix.

Every Thing, fixed or moving, uses H3 resolution 8, about 0.74 km² with an edge of about
530 m. Actual cell area varies. There is no resolution menu. The card shows the area size
(`LocationArea`), and Detailed adds the resolution number and the cell index.
`ConfigFields` accepts only `location.resolution=8`, and `ConfigPatch.Builder.location(cell, country)`
takes only an empty or resolution 8 cell and always sends 8. Firmware still takes 0 to 15 until
firmware #139. A finer cell read from a board becomes its resolution 8 parent. A coarser one cannot
be refined, so the app asks for a new fix before Save. Firmware applies the maximum again
before saving and derives the published center from the retained cell. Country
is declared by the owner, not inferred from the cell or used as NTP residency
proof. Exact GPS, cell-center coordinates and administrative country are different
things. See the firmware-owned [time/location contract](../../opensensor-space-firmware/docs/shared/time-and-location.md).

## Code boundaries

- `core/protocol` owns the typed page model, page selector and cell-only patch.
  `location.gnss_cell` is the board's own GPS cell offer, sent from Waveshare `v2.18` (firmware #147).
  `LocationConfig.gnssCell` keeps it only when it is a resolution 8 cell. It is null both when the key
  is absent (no receiver) and when it is empty (no fresh fix). `gnssCellText` keeps that difference.
  `thing.mobility` (`fixed` or `moving`) has a `ConfigFields` row and
  `ConfigPatch.Builder.thingMobility`. Nothing sends it yet. `thing.setup` reads as
  `ThingConfig.needsSetup`.
- `core/model` `H3Cell` checks a cell index and its hex form in pure Kotlin. `core/publicfeed`
  `H3Grid.gridDisk` lists a cell and its rings on the platform binding.
- `core/sync` `ThingSetupPolicy.needsSetup` is the one rule for a Thing that still needs setup.
  See [sync-engine.md](sync-engine.md#thing-setup).
- `core/sync` reuses the existing serialized request and config-chunk parser.
- `feature/device` owns shared draft/readback behavior and permission-triggered UI.
- Its Android and iOS source sets own the temporary OS fix, cancellation and H3
  conversion. Android repository and iOS bridge retain their existing command guards.
- `DeviceCapabilities` reads LIVE through the thing of the `info.dict` digest, from the
  bundled board templates in `core/model/bundled`. Only the current digest of each board is
  bundled. An older or unknown digest still syncs verified files but has no LIVE view.

Cross-platform host and simulator gates verify parsing, old firmware behavior,
coarsening, late-fix suppression and readback decisions. A phone run beside a
real thing is still required to qualify permission timing, OS fix quality and
radio behavior. No phone evidence is claimed by these host checks.
