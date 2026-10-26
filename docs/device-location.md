# Private device location

Device settings can provision the monitoring station's H3 area over the existing
authenticated BLE or LAN session. This is available in shared Android/iOS UI on
firmware that supports GET_CONFIG page 1. Legacy firmware shows unsupported.

1. Stand beside the sensor and open Configure location.
2. Choose a maximum H3 resolution, initially 5, and optionally enter the
   owner-declared assigned ISO alpha-2 country code, such as EG.
3. Tap Use phone location. Only this tap requests foreground approximate OS
   access and a one-shot fix. Cancel, denial or timeout leaves a failed-fix note.
4. Review and tap Save on device. Readback must match before the app says saved.
   Clear location and Save removes the cell. This flow does not enable uploads.

The platform adapter converts the temporary fix with the existing official H3
binding before returning a cell. UI/controller state and wire requests contain
only the cell, cap and declared country. The exact fix is never written to app
storage, logged, sent to firmware or reverse-geocoded through a remote service.
There are no background updates. Dismissing the dialog cancels the request.
Android retains only ACCESS_COARSE_LOCATION. iOS retains When In Use and reduced
accuracy by default. A small chosen cell cannot improve an approximate OS fix.

Resolution 0 through 15 is configurable. Lower values disclose a larger area;
resolution 5 averages about 253 km², with variable actual cell area. Finer values
can expose a particular place. Raising precision after coarsening cannot recover
the original fix; the app requires a new fix. Firmware applies the maximum again
before saving and derives the published center from the retained cell. Country
is declared by the owner, not inferred from the cell or used as NTP residency
proof. Exact GPS, cell-center coordinates and administrative country are different
things. See the firmware-owned [time/location contract](../../aq-parquet/docs/shared/time-and-location.md).

## Code boundaries

- `core/protocol` owns the typed page model, page selector and cell-only patch.
- `core/sync` reuses the existing serialized request and config-chunk parser.
- `feature/device` owns shared draft/readback behavior and permission-triggered UI.
- Its Android and iOS source sets own the temporary OS fix, cancellation and H3
  conversion. Android repository and iOS bridge retain their existing command guards.
- `LiveProfiles` resolves LIVE from the `info.dict` digest through the bundled board
  templates in `core/model/bundled`. Only the current digest of each board is bundled. An
  older or unknown digest still syncs verified files but has no LIVE profile.

Cross-platform host and simulator gates verify parsing, old firmware behavior,
coarsening, late-fix suppression and readback decisions. A phone run beside a
real station is still required to qualify permission timing, OS fix quality and
radio behavior. No phone evidence is claimed by these host checks.
