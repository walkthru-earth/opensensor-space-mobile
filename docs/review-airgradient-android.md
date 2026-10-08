# Review: `worxbend/airgradient-android` — what is worth reconsidering for AQ Sync

Date: 2026-09-17. Reviewed at commit `f25ec04` (clone in `/tmp/aq-explore/airgradient-android`, read-only).
Four parallel reviews (sensor domain · notifications/monitoring · history/viz · hygiene) plus spot checks
in this repo. Nothing was changed in either repo; this file is the decision record.

## What that app is

A Kotlin/Compose client for the **AirGradient local HTTP server** (`GET <device>/measures/current`,
Retrofit polling every 30 s–5 min). Single `:app`, manual DI, DataStore only (a 7-day/90-day store of
hourly `min/avg/max/count` aggregates as one JSON file), alert notifications with a pure decision engine,
an always-on `START_STICKY` foreground polling service *or* a WorkManager periodic check, three tabs
(Dashboard · Trends · Today) and a Settings screen. The repo was written by an autonomous agent loop
(`AGENT_LOG.md`, `PLAN.md` 4,000 lines) and has never been field-verified beyond CI; weigh its ideas on
merit, not as proven practice. Its transport layer (HTTP polling) has nothing in common with our
BLE/LAN Parquet file sync, so everything below is domain, UX or process.

## Findings about *our* code that the comparison surfaced

Status 2026-10: items 1 and 2 are superseded. The edges now live once in `:core:airquality`
(`WhoGuidelines`, WHO 2021 steps 15 · 25 · 37.5 · 50 · 75 for PM2.5, inclusive from below),
`MetricSpec.healthScale` replaced `Band`, and every colour goes through `WhoStep.color()`.

1. **PM2.5 band edges exist twice with different boundary semantics (bug).**
   `ui/theme/Theme.kt` `pm25Band()` hard-codes 15/35/55/150 with `<=`; `AirMood.of` (`:core:history`)
   uses `pm25 < upTo`. PM values are integers in our files, so a reading of exactly 15, 35, 55 or 150
   is titled one mood on the Now card and coloured the neighbouring band on the Detailed readings card
   (`ui/device/TodayScreen.kt:278`). Fix: delete the table, `pm25Band(v) = v?.let { AirMood.of(it).color() }`,
   and add a boundary test to `AirMoodTest`. Their discipline of deriving every colour and scale from *one*
   threshold table is what exposed this.
2. **`Band(upTo, mood: AirMood)` couples every future metric to PM2.5 copy.** The first non-PM banded
   metric (CO₂) would inherit "Smoky"/"downwind of a barbecue". Introduce a metric-neutral
   `Level { GOOD, FAIR, ELEVATED, HIGH, VERY_HIGH }` in `Metrics.kt`, make `AirMood` PM2.5's copy table
   keyed by `Level`, and switch chart callers (`BandColumnChart`, `DayRing`, `CalendarHeatmap`, `NowCard`)
   to `levelOf(...).color()`. Do this before the next registry entry with bands.
3. **Cloud backup is probably already non-functional.** `data_extraction_rules.xml` includes `archive/`
   in `<cloud-backup>`, but Android Auto Backup is capped at **25 MB per app** and skips the *whole*
   backup above that ([Auto Backup docs](https://developer.android.com/identity/data/autobackup)); the
   full SD export is 32 MB. Drop `archive/` from `<cloud-backup>` (keep it in `<device-transfer>`, which has
   no quota) or document cloud backup as best-effort. Add `disableIfNoEncryptionCapabilities="true"` to
   `<cloud-backup>` so the Room DB (with `known_device.lanToken`) is only backed up when the device can
   encrypt it with the lock-screen secret. Keep the DB in backup otherwise: the token is LAN-scoped and
   rotatable, and restoring it is what keeps LAN sync working on a new phone (BLE bonds never transfer).
   `backup_rules.xml` (`fullBackupContent`) is dead on minSdk 31 — remove the duplicate.

## Verdicts

### Adopt (small, verifiable, no rule conflict)

| Idea | Their file | Ours today | Change in our repo |
| --- | --- | --- | --- |
| CI running the verify gate + debug APK artifact | `.github/workflows/android.yml` | no `.github/` | `.github/workflows/verify.yml`: setup-java 21 → `gradle/actions/setup-gradle@v4` → `./gradlew --no-daemon verify` → upload `app-debug.apk`; note in AGENTS.md what CI does *not* run (no emulator, no phone) |
| Persisted run diagnostics ("last check / last success / consecutive failures") | `domain/monitoring/MonitoringRuntimeState.kt`, `data/monitoring/` | nothing; README "Next" 2 wants period counts via logcat | `data/background/AutoSyncRuntimeStateStore.kt` (DataStore) written from `SyncWorker` start/`finally` out of the existing `AutoSyncReport`; shown on the Device screen's Auto-sync card ("last run 14:05 · 3 new files · 11 of 12 periods ran today"); excluded from backup. Answers "did the OEM kill the job" without ADB |
| Computed accessibility narratives for silent canvases | `today/TodayScreen.kt` `barsContentDescription`, `trends/TrendsHeatmapPresentation.kt` | `MoodFace` only; `BandColumnChart`, `DayRing`, `CalendarHeatmap` are silent | pure `describe(columns, metric, range, zone)` in `:core:history` (tested over fixtures) → `Modifier.semantics { contentDescription }`; same string is SwiftUI `.accessibilityLabel` |
| Own launcher identity, monochrome layer, splash icon, flat notification glyph | `res/drawable/ic_launcher_*.xml`, `values-v31/styles.xml` | Google's template robot icon, colour-only splash | own vector (capybara or quarter-hour ring), `<monochrome>` in `mipmap-anydpi-v26/ic_launcher.xml`, `windowSplashScreenAnimatedIcon` in `themes.xml` (minSdk 31), redraw `ic_stat_sync` |
| `@Preview` functions over stateless composables | `dashboard/DashboardScreenPreview.kt` | 0 previews | `ui/device/DevicePreviews.kt`, `ui/history/ChartPreviews.kt` + one `PreviewFixtures.kt`; lint already covers preview code |
| `.editorconfig` | `.editorconfig` | none | 8 lines, minus their ktlint keys |

### Adapt (the idea is right, the mechanism must change)

| Idea | Their version | Our version |
| --- | --- | --- |
| "Cached history" section with retention chips + Clear | `HistoryRetentionPolicy.kt`, Settings | **No retention knob** (archive = source of truth). Keep the *visibility*: read-only "On this phone — archive N files · X MB · derived Y MB · tiers Z rows" in Settings/History via `data/history/StorageStats.kt` |
| Week as 7 × 24 heatmap | `viz/HeatmapGrid.kt`, alpha = *severity* | Optional `ui/history/WeekGrid.kt` above the week columns, fed by `Columns.rebin(series, 3600)` from the 1 h tier; **colour = band, alpha = coverage, dashed = estimated, hollow = gap**, tap → day. Swift Charts `RectangleMark` |
| `AnimatedValueText` | `AnimatedContent` fade+slide 240 ms (not a count-up) | On the Now card's PM2.5 line and mood title; skip when the animator scale is 0 (they never check). iOS `.contentTransition(.numericText())` |
| One bottom bar with tab transition | `navigation/AppShell.kt` (Nav-Compose) | Hoist a single `NavigationBar` into `ui/Navigation.kt` (Nav3) with a 220 ms fade `transitionSpec` instead of a `DeviceBottomBar` per screen |
| Alert notifications from a pure decision engine | `NotificationDecisionEngine.kt`: 2 consecutive bad readings, 20-min per-key cooldown, escalation bypass, 2 h re-alert, recovery, stale, unreachable | Does **not** map: our data arrives once per 15-min period, 15–45 min old. The honest maximum, later and opt-in: after `deriveStale()` in `SyncWorker`, take the worst valid PM2.5 bucket in the files that just arrived and append one line to the **existing** summary ("Air reached Smoky at 14:20 — *was*, not *is*"); a pure `AwayAlertPolicy` in `:core:history` with `lastAlertedEpisodeStart` persisted beside the run diagnostics. Only after the diagnostics prove periods actually run |
| Midnight re-anchor | `core/time/ClockTicks.kt` `hourlyTicks` | History left open past midnight keeps yesterday's window; a small tick in `HistoryViewModel` |
| Three-role status colours (accent/container/onContainer), light + dark, `unknown` | `theme/StatusColors.kt` | `LevelColors` table in `ui/theme/Color.kt` via `staticCompositionLocalOf`; NowCard chips gain readable containers in dark mode; iOS copies the same 6 × 3 table |
| `PRIVACY.md` | `docs/PRIVACY.md` | Short page collecting the facts already in the manifest comments and README rows |
| "Test connection" action | `settings/components/DeviceUrlInput.kt` (sealed `ConnectionTestState`) | Later: a `LanTestState` facet + "Test LAN" (HELLO/AUTH only) on the LAN card to surface token rejection without a sync; needs a phone run |

### Skip (already covered, or worse than ours)

- **Thresholds.** Theirs (PM2.5 12/35/55 = EPA 2012, CO₂ 800/1200/2000, TVOC 65/220/660, NOx 20/50/150)
  trace only to two hobby reference projects, no primary source; TVOC/NOx grading ignores the index-vs-ppb
  unit it infers itself. Ours cite WHO 2021. When CO₂ lands, cite ASHRAE/REHVA ourselves.
- **AQI from a live snapshot** (`AqiCalculator.kt`): 2012 breakpoints applied to a 10-s sample. Confirms
  our "no AQI without validated averaging" rule.
- **Trend arrow from two consecutive snapshots** (`TrendCalculator.kt`, |Δ| < 0.05 → stable): flaps on
  10-s PMS samples. A defensible trend is "now vs today so far", which the Now card already shows.
- **ArcGauge / DotMatrix / HourlyBars / TrendsDailyLine.** A 0–critical gauge is a de-facto AQI; the
  fixed-range axis is the failure our p95 axis already fixed; a daily line drops coverage and estimation;
  min–max normalisation makes a flat week look like a range. The ring shape already means *time* in `DayRing`.
- **Always-on foreground polling service** (`START_STICKY`, `while(isActive){tick; delay}`): exactly what
  AGENTS.md forbids, and `dataSync` is time-budgeted (6 h/24 h) on API 35+. `SyncForegroundService` stays
  transfer-scoped.
- **Adaptive polling backoff**, **notification text gate**, **startup reconciler**: our WorkManager
  `Result.retry()`, the `conflate` + ≤ 2 posts/s pacing on the progress flow, and
  `AutoSyncScheduler.start()` in `Application.onCreate` (+ WorkManager's own reschedule receiver) already
  cover each; theirs re-arms only from `MainActivity`.
- **detekt/ktlint**: ~540 lines > 120 chars and a 721-line `DeviceScreen` would fire hundreds of findings;
  detekt 1.23.8 embeds Kotlin 2.0 vs our 2.4.20. Lint stays the gate.
- **Release signing workflow**: no version scheme or release yet; copy the env-var signing when there is one.
- **Sealed-only UI state**: our screens show many facets at once; their Settings uses the same flat-state-with-sealed-facets hybrid we do.
- **`AGENT_LOG.md`** (machine loop log), **`InstantLabels`** (absolute, we already have relative `formatAgo`),
  **`StoredEnum`**, **`.sdkmanrc`**, **Compose androidTests** (their CI compiles but never runs them either).

## Suggested order

1. `Theme.kt` → `AirMood.of` (bug, ten lines, boundary test).
2. Backup rules: quota + encryption condition; remove dead `backup_rules.xml`; README decision row.
3. `verify.yml` CI + `.editorconfig` + a sentence in AGENTS.md.
4. Auto-sync run diagnostics (feeds README "Next" 2).
5. Chart accessibility narratives (pure, tested, iOS-ready).
6. Launcher/splash identity; storage "On this phone" block.
7. `Level` decoupling + role colours — before the first non-PM banded metric.
8. Week grid, `AnimatedContent` on Now, single bottom bar; then, only after 4 proves runs happen, the
   opt-in "air was bad while you were away" summary line.
