# Store compliance checklist (Google Play and App Store)

**Scope:** what the store forms ask and how this app answers today. Permission timing, purpose
strings, privacy manifest, Data safety and privacy label answers, and the open items that need an
owner decision before the first store submission.
**Code:** `app/src/main/AndroidManifest.xml`, `ui/permissions/`, `ios/App/Info.plist`,
`ios/App/PrivacyInfo.xcprivacy`. **Related:** [screens.md](screens.md) (permission timing table),
[devices-and-discovery.md](devices-and-discovery.md) (network boundary, public feed, uploads).

State as of 2026-10-08. Re-check the linked store pages before each submission. They change yearly.

## Current data posture

- Bluetooth LE and the local Wi-Fi (mDNS `_aqsync._tcp`) reach only the user's own sensors.
- Readings and Parquet files stay on the phone. They leave only when the user shares or exports them
  through the system share sheet or document picker.
- No account, no analytics, no crash reporting SDK, no ads. No location permission today. The
  planned Discover "Near me" asks for approximate location only on tap (see below).
- No server today. The planned Discover feed (anonymous read-only GETs) and per-device opt-in
  uploads change the answers below. See "Before Discover or uploads ship".

## Google Play

| Item | Answer today | Evidence |
| --- | --- | --- |
| Target API | Targets 37. Play requires API 36 for new apps and updates from 2026-08-31 ([target SDK](https://developer.android.com/google/play/requirements/target-sdk)) | `app/build.gradle.kts` |
| Runtime permissions | Asked in context from the action that needs them, never at launch. In-app rationale only when `shouldShowRequestPermissionRationale` is true, settings link after a permanent denial | `ui/permissions/RuntimeAccess.kt`, `PermissionFlowTest` |
| Location | None declared. `BLUETOOTH_SCAN` carries `neverForLocation`. No background location | `ManifestPermissionsTest` |
| Prominent disclosure | Not required. It applies when an app collects personal or sensitive data in a way users would not expect. Nothing leaves the phone without a user share | Play [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311) |
| Data safety form | "No data collected" and "No data shared". Data processed only on the device, and data the user sends with the share sheet, are not collection | Play [Data safety help](https://support.google.com/googleplay/android-developer/answer/10787469) |
| Encryption in transit | Answer "No" for data in transit, since no user data is sent to a developer server. BLE links are encrypted by pairing. The LAN stream is not encrypted and stays on the local network | `docs/transports.md` |
| Data deletion | Not applicable, no account. Uninstall or Forget removes local data | |
| Account deletion | Not applicable, no account | |
| Foreground service | `dataSync` for a user-started or Auto-sync file transfer. Fill the Play Console foreground service declaration with this use and a short video of a sync with its notification. Android 15+ limits `dataSync` to 6 hours a day, far above one transfer | Manifest comment, `SyncForegroundService` |
| `POST_NOTIFICATIONS` | Asked when the user starts a sync or turns on Auto-sync or wake when nearby. A denial only hides the progress notification | `TodayRoute`, `FilesScreen`, `DeviceRoute`, `ScanScreen` |
| `ACCESS_LOCAL_NETWORK` (API 37) | Asked only from a Wi-Fi sync action on the Device screen. Bluetooth works without it | `DeviceRoute` |
| Companion device permissions | Normal permissions, effective only while an associated sensor is near, after the user approves the system dialog | Manifest comment |
| `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK` | Re-arm the Wi-Fi trigger after boot. Wi-Fi lock only during a LAN transfer | Manifest comment |
| Camera | Not declared. Barcode scan runs in Google Play services' own UI | Manifest `meta-data` |

## App Store

| Item | Answer today | Evidence |
| --- | --- | --- |
| Purpose strings | `NSBluetoothAlwaysUsageDescription` "Finds and connects to your opensensor devices nearby to copy their readings." `NSLocalNetworkUsageDescription` "Finds your opensensor devices on your Wi-Fi to sync faster." `NSBonjourServices` `_aqsync._tcp` only | `ios/App/Info.plist` |
| Prompt timing | `CBCentralManager` is created on the first Find sensors tap. `NWBrowser` starts on the first Find Wi-Fi sensors tap. Launch shows no prompt | `BluetoothCompanion.swift`, `LanDiscovery.swift` |
| Privacy manifest | `PrivacyInfo.xcprivacy` in the app bundle. Tracking false, no tracking domains, no collected data types. Required reason APIs, file timestamp `C617.1` (`stat`, `fstat`, `attributesOfItem` on files in the app container) and system boot time `35F9.1` (`ProcessInfo.systemUptime` for elapsed transfer timing). No UserDefaults, disk space or keyboard APIs found in the binary | [Apple required reason APIs](https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api) |
| Privacy nutrition label | "Data Not Collected" | [App privacy details](https://developer.apple.com/app-store/app-privacy-details/) |
| Export compliance | `ITSAppUsesNonExemptEncryption` false. Only OS encryption (BLE pairing, Keychain, file protection) | `Info.plist` |
| Background modes | None declared | `Info.plist` |
| Account deletion (Guideline 5.1.1(v)) | Not applicable, no account | |
| Tracking (ATT) | No tracking, no IDFA | |

## Before Discover or uploads ship (owner decisions)

Owner decisions of 2026-10-08 are recorded in
[devices-and-discovery.md](devices-and-discovery.md#owner-decisions-and-open-questions).

1. **Feed requests reveal an IP address.** Anonymous GETs to the public feed and basemap still send
   the phone's IP address to the host. The host is source.coop. Confirm with Radiant Earth in
   writing what source.coop and AWS log for public reads and that none of it reaches us. If logs are kept, Play Data
   safety and the Apple label likely need "approximate location" or "other identifiers" with
   purpose "App functionality", not linked to identity. If the host keeps no logs, record that.
   Add the feed and basemap domains to the privacy manifest only if they track, which they must not.
2. **Upload cell resolution versus "approximate location".** Uploads carry an H3 cell. Play
   treats location finer than about 3 km² as precise (verify against the current Data safety
   help before relying on this figure). H3 resolution 8 is about 0.7 km², resolution 7 about
   5 km². Decided: published and stored cells are capped at resolution 7, so they stay
   approximate on both stores.
3. **Uploads make the app collect data.** With any upload on, Data safety needs collected and
   shared data types (sensor readings under "App activity" or "Other", plus location as above),
   encryption in transit "Yes" (HTTPS), and a clear statement that published data is CC BY 4.0
   and permanent, that "Stop sharing" applies from the next UTC day, and a contact for legal
   removal requests. There is no account, so account deletion does not apply. The
   Apple label changes from "Data Not Collected" to listed types, not linked to the user, no
   tracking.
4. **Export compliance with HTTPS uploads.** Standard HTTPS through `URLSession` stays exempt, so
   `ITSAppUsesNonExemptEncryption` can stay false. Re-check if any custom crypto is added.
5. **"Near me".** Android declares only `ACCESS_COARSE_LOCATION`, asked on the tap. iOS sets
   `NSLocationDefaultAccuracyReduced` with a When In Use purpose string. The fix only moves the
   map and is not collected. Before building it, check the Android system location button rule
   against the target SDK.
6. **Privacy policy URL.** Both stores require one. Today it can state "no data collected". Update
   it in the same release that adds the feed or uploads.

## Gate before each submission

- [ ] `ManifestPermissionsTest` and `PermissionFlowTest` pass, the manifest set is unchanged or reviewed.
- [ ] Fresh install on a phone shows no permission prompt at launch (Android and iPhone), recorded in `docs/status.md`.
- [ ] Xcode Organizer privacy report of the archive matches `PrivacyInfo.xcprivacy`.
- [ ] Data safety form and App privacy answers match this page.
