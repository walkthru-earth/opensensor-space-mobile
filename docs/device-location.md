# Private device location

Device settings can provision the monitoring thing's H3 area over the existing
authenticated BLE or LAN session. This is available in shared Android/iOS UI on
firmware that supports GET_CONFIG page 1. Legacy firmware shows unsupported.

1. Open Choose area. A full screen map shows the H3 resolution 8 cells around its middle, each
   about 0.74 km². Below zoom 12 the cells are too small to tap and the map asks to zoom in. It
   opens on the saved cell, or on the phone's time zone region.
2. Move the map by hand, by place search, or with the location button. Place search reads the
   bundled GeoNames list on the phone, so typing never leaves it. The location button is the only
   place that asks for foreground approximate OS access and a one-shot fix. While it waits, the same
   button stops it, and it gives up after 25 seconds. Denial or timeout shows a failed fix note.
   The fix and a picked place only move the map to a cell. Neither chooses one.
3. Tap the cell where the sensor stands. It fills in the brand blue. A screen reader has a
   "Choose the area in the middle" action instead of aiming at a cell.
4. Optionally enter the owner-declared assigned ISO alpha-2 country code, such as EG. A country
   code that is not two letters A to Z says so under the field and keeps Save off.
5. Tap Save. Readback must match before the app says saved. Clear and Save removes the cell.
   This flow does not enable uploads.

With the network switch in Settings off the map is a plain paper grid with no basemap, and the
cells and search still work. With it on the basemap tiles come from the public Protomaps
build and the map info credits OpenStreetMap, Protomaps and GeoNames. There is no offline basemap
download.

The picker emits only a resolution 8 cell id (`CellPickerGrid.pickable`), from the `h3` property of
the tapped outline. The camera centre becomes a cell as soon as it rests and is not kept. The
platform adapter converts the temporary fix with the official H3 binding before it returns a cell,
and that cell only moves the camera (`CellFocus`). `DeviceConfigController.acceptLocationCell`
drops anything that is not a resolution 8 cell id. UI and controller state and wire requests contain
only the cell, the resolution and the declared country. The exact fix is never written to app
storage, logged, sent to firmware or reverse-geocoded through a remote service. There are no
background updates. Leaving the picker cancels the request.
After a read or save the phone keeps the readback cell of a fixed Thing in `thing_place`, so
Discover can show the user's own pin there. It keeps the cell only, never the published centre.
A moving Thing keeps no cell on the phone, and Forget deletes the cell of every Thing the sensor named.
Android retains only ACCESS_COARSE_LOCATION. iOS retains When In Use and reduced
accuracy by default. A small chosen cell cannot improve an approximate OS fix.

Every Thing, fixed or moving, uses H3 resolution 8, about 0.74 km² with an edge of about
530 m. Actual cell area varies. There is no resolution menu. The card shows whether a cell is
set, and Detailed adds the cell index. The picker shows the area size (`LocationArea`), and
Detailed adds the resolution number there.
`ConfigFields` accepts only `location.resolution=8`, and `ConfigPatch.Builder.location(cell, country)`
takes only an empty or resolution 8 cell and always sends 8. Firmware still takes 0 to 15 until
firmware #139. A finer cell read from a board becomes its resolution 8 parent. A coarser one cannot
be refined, so the app asks for a new pick before Save. Firmware applies the maximum again
before saving and derives the published center from the retained cell. Country
is declared by the owner, not inferred from the cell or used as NTP residency
proof. Exact GPS, cell-center coordinates and administrative country are different
things. See the firmware-owned [time/location contract](../../opensensor-space-firmware/docs/shared/time-and-location.md).

## Site

The owner site describes where and by whom a Thing is deployed (firmware #148, mobile #272). It
holds five optional values. Device settings show them under the area as one "Location and site"
card, and the setup wizard asks for them on its Site page after Location.

| Field | Key | Cap (UTF-8 bytes) | Rule |
| --- | --- | --- | --- |
| Name | `owner.title` | 64 | text |
| Organisation | `owner.provider` | 64 | text |
| Link | `owner.url` | 96 | `https://`, then an ASCII letter or digit, no `@` before the first `/`, `?` or `#`, only the RFC 3986 characters |
| Description | `owner.description` | 160 | text |
| Deployment ID | `thing.deployment_id` | 32 | `a` to `z`, `0` to `9` and inner `-`, never `unknown` |

Text refuses C0 controls, DEL, C1 controls and invalid UTF-8, and keeps quotes, backslashes and
other Unicode. A link also refuses spaces, quotes, backslashes and non-ASCII. These are the
firmware `check_owner_value` rules, mirrored in `SiteRules` (`core/model`). The phone checks
each value before it sends anything and shows the reason under the field. Empty always passes,
because it clears the key.

- Read. GET_CONFIG page 5 answers `{"owner":{"title","provider","url"},"thing":{"deployment_id"}}`
  and page 6 `{"owner":{"description"}}`. `DeviceSession.getSite` reads both and merges them
  (`siteOf`). Page 5 `ERROR` 1, or a page 5 without `owner` from firmware that ignores the page
  byte, means the board has no site keys. The fields then stay hidden and the card keeps its
  "Location" title. They are never shown empty. Once page 5 answered, a page 6 error or a page 6
  without `owner` is a failed read, not an empty description.
- Write. Only the Save site button in Device settings, or Next on the setup Site page, sends.
  Typing sends nothing. Save sends only the values that differ from the board's last answer.
  An emptied value goes as `key=` and clears it, a value that stays empty is not sent. The site
  keys go in a `SET_CONFIG` of their own, and `ConfigPatch.Builder.build` refuses a patch that
  mixes them with any other key, so a site refusal can never cost a location or name write.
  Pages 5 and 6 are read back after the write, and the app says saved only when they match.
- A refused write says so under the card. After a failed site write the next successful one reads
  pages 0 and 1 again too, as the firmware asks after an `nvs` error.
- The board never logs owner values, and only `deployment_id` reaches the Parquet files, so a
  change splits the current file. Owner values are not secrets. The Debug frame list redacts
  every `SET_CONFIG`, and a CONFIG reply shows them as the board sent them.

## Code boundaries

- `core/protocol` owns the typed page model, page selector and cell-only patch.
  `location.gnss_cell` is the board's own GPS cell offer, sent from Waveshare `v2.18` (firmware #147).
  `LocationConfig.gnssCell` keeps it only when it is a resolution 8 cell. It is null both when the key
  is absent (no receiver) and when it is empty (no fresh fix). `gnssCellText` keeps that difference.
  `thing.mobility` (`fixed` or `moving`) has a `ConfigFields` row and
  `ConfigPatch.Builder.thingMobility`. Nothing sends it yet. `thing.setup` reads as
  `ThingConfig.needsSetup`. `Request.GetConfigPage` asks for page 5 or 6, `OwnerConfig` and
  `ThingConfig.deploymentId` parse them, and `ConfigPatch.Builder.site` checks each site value.
  `ConfigFields.SITE_KEYS` are the site keys and `ConfigGroup.SITE` names their card.
- `core/model` `ThingSite`, `SiteField` and `SiteRules` hold the site values, their caps and the
  firmware character rules.
- `core/model` `H3Cell` checks a cell index and its hex form in pure Kotlin. `core/publicfeed`
  `H3Grid.gridDisk` lists a cell and its rings on the platform binding.
- `core/sync` `ThingSetupPolicy.needsSetup` is the one rule for a Thing that still needs setup.
  `ThingSetupWatch` applies it to the foreground scan and to each page 1 read, and My devices opens
  the setup wizard from it. It reads only the cell, never a fix. See
  [sync-engine.md](sync-engine.md#thing-setup).
- `core/sync` reuses the existing serialized request and config-chunk parser.
- `ui/map` `CellPicker` draws the cells, takes the tap and emits only the cell id. `CellPickerGrid`
  holds its grid maths apart from Compose. `PlaceSearchField` searches the bundled GeoNames
  cities15000 list (`PlaceSearch`), written by `tools/places/make_places.py`. `LocalMapNetwork`
  carries the Settings network switch from each app root.
- `feature/device` owns shared draft/readback behavior and permission-triggered UI.
  `DeviceLocationForm` is the card of Device settings and of the setup Location page, and opens the
  picker full screen. `DeviceSiteForm` is the site part of that card and the setup Site page.
  `DeviceConfigController` keeps the site draft (`DeviceSiteState`) beside the location draft.
- Its Android and iOS source sets own the temporary OS fix, cancellation and H3
  conversion. Android repository and iOS bridge retain their existing command guards.
- `DeviceCapabilities` reads LIVE through the thing of the `info.dict` digest, from the
  bundled board templates in `core/model/bundled`. Only the current digest of each board is
  bundled. An older or unknown digest still syncs verified files but has no LIVE view.

Cross-platform host and simulator gates verify parsing, old firmware behavior,
coarsening, late-fix suppression and readback decisions. A phone run beside a
real thing is still required to qualify permission timing, OS fix quality and
radio behavior. No phone evidence is claimed by these host checks.
