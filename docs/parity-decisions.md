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
