# Parity decisions for owner review

**Scope:** every choice made without the owner during the overnight parity work of 2026-10-09,
so each one can be confirmed or reversed. **Code:** see the linked issue or commit.
**Related:** [platform-parity-plan.md](platform-parity-plan.md), tracking issue #6.

Each row says what was decided, why, and what reversing it would cost. Rows marked
**Confirm** change user-visible behavior.

| # | Decision | Why | Reverse cost | Where |
| --- | --- | --- | --- | --- |
| P1 | iOS Wi-Fi sync keeps the session open when some files fail and says "N of M files were not saved" | Same as Android, the next tap retries only the failed files | Small, one branch in `LanDiscovery.swift` | 329d6f5 |
| P2 | Missing `max_read` in HELLO means 4096 on both apps, but iOS still caps any value at 16384 | Conservative default, the cap protects the iOS frame buffer | Small | 500c162 |
| P3 | PM values from 100 up are rounded instead of cut off on Android | One shared `formatPm` for both apps | Small, `core/units` | 6134c66 |
| P4 | **Confirm** WHO step wording "Within the WHO guideline", "Within WHO interim target 4" to "1", "Above every WHO target" on both apps | One wording, matches WHO 2021 names | Strings only | 6c15a4a |
| P5 | **Confirm** privacy copy now says approximate location is used only on Near me | It was wrong after Discover shipped | Strings only | 6c15a4a |
| P6 | Android cloud backup now includes the settings and discover DataStore files, not the runtime throttle preferences | Settings were lost on restore | Small, backup rules | 6c15a4a |
| P7 | New shared modules use packages `earth.walkthru.aqsync.sync`, `.archive`, `.settings` | Matches existing `core/*` packages | Rename only | 5ee93c1 |
| P8 | `verify` runs `verifyIos` on a Mac, `-Popensensor.skipIos=true` turns it off | No module is missed by the gate | Small | 214ab32 |
| P9 | **Confirm** iOS sticker scan uses VisionKit `DataScannerViewController` with a camera permission asked on the Scan tap. Manual entry always works | Same feature as Android, Apple's own scanner, no third-party SDK | Drop the scanner, keep manual entry only | inventory A1, F52 |
| P10 | **Confirm** iOS declares the `processing` and `bluetooth-central` background modes, only used when a device's Auto-sync is on. No `fetch` mode | Needed for unattended collection like Android. App Review note required | Remove the modes, iOS syncs only while open | inventory A2, F54, F68 |
| P11 | "Sync in background" on iOS uses `BGContinuedProcessingTask` on iOS 26 and later. Older iOS keeps today's short lease with a "keep the app open" hint. Deployment target stays 18.5 | The system shows progress and Cancel for free | Small | inventory A3, F48, F66 |
| P12 | Wi-Fi arrival trigger, boot re-arm, Wi-Fi lock and Companion Device Manager exemptions stay Android only | iOS has no public equivalent and does not need them | none | inventory A4, F70 to F73 |
| P13 | iOS does not expose the archive in the Files app. Export goes through Share and Save | A writable Documents folder would let users rename or delete archive files | Add `UIFileSharingEnabled` on a separate copy | inventory A5, F81 |
| P14 | No widget, Live Activity, App Intents, shortcuts or deep links on either app for now | Android has none, iOS 26 shows sync progress through the system | New feature work | inventory A6 |
| P15 | **Confirm** AccessorySetupKit is not adopted now. Forget on iOS removes the app record and Keychain token and tells the user how to remove the bond in Settings | Adopting it changes pairing UX and Info.plist discovery keys | Medium, pairing flow rework | inventory A7, F17, F24 |
| P16 | iOS asks for notifications only from the Auto-sync switch, never from a foreground Sync | Foreground syncs need no notification on iOS | Small | inventory A8, F77 |
| P17 | Background Bluetooth on iOS connects only to bonded known peripherals, never prompts pairing | Same as Android's unattended rule | none | inventory A9, F86 |
| P18 | Upload opt-in stays out of parity scope until its design is built | Not on Android either | none | inventory A10, F60 |
| P19 | iOS Developer level exposes the same raw command screen with REBOOT and SET_CONFIG denied | Same tools on both apps, one shared denylist | Hide Debug on iOS | inventory A11, F61 |
| P20 | Fake Bluetooth and Wi-Fi devices ship only in debug builds on both apps | Testing without hardware, never in release | none | inventory A12, F21 |
| P21 | Shared navigation uses JetBrains Navigation 3 UI 1.1.2 with the existing nav3 runtime 1.1.7 in commonMain. A small own host stays as the iOS fallback | Paired with CMP 1.12.1, same Google binaries on Android, iOS edge swipe through Navigation Event | Medium, swap the iOS host only | `docs/adr/0001-shared-navigation.md`, #12 |
