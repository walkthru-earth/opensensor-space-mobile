# Status, what is verified and where

**Scope:** what is verified today, on which platform, at which commit, and which phone runs are still open. Host tests, the iOS simulator, the Android phone and the iPhone are kept apart.
**Code:** `./gradlew verify` (the gate), `.github/workflows/verify.yml`.
**Related:** measured board behaviour is in `../opensensor-space-firmware/docs/boards/*/bench-verified.md`. Anything not recorded here or there is unverified.

## Evidence

Each cell gives the date and commit of the newest recorded evidence for that feature, or "not run".
The gate runs every host and simulator test on each commit. A host or simulator cell names the
newest change to that feature that passed them. A phone cell needs a run of that feature on that
phone. An install and launch alone counts only for the install row. When the record names no
build commit, the cell gives the commit that recorded the run, marked "rec.".

| Feature | Host tests | iOS simulator | OnePlus 7 Pro | iPhone 16 Pro |
| --- | --- | --- | --- | --- |
| BLE sync | 2026-10-29 `4af870c4` | 2026-10-29 `4af870c4` | 2026-10-09 `5d5db510` | not run, see note 1 |
| LAN sync | 2026-10-29 `4af870c4` | 2026-10-29 `4af870c4` | 2026-10-10 `bed79491` | 2026-10-09 `d763fdb6`, owner report |
| Sensor clock | 2026-10-29 `4af870c4` | 2026-10-29 `4af870c4` | 2026-10-09 `5d5db510` | not run |
| Auto-sync | 2026-10-18 `958148ea` | 2026-10-18 `958148ea` | 2026-10-10 `bed79491`, one-time background run only | not run |
| Pairing and stale bond | 2026-10-11 rec. `91919319` | 2026-10-11 rec. `91919319` | 2026-09-29 `06ab6ccd` | not run, see note 1 |
| Station page and live readings | 2026-10-27 `39388eab` | 2026-10-27 `39388eab` | 2026-10-27 rec. `b58cc77b` | 2026-10-09 `d763fdb6`, owner report |
| History | 2026-10-27 `39388eab` | 2026-10-27 `39388eab` | 2026-09-30 `dba3e693` | not run |
| Files | 2026-10-18 `958148ea` | 2026-10-18 `958148ea` | 2026-10-10 `bed79491` | not run |
| Export | 2026-10-18 `958148ea` | 2026-10-18 `958148ea` | not run | not run |
| Discover | 2026-10-18 `958148ea` | 2026-10-18 `958148ea` | not run | not run |
| Setup wizard | 2026-11-09 `9503132c` | 2026-11-09 `9503132c` | not run | not run |
| Location (res 8 cell) | 2026-11-09 `9503132c` | 2026-11-09 `9503132c` | not run | not run |
| Thing publish (File Item, Collection) | 2026-11-10 `430c12f4` | 2026-11-10 `430c12f4` | not run | not run |
| No permission prompt at launch | not applicable | not run | 2026-10-08 rec. `15c7cd1a` | not run |
| Install and launch | not applicable | 2026-10-11 rec. `80c338f1` | 2026-10-27 rec. `b58cc77b` | 2026-10-10 `bed79491` |

### What the phone cells cover

1. iPhone and iPad over Bluetooth. On 2026-10-08 at `e1a3c6de` the owner saw live readings over
   Bluetooth on the iPhone, with no file sync. On 2026-10-11 at `5604fb3e` Smart Sync over
   Bluetooth failed on the iPad Air M2 (iPadOS 27.0.1). The CoreS3 ended each link with a failed
   encryption and HCI 0x13 because a reflash had reset its bonds while the iPad kept the old key.
   Forgetting the sensor in iPadOS Settings and pairing again is the workaround.
2. OnePlus BLE sync and clock, 2026-10-09. Against CoreS3 firmware `01e6468`, the phone connected
   over Bluetooth, set the sensor clock with no manual action, and Smart Sync downloaded and checked
   the first fresh file. It then moved to Wi-Fi LAN and completed the six file manifest. The board
   had a working RTC, so no-RTC recovery and power cuts are not covered.
3. OnePlus LAN sync and Files, 2026-10-10. After CoreS3 firmware v6.11, automatic LAN sync copied
   three files. "Sync now in background" then copied one 90 row file over LAN, and its CRC-32
   matched the device. Files showed 38/38 checked. This is a short check, not overnight endurance.
4. Auto-sync. No phone has completed a 15 minute periodic cycle. The OnePlus cell is the one-time
   background run in note 3.
5. OnePlus pairing, 2026-09-29. After a Waveshare reflash left a stale bond, the owner entered the
   PIN, Android reported the bond, and the app verified INFO and showed live readings.
6. OnePlus Station page, 2026-10-27. My devices showed the day lines of two boards. Today showed
   the peacock hero, Today so far and the air cards. Battery state, the generic hero and Forget
   were not checked. The run came before Today became the Station page hub (#117).
7. OnePlus History, 2026-09-30. Day and Week of a Waveshare across a station reset. This came
   before History read template datastreams, so the current History has no phone run.
8. iPhone LAN and live readings, 2026-10-09. The owner saw Wi-Fi LAN and live data, and reported
   that Wi-Fi Smart Sync works after the `TIME_SET` fix. No file count or rate was recorded.
9. OnePlus permissions, 2026-10-08. With Bluetooth and notification permissions revoked, launch
   opened on the sensor list with no permission dialog. Scan, Sync now and the deny paths were
   not checked.
10. Install rows. The OnePlus installed over its stored data on 2026-10-27. The iPhone installed
    and launched `bed79491` on 2026-10-10. The iPad installed and launched `5604fb3e` on
    2026-10-11. The simulator row is the unsigned Debug build that drew Arabic Discover labels.

## Open phone runs

Open issues that need a phone run.

- [#59](https://github.com/walkthru-earth/opensensor-space-mobile/issues/59), sync stability for
  proto 3. The preparation, resume and large LIST repairs have host tests in `CollectionControllerTest`
  and `LinkSessionTest` and no phone run.
- [#84](https://github.com/walkthru-earth/opensensor-space-mobile/issues/84), the iOS Bluetooth
  queue split on hardware.
- [#98](https://github.com/walkthru-earth/opensensor-space-mobile/issues/98), inspector memory and
  weak link READ windows on a 2 GB phone.
- [#266](https://github.com/walkthru-earth/opensensor-space-mobile/issues/266), a board sidecar and
  Collection from firmware #145 against the phone's rebuild.
- [#272](https://github.com/walkthru-earth/opensensor-space-mobile/issues/272), the Site page and
  card against a board with firmware #148 and one without, a refused link or deployment ID, Arabic
  text at the 64 byte cap, and the readback after Save.
- [#263](https://github.com/walkthru-earth/opensensor-space-mobile/issues/263), a moving Thing as a
  track of cells.
- [#264](https://github.com/walkthru-earth/opensensor-space-mobile/issues/264), the phone's res 8
  cell sent to a board.
- [#302](https://github.com/walkthru-earth/opensensor-space-mobile/issues/302), a user opened link
  kept when an iOS Auto-sync step is cancelled.
- [#228](https://github.com/walkthru-earth/opensensor-space-mobile/issues/228), the phone as a
  Thing, blocked.

Other checks owed, with no issue of their own.

- Sensor clock, token and Wi-Fi sync. The clock after a power cut, the token after a sensor reset,
  Wi-Fi sync switched back on below Developer, no Local Network prompt outside the Scan tap, and
  Wi-Fi browsing after an iOS update.
- Templates. LIVE, History and Today so far for each board on firmware at the bundled template
  commit, and a CrowPanel file in History and Files.
- Station page. Forget during Collect all, the battery state on both boards, and the iPhone day
  lines.
- History of each board after the template rebuild, and its default metric.
- The corrected PM2.5 default, the long read on a small MTU, and an export of selected records.
- Removal of old format files on both phones, then History, then a sync of a board that still
  lists them.
- Auto-sync. A 15 minute periodic cycle, overnight on the OnePlus, with the OEM battery settings it
  needs.
- Arabic map labels on an iPhone set to Arabic.
- The TalkBack and VoiceOver walk in English and Arabic from
  [cross-platform-guide.md](cross-platform-guide.md).

## Recent changes, host and simulator only

No phone run of these changes is recorded.

- 2026-11-17, `06a49bbe`. The bundled templates are byte copies of opensensor-space-firmware
  e2eb30b `docs/shared/templates`, which adds the CrowPanel e-paper template. CI checks out the
  firmware at that commit. CrowPanel has no reviewed app profile, so it renders with a profile
  derived from its template and has no LIVE view, hero or Today so far.
- 2026-11-10, `430c12f4`, part of #266. `ThingCollections.build` writes the Thing Collection in
  the canonical form of stac-opensensor v0.1.0. `PublishGate.boardFile` clears a board file only
  with the board's own sidecar after the phone's rebuild matches it. `PublishGate.phoneFile`
  publishes only this phone's own Thing and refuses every board file.
- 2026-11-09, `9503132c`, #272. The wizard runs Find, Name, Location, Site, Wi-Fi and Done.
  Location needs a confirmed res 8 cell from the area map. Site is optional and counts as saved
  only when pages 5 and 6 read back the same ([device-location.md](device-location.md#site)).
- 2026-11-04, `48360570`. The board kind publishes H3 resolution 8. The board writes the File Item
  and `FileItems` must rebuild the same Item. `FileItemSchemaTest` and `ProfileExampleTest` check
  it against the v0.1.0 profile. The JVM uses h3-java 4.5.1.
- 2026-10-29, `4af870c4`. The sensor clock, the Wi-Fi token and Sync over Wi-Fi run by themselves
  once per connection on both phones, through `SmartSyncPolicy`, `LanTokenPolicy` and
  `LanAutoEnable`. Their cards show only at Developer. `ios/tests/verify-native.sh` and a
  simulator build passed.
- 2026-10-27, `39388eab`. History reads a file by the template its board served before a bundled
  one. A dictionary with no reviewed profile has no LIVE view, no hero and no Today so far.
  `FirmwareTemplatesTest` fails when a local firmware checkout has other templates.
- 2026-10-26, `f92f2636`, #238. Today cards come from the template and the app profile. The
  profile hero picks the hero through `HeroRegistry`, and any other thing gets
  `GenericMetricHero`. Forget moved to the Today menu.
- 2026-10-26, `a60fba64`. History metrics come from the board template datastreams and the app
  profile. Rollups follow `opensensor:aggregate`.

## Test devices

- OnePlus 7 Pro (GM1911), Android 16, over USB adb. `run-as` is refused on this ROM, so inspect
  through logcat and the app UI.
- iPhone 16 Pro, iOS 26.6.2 at the last recorded run.
- iPad Air 13-inch M2, iPadOS 27.0.1 at the last recorded run.

## How to record a run

Update the cells the run covers with its date and commit. Add a short note above when the cell
needs one. Put board and firmware observations in the board's `bench-verified.md`. Entries before
2026-10-26 are in git history (`git log -- docs/status.md`).
