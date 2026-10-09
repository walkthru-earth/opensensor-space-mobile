# Store compliance checklist (Google Play and App Store)

**Scope:** what the store forms ask and how this app answers today. Permission timing, purpose
strings, privacy manifest, Data safety and privacy label answers, and the conservative decisions taken for
Discover and uploads before the first store submission.
**Code:** `app/src/main/AndroidManifest.xml`, `ui/permissions/`, `ios/App/Info.plist`,
`ios/App/PrivacyInfo.xcprivacy`. **Related:** [screens.md](screens.md) (permission timing table),
[devices-and-discovery.md](devices-and-discovery.md) (network boundary, public feed, uploads).

State as of 2026-10-08. Re-check the linked store pages before each submission. They change yearly.

## Current data posture

- Bluetooth LE and the local Wi-Fi (mDNS `_aqsync._tcp`) reach only the user's own sensors.
- Readings and Parquet files stay on the phone. They leave only when the user shares or exports them
  through the system share sheet or document picker.
- No account, no analytics, no crash reporting SDK, no ads. The only location permission is
  approximate, asked by the Discover "Near me" button on that tap. The fix moves the map and draws
  a puck on the phone, and is never stored or sent (AGENTS.md).
- No server today. The planned Discover feed (anonymous read-only GETs) and per-device opt-in
  uploads change the answers below. See "Before Discover or uploads ship".

## Google Play

| Item | Answer today | Evidence |
| --- | --- | --- |
| Target API | Targets 37. Play requires API 36 for new apps and updates from 2026-08-31 ([target SDK](https://developer.android.com/google/play/requirements/target-sdk)) | `app/build.gradle.kts` |
| Runtime permissions | Asked in context from the action that needs them, never at launch (`PermissionTriggers`). In-app rationale only when `shouldShowRequestPermissionRationale` is true. Settings link on Android after a denial that an earlier rationale or request proves permanent (a dismissed first prompt stays quiet) and after a reported denial on iOS, for Bluetooth, Local Network and Near me location | `:ui:permissions` (`PermissionFlow`, `PermissionTriggers`, `PermissionFlowTest`), `app/.../ui/permissions/RuntimeAccess.kt`, `ios/App/BluetoothCompanion.swift`, `ios/App/LanPolicy.swift` |
| Location | Only `ACCESS_COARSE_LOCATION`, asked on the Near me tap, foreground only. `BLUETOOTH_SCAN` carries `neverForLocation`. No fine or background location, so no Play location declaration. Data safety stays "not collected" because the fix never leaves the phone | `ManifestPermissionsTest`, `feature/discover/.../NearMe*.kt` |
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
| Languages | Store listing in English and Arabic. The app offers both in the per-app language picker (`android:localeConfig`). Arabic store text needs the same native review as the app copy (`parity-decisions.md` P22) | `res/xml/locales_config.xml`, `res/values-ar/` |

## App Store

| Item | Answer today | Evidence |
| --- | --- | --- |
| Purpose strings | `NSBluetoothAlwaysUsageDescription` "Finds and connects to your opensensor devices nearby to copy their readings." `NSLocalNetworkUsageDescription` "Finds your opensensor devices on your Wi-Fi to sync faster." `NSBonjourServices` `_aqsync._tcp` only. `NSLocationWhenInUseUsageDescription` "Centres the public sensor map near you. Your location stays on this phone." with `NSLocationDefaultAccuracyReduced` true, asked only from the Discover Near me tap | `ios/App/Info.plist` |
| Purpose strings, Arabic | The same three purpose strings in Arabic, in the `InfoPlist.xcstrings` string catalog, which Xcode compiles to `ar.lproj/InfoPlist.strings`. English stays identical to `Info.plist` (`TranslationsTest`) | `ios/App/InfoPlist.xcstrings` |
| Languages | App Store localizations English and Arabic. `CFBundleLocalizations` lists en and ar, `knownRegions` adds ar, so iOS offers Arabic in the app's Language setting | `ios/App/Info.plist`, `project.pbxproj` |
| Prompt timing | `CBCentralManager` is created on the first Find sensors tap. `NWBrowser` starts on the first Find Wi-Fi sensors tap. Launch shows no prompt | `BluetoothCompanion.swift`, `LanDiscovery.swift` |
| Privacy manifest | `PrivacyInfo.xcprivacy` in the app bundle. Tracking false, no tracking domains, no collected data types. Required reason APIs, file timestamp `C617.1` (`stat`, `fstat`, `attributesOfItem` on files in the app container) system boot time `35F9.1` (`ProcessInfo.systemUptime` for elapsed transfer timing) and UserDefaults `CA92.1` (`NSUserDefaults` holds the Discover map camera, chip choices and app settings, read only by this app). No disk space or keyboard APIs found in the binary | [Apple required reason APIs](https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api) |
| Privacy nutrition label | "Data Not Collected" | [App privacy details](https://developer.apple.com/app-store/app-privacy-details/) |
| Export compliance | `ITSAppUsesNonExemptEncryption` false. Only OS encryption (BLE pairing, Keychain, file protection) | `Info.plist` |
| Background modes | None declared | `Info.plist` |
| Account deletion (Guideline 5.1.1(v)) | Not applicable, no account | |
| Tracking (ATT) | No tracking, no IDFA | |

## Decisions for Discover and uploads (conservative defaults, 2026-10-08)

Owner decisions of 2026-10-08 are recorded in
[devices-and-discovery.md](devices-and-discovery.md#owner-decisions-and-open-questions). Where a
question was still open, the safest choice is taken. These are product rules, not legal advice.
Have counsel read the privacy policy before the first public release.

1. **Published cells are capped at H3 resolution 7** (about 5 km²). Play's Data safety guidance
   treats location finer than about 3 km² as precise. Resolution 8 (about 0.7 km²) could count as
   precise location, resolution 7 stays approximate on both stores. The owner may pick a coarser
   cell, never a finer one. `devices-and-discovery.md` applies the same cap.
2. **Feed hosting is source.coop** (owner decision, Radiant Earth partnership on AWS Open Data).
   Requests carry no cookies, no query identifiers and load no third-party scripts. We receive no
   access logs. Confirm in writing with Radiant Earth what source.coop and AWS log for public
   reads and that none of it reaches us. Host the basemap the same way. If logs that reach us
   cannot be avoided, declare them. Play Data safety "Device or other IDs" and Apple "Device ID", both for
   App functionality, not linked to the user, no tracking. Never add a tracking domain to the
   privacy manifest.
3. **Uploads stay opt-in per device, off by default**, with one confirmation that lists what is
   shared (readings, the resolution 7 cell, the public name) and what never is (exact location,
   station UUID, MAC address, Wi-Fi, PIN, token), and states that published data is released
   under CC BY 4.0 and cannot be withdrawn (owner decision). "Stop sharing" lives in that
   device's settings and stops publication from the next UTC day. Already published data stays
   public. The privacy policy keeps a contact for legal removal requests, since a device's series
   may still be personal data under laws such as the GDPR. Update both store forms in the same
   release.
   - Play Data safety. Collected and shared, "Approximate location" and "Other app activity or
     Other" (sensor readings), purpose App functionality, optional, encrypted in transit (HTTPS).
     Answer the deletion question as stated above, with the legal request contact.
   - App Store label. "Coarse Location" and "Other Data", not linked to the user, not used for
     tracking.
4. **Export compliance.** HTTPS through the platform stacks only. No custom cryptography.
   `ITSAppUsesNonExemptEncryption` stays false.
5. **Privacy policy.** [privacy-policy.md](privacy-policy.md) is the draft to host at a public URL
   and link in Play Console, App Store Connect and the app's Settings About line. The owner
   commits to it at publish time, not before. It describes
   today's behaviour and already covers the opt-in sharing rules above.
6. **Children.** Target audience 13 and over in Play's target audience form, and no claims of
   being made for children, so the Families policy and COPPA rules for child-directed apps do not
   apply.
7. **Play foreground service declaration.** Type `dataSync`. Description to paste: "Copies air
   quality data files from the user's own Bluetooth or Wi-Fi sensor to the phone when the user
   starts a sync, or on the schedule the user turned on per device. The transfer must finish
   while the screen is off. A progress notification with a Stop action is shown." The screen
   recording of Sync now with its notification is made at publish time.
8. **"Near me"** (owner decision). Android declares only `ACCESS_COARSE_LOCATION`, asked on the
   tap, never in the background. iOS uses When In Use with `NSLocationDefaultAccuracyReduced`.
   The fix only moves the map and may preselect the sharing cell, it is never stored or sent, so
   it is not collected. Built 2026-10-09. `ManifestPermissionsTest` expects `ACCESS_COARSE_LOCATION`
   and rejects fine and background location. The Android 17 location button (Jetpack
   `androidx.core.locationbutton`) is optional, not a targetSdk 37 rule, and it grants one time
   precise location, so the app keeps the standard coarse runtime prompt. The iOS privacy
   manifest needs no change, since the fix is not collected and location is not a required
   reason API.
9. **Upload identity.** No accounts or passkeys. A per-device P-256 key in the Android Keystore
   or iOS Keychain signs uploads. Platform signing for authentication keeps the export exemption.

## Gate before each submission

- [ ] `ManifestPermissionsTest` and `PermissionFlowTest` (`:ui:permissions`, Android host and iOS simulator) pass, the manifest set is unchanged or reviewed.
- [ ] Fresh install on a phone shows no permission prompt at launch (Android and iPhone), recorded in `docs/status.md`.
- [ ] Xcode Organizer privacy report of the archive matches `PrivacyInfo.xcprivacy`.
- [ ] Data safety form and App privacy answers match this page.
- [ ] At publish time, the privacy policy URL is live and matches what the release does.
- [ ] At publish time, the Play foreground service declaration has its video.
