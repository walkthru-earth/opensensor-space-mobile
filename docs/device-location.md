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
A moving Thing keeps its last `location.gnss_cell` there with `moving` set, or an empty cell
before its first fix, and never its configured cell. Forget deletes the cell of every Thing the
sensor named.
Android retains only ACCESS_COARSE_LOCATION. iOS retains When In Use and reduced
accuracy by default. A small chosen cell cannot improve an approximate OS fix.

Every Thing, fixed or moving, uses H3 resolution 8, about 0.74 km² with an edge of about
530 m. Actual cell area varies. There is no resolution menu. The card shows whether a cell is
set, and Detailed adds the cell index. The picker shows the area size (`LocationArea`), and
Detailed adds the resolution number there.
`ConfigFields` accepts only `location.resolution=8`, and `ConfigPatch.Builder.location(cell, country)`
takes only an empty or resolution 8 cell and always sends 8. Firmware refuses any other resolution
(firmware #139). A finer cell read from older firmware becomes its resolution 8 parent. A coarser
one cannot be refined, so the app asks for a new pick before Save. Firmware applies the maximum again
before saving and derives the published center from the retained cell. Country
is declared by the owner, not inferred from the cell or used as NTP residency
proof. Exact GPS, cell-center coordinates and administrative country are different
things. See the firmware-owned [time/location contract](../../opensensor-space-firmware/docs/shared/time-and-location.md).

## Mobility and GNSS

When page 1 reports `thing.mobility`, the location card of Device settings and of the setup
Location page shows a Fixed or Moving choice with an info icon. Firmware that does not report the
key never sees the choice, because it refuses a request with an unknown key. A tap sends
`thing.mobility` alone through the existing `SET_CONFIG` path (`DeviceConfigAction.SetMobility`),
and the card says saved only when page 1 reads back the same value. Nothing else sends it.

- Fixed is the default. The Thing stands in its chosen cell and every row writes source 1.
- Moving hides the cell and Choose area. Each row takes the cell of the board's own GNSS fix, or
  no location at all. The phone never supplies a cell for a moving Thing (`location.phone_cell`,
  #264, is not used). A board with no receiver (page 1 omits `location.gnss_cell`) says so on
  the card, because its rows carry no location.
- Setup may finish once the board stores a cell or reports Moving
  (`DeviceLocationState.located`). There is no Skip.
- On a board with a receiver and a fresh fix, the picker offers Use sensor GPS, and with no
  drafted cell it opens on the `location.gnss_cell`. The tap only drafts that cell and centres it, and Save stays the owner's
  explicit step.
- When the draft replaces a stored cell, the picker notes that new rows use the new cell, earlier
  files keep the old one, and a new file starts. A change of mobility splits the file too.

Discover shows a moving own Thing from its rows, never from a guess (#263). `CellTrackReader`
(`core/history`) reads the last 24 hours of the Thing's verified files on the phone, one
`h3_cell_id` and `location_source` per row, into a `CellTrack`. A row with a null cell, a null or 0
source, or a value that is no H3 cell is a row with no fix and never borrows a neighbour's cell.
Only GNSS (2) and phone (3) fixes place a moving Thing. Rows from its fixed hours carry the
configured cell (1), which is never where it is now. Those fixes are outlined with an ink fill and
line in their own map source with no Thing UUID, and the pin stands at the newer of the stored GNSS
cell and the last such fix. With neither it shows no pin. The 24 hour window is read again when a
file is synced and every quarter hour, so old cells leave the map with no new file.

## Site

The owner site describes where and by whom a Thing is deployed (firmware #148, mobile #272). It
holds five optional values. Device settings show them under the area as one "Location and site"
card, and the setup wizard asks for them on its Site page after Location. The info text says
plainly that these values are public when the owner shares the sensor's data, and asks a home
owner not to type a home address.

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
  without `owner` is a failed read, not an empty description. A read that fails, such as one
  that meets the page 0 read of a Refresh still holding the link, is tried three times in all,
  each new try after the link is free. The area read (page 1) does the same. If the site read
  still fails, the card and the setup Site page say so and offer Try again, which reads pages 5
  and 6 only. Skip stays on in the wizard.
- Write. Only the Save site button in Device settings, or Next on the setup Site page, sends.
  Typing sends nothing. Save sends only the values that differ from the board's last answer.
  An emptied value goes as `key=` and clears it, a value that stays empty is not sent. The site
  keys go in a `SET_CONFIG` of their own, and `ConfigPatch.Builder.build` refuses a patch that
  mixes them with any other key, so a site refusal can never cost a location or name write.
  Pages 5 and 6 are read back after the write, and the app says saved only when they match.
- A refused or unconfirmed write says so under the fields, and the draft stays for another Save
  or Next. After a failed site write, the next site save that succeeds reads pages 0 and 1 again
  too. In the wizard Next moves on only when the save is confirmed, and Skip is off while it runs
  (`SetupController.saveSiteThenNext`).
- The board never logs owner values, and only `deployment_id` reaches the Parquet files, so a
  change splits the current file. Owner values are not secrets. The Debug frame list redacts
  every `SET_CONFIG`, and a CONFIG reply shows them as the board sent them.

## Code boundaries

- `core/protocol` owns the typed page model, page selector and cell-only patch.
  `location.gnss_cell` is the board's own GPS cell offer, sent from Waveshare `v2.18` (firmware #147).
  `LocationConfig.gnssCell` keeps it only when it is a resolution 8 cell. It is null both when the key
  is absent (no receiver) and when it is empty (no fresh fix). `gnssCellText` keeps that difference.
  `thing.mobility` (`fixed` or `moving`) has a `ConfigFields` row and
  `ConfigPatch.Builder.thingMobility`, sent only by the Fixed or Moving tap. `thing.setup` reads as
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
- `DeviceCapabilities` reads LIVE through the thing of the `info.desc` and `info.dict` digests,
  by `TemplateIndex.resolve`, from the templates boards served and then the bundled ones in
  `core/model/bundled`. Only the current digest of each board is bundled. An older or unknown
  digest still syncs verified files but has no LIVE view until its board serves the template.

Cross-platform host and simulator gates verify parsing, old firmware behavior,
coarsening, late-fix suppression and readback decisions. A phone run beside a
real thing is still required to qualify permission timing, OS fix quality and
radio behavior. No phone evidence is claimed by these host checks.
