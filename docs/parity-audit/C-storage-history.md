# Audit C. Storage, archive, index, history, Parquet, export (Android vs iOS)

Date 2026-10-09. Read-only audit of `main` at 180d1ee. Paths are relative to the repo root.
Scope: on-device archive, receipts and index, Room, history tiers and chart data, Parquet read and
write, export and share, metric registry and health scales, units. Transport engine, screens and
settings/background are covered by other audits and only touched where the archive depends on them.

Evidence level: source reading only. No build, simulator or phone run was made for this audit.

## 0. One-paragraph summary

The shared core (`:core:parquet`, `:core:history`, `:core:airquality`, `:core:units`) is already
portable and compiles for iOS, but iOS only uses `NowReading`, `UnitSystem` and the Discover
reader. Everything between a verified file and a chart (index, time estimate, tiers, derived day,
repository, export of derived files) exists only on Android, in `app/.../data/history` and
`app/.../data/local`, tied to Room, `java.io.File`, `java.security.MessageDigest`, `java.time` and
`android.util.Log`. The archive layout itself is defined twice and differs: partial naming,
receipt format, station charset, verification code and conflict handling. Rule 4 of
`docs/cross-platform-guide.md` ("one archive layout defined once in shared code") is not met.
Four iOS behaviour bugs follow from the Swift copy (section 2).

## 1. Parity matrix

Legend: yes, partial, no. "shared" means the decision lives in `core/*/commonMain`.

| Concern | Android | iOS | Shared? | Notes |
| --- | --- | --- | --- | --- |
| Archive root | yes `filesDir/archive/<station>/<hive name>` `app/src/main/java/earth/walkthru/aqsync/data/sync/FileArchive.kt:18,32,56` | yes `Application Support/SensorArchive/<station>/<hive name>` `ios/App/SensorArchive.swift:30-34,85-86` | no | Relative layout below the station is the same, root names differ (`archive` vs `SensorArchive`) |
| Partial naming | `archive-partial/<station>/<name>.partial` + `.partial.meta` text `"<size> <crc32 decimal>"` `FileArchive.kt:19-20,72-73,95,103` | beside the final, `<name>.<size>-<crc hex>.partial` `SensorArchive.swift:165` | no | Divergent. iOS partials live inside the archive tree |
| Partial reset rule | meta mismatch or longer than size, delete and restart `FileArchive.kt:97-102` | longer than size, delete `SensorArchive.swift:166-170`. A changed size/crc picks a new file name, old one is orphaned | no | See bug B3 |
| Receipt / "is synced" | Room row `archived_file` (bytes, crc32, sha256, syncedAt, sourceDevice) `data/local/database/ArchivedFile.kt:20-36` | `<name>.parquet.verified.json` `{size, crc}` `SensorArchive.swift:22-25,100-129` | no | iOS has no sha256, no sourceDevice, no syncedAt |
| Completed check per run | trusts Room row via `SyncPlanner` `data/DeviceConnectionRepository.kt:333,827` | re-reads and re-CRCs every final file `SensorArchive.swift:102-116` called from `SensorTransfer.swift:98-101` | no | See bug B2 |
| Verify (size, CRC-32, PAR1) | shared `ParquetVerify` `core/protocol/src/commonMain/.../ParquetVerify.kt:58-70` + JVM file adapter `core/protocol/src/jvmMain/.../ParquetVerifyJvm.kt:25-43` | own Swift CRC table and check order (size, magic, CRC) `SensorArchive.swift:16-20,178-196` | Android only | Same verdict, duplicated code, no typed reason on iOS |
| Name rule (399 bytes, charset) | shared `SafeName` `core/protocol/src/commonMain/.../SafeName.kt:18-25` | Swift copy `SensorArchive.swift:72-84` | Android only | Equivalent today, two copies |
| Station rule | `[0-9A-Za-z._-]+`, not `.`/`..` `FileArchive.kt:130-133` | `[A-Za-z0-9_-]` `SensorArchive.swift:73-75` | no | Android allows `.`, iOS does not |
| Symlink defence | no | yes, every ancestor and receipt `SensorArchive.swift:87-96,108,120-122` | no | |
| Atomic commit, never replace | yes rename, refuse existing `FileArchive.kt:116-125` | yes move, existing final must verify `SensorArchive.swift:229-243` | no | |
| Existing final without record | verify, sha256, insert indexed row `data/sync/SyncEngine.kt:128-156` | verify, write receipt `SensorArchive.swift:156-161` | no | Same idea, different store |
| Per-file conflict handling | per-file `FileOutcome.Mismatch`, run continues `SyncEngine.kt:131-136` | throws, whole run aborts `SensorTransfer.swift:100,114` | no | See bug B1 |
| Writer exclusion | per (station, name) lease `FileArchive.kt:35-54` | one `ArchiveIO` actor per collection, new instance per session `ios/App/LanDiscovery.swift:189`, `SensorArchive.swift:248-274` | no | |
| File protection class | default (credential encrypted storage) | `completeUntilFirstUserAuthentication` `SensorArchive.swift:38-44,53-70` | n/a | OS seam, fine to stay native |
| DB schema / migrations | Room v10, explicit 1-2, 2-3, 3-4, 8-9, 9-10, auto 4-8 `data/local/database/AppDatabase.kt:32-88`, schemas in `app/schemas/` | none. Legacy final without receipt is upgraded on first OPEN `SensorArchive.swift:156-161`, `ios/tests/ArchiveChecks.swift:82-101` | no | |
| History index (`ParquetIndexer`) | yes `data/history/HistoryIndexer.kt:34-66` (+ backfill 72-97) | no | logic shared, wiring Android only | Producer-station check uses `HivePartition` in `data/sync/SyncPlanner.kt:13-49` |
| Time estimate for unsynced boots | yes `HistoryIndexer.kt:104-124` | no | `TimeEstimate` shared | |
| Tiers (`metric_bucket` 15 min, 1 h, 1 d) | yes `data/history/HistoryDeriver.kt:81-167`, `data/local/database/History.kt:19-97` | no | `Aggregator` shared `core/history/.../Aggregation.kt:55-128` | |
| Derived daily Parquet + manifest | yes `data/history/DailyDerived.kt:86-224` | no | `ParquetWriter` shared | |
| Day signature (SHA-256) | yes `HistoryDeriver.kt:68-73` (`MessageDigest`) | no | no | No common SHA-256 exists |
| Chart data (`HistoryRepository`, `Columns`, `ChartAxes`) | yes `data/history/HistoryRepository.kt:34-216` | no (`docs/ios-migration.md:130` "no archive charts") | `Columns`, `ChartAxes`, `HistoryWindow` shared | |
| Chart by file (`FileSummary`) | yes `HistoryRepository.kt:67-86` | no | `FileSummary` shared | |
| Metric registry | yes everywhere | partial, only `Metrics.AMBIENT_TEMP/BOARD_TEMP` units for the Now card `ios/shared/src/commonMain/.../CompanionScreen.kt:82` | yes `core/history/.../Metrics.kt:65-96` | Planned move to `:core:model` (W11) not done |
| Moods, WHO 2021 steps | yes, colour `ui/theme/Color.kt:19,39`, step names `ui/components/MoodText.kt:74-81` | partial, mood title text only, no step colour or step name `CompanionScreen.kt:71-73` | yes `core/airquality/.../HealthScale.kt:77-88`, `core/history/.../AirMood.kt:20-46` | Discover uses the same scale on both (`feature/discover/.../CellChartMath.kt:34,87`) |
| Units | yes DataStore `unit_system` | yes `NSUserDefaults settings.unitSystem` `ios/shared/src/iosMain/.../AppRoot.kt:75-91` | yes `core/units/.../Units.kt` + expect `systemUnitSystem` | Parity ok |
| Parquet read | yes archive, files, history, Discover | Discover sample only | yes `core/parquet` | ZSTD Android only `data/history/parquet/AppParquetCodecs.kt:15-18` (by design) |
| Parquet write | yes daily files | no | yes `ParquetWriter` / `ParquetSink` | |
| Raw export | zip (stored), SAF save, share zip, share N files `data/export/ExportManager.kt:41-147` | folder snapshot via `UIDocumentPickerViewController(forExporting:)` `ios/App/AppDelegate.swift:62-113`, `SensorArchive.swift:132-154` | no | Both keep `<station>/<hive>` paths. iOS no per-file share, no zip |
| Derived export | yes daily zip `ExportManager.kt:91-105` | no (no derived files) | no | |
| Delete / retention | no. `FileArchive.delete` exists but has no caller and leaves the Room row `FileArchive.kt:127` | no | no | |
| Backup | cloud excludes DB and archive, device transfer includes `archive/` and DB, not partials `app/src/main/res/xml/data_extraction_rules.xml` | root `isExcludedFromBackup` (also excludes from backup based migration) `SensorArchive.swift:58-63` | n/a | Partial parity. iOS loses the archive on a backup restore, Android keeps it on device transfer |
| Tests | Room-free fakes in `app/src/test/.../data/history/*Test.kt`, `ConcurrentArchiveTest` | `ios/tests/ArchiveChecks.swift` (Swift only) | partial | `core/history` Aggregation/Columns/TimeEstimate/ParquetIndexer/FileSummary tests are `jvmTest` only, not run on iOS |

## 2. Duplicated or divergent decisions, and real bugs

### 2.1 Behaviour bugs

**B1 (iOS, high). One bad local file blocks every future sync of that sensor.**
`SensorArchive.completed` throws `conflictingArchive` when a receipt's size differs or the final
file no longer matches its CRC (`ios/App/SensorArchive.swift:112-114`). `prepare` throws the same
when an existing final disagrees with OPENED (`SensorArchive.swift:158-159`). The transfer loop
calls both with `try` and no per-file catch (`ios/App/SensorTransfer.swift:98-101,114`), so the
whole collection ends. There is no delete UI to clear it. Android reports a per-file
`FileOutcome.Mismatch` and continues with the other files (`app/.../data/sync/SyncEngine.kt:128-136`).
`ArchiveChecks.swift:103-118` asserts the throw but not the run-level consequence.

**B2 (iOS, medium, battery). Every run re-CRCs the whole archive.**
`completed` calls `verified`, which streams the full file through a Swift per-byte table loop
(`SensorArchive.swift:112,189-194`) for every file the sensor lists (`SensorTransfer.swift:98-101`).
Cost grows with the archive (767 real files today). This runs inside the bounded iOS background
task window for LAN. Android trusts the index row and only re-verifies on the commit path.

**B3 (iOS, low). Orphaned partials.**
The partial name embeds size and CRC (`SensorArchive.swift:165`). If OPENED changes for the same
name, the old partial is never removed. Android keeps one partial per name and resets it on meta
mismatch (`FileArchive.kt:97-102`).

**B4 (iOS, medium). Corrupt resumed prefix fails the run instead of retrying once.**
On a final CRC failure iOS deletes the partial and throws (`SensorArchive.swift:231-233`), ending
the run (B1). Android re-reads once from zero with the same handle when the partial was resumed
(`SyncEngine.kt:231-239`). Shared with the sync audit, listed here because the decision sits in
the archive adapter, which rule 6 forbids.

**B5 (Android, low). De-duplication key differs between tiers and the daily file.**
`Aggregator` and `HistoryRepository.fromRows` skip de-duplication when `sequence` is absent or
null (`core/history/src/commonMain/.../Aggregation.kt:94-97`, `app/.../data/history/HistoryRepository.kt:162-165`).
`DailyDerivedBuilder` always de-duplicates on `(boot_hi, boot_lo, sequence ?: utc_ns)` with 0 for a
missing boot (`app/.../data/history/DailyDerived.kt:123-124`). For files without a sequence, or
rows with a null sequence, the tier row counts and the daily file can disagree, and two different
boots with an identical timestamp collapse in the daily file. `docs/history-pipeline.md` says the
two "agree". Current firmware writes sequence, so impact is latent.

**B6 (Android, low). Dead delete path that would desync the index.**
`FileArchive.delete` removes the file but not the `archived_file` row (`FileArchive.kt:127`). No
caller today. If wired as is, `HistoryIndexer.backfill` would mark the row "file missing" but the
planner would still count the file as synced.

**B7 (Android, low). Export temp files are deleted under a running share.**
`buildDailyZip` and `buildArchiveZip` wipe `cacheDir/export` first (`ExportManager.kt:94,114`). A
second export deletes a zip another app may still be reading through an earlier share intent.

**B8 (iOS, presentation). Now card does not follow the WHO display rule.**
The card prints the mood title only (`CompanionScreen.kt:71-73`), without the step colour, the WHO
step name or the "compared with the 24-hour guideline" note required by
`docs/cross-platform-guide.md` "Health scales and charts". Android uses `WhoStep.color()` and
`labelText()`. Screens audit owns the fix, the data (`AirMood.step`, `HealthScale`) is already shared.

### 2.2 Duplicated decisions (same decision written twice)

| Decision | Kotlin | Swift / other copy |
| --- | --- | --- |
| CRC-32 table and loop | `core/protocol/src/commonMain/.../Crc32.kt` (expect, JVM `java.util.zip`, iOS actual) | `ios/App/SensorArchive.swift:16-20,189-195` |
| Completion rule order | `ParquetVerify.kt:58-70` (size, CRC, magic) | `SensorArchive.swift:178-196` (size, magic, CRC) |
| Name safety | `SafeName.kt:18-25` | `SensorArchive.swift:72-84` |
| Station safety | `FileArchive.kt:130-133` | `SensorArchive.swift:73-75` (different charset) |
| Archive and partial layout | `FileArchive.kt:14-33,72-73` | `SensorArchive.swift:30-34,104,165` |
| "Is this file synced" record | `ArchivedFile` Room row | `.verified.json` receipt |
| Export file set and tree | `FileArchive.listAll` `FileArchive.kt:61-68` + zip naming `ExportManager.kt:111-129` | `SensorArchive.export` `SensorArchive.swift:132-154` |
| WHO axis top | `ChartAxes.snapped` with `Columns.axisRaw` (p95 x 1.2, floor `axisFloor`) `core/history/.../ChartAxis.kt:40-47`, `Columns.kt:184-188` | `HealthAxis.high` (p95, floor guideline, no headroom) `core/publicfeed/.../CellScale.kt:56-59` |

The last row is Kotlin vs Kotlin. Time-series and histogram charts may legitimately differ, but the
"snap to the next WHO edge" rule should live once in `:core:airquality` with the headroom as a parameter.

### 2.3 Doc drift found on the way

- `docs/history-pipeline.md` says the daily file's `derived_by` is `AQ Sync Android derived-v1`. Code writes `opensensor.space Android derived-v1` (`DailyDerived.kt:259`).
- `docs/production-readiness-plan.md` W6 says "the old layouts are dropped, with no migration". `docs/cross-platform-guide.md` rule 4 says a layout change "ships with a migration for both platforms". Pick one. Recommendation in section 4, step 9 (cheap rename migration, discard partials).
- `docs/data-model.md` "Files under filesDir" lists only Android. After unification it should state the canonical relative layout and each platform's root.
- `core/history` tests that need fixtures are `jvmTest` only, contrary to rule 5 (`docs/cross-platform-guide.md`). `core/parquet` already solved this with `PARQUET_FIXTURES` (`docs/parquet.md` "Fixtures and oracles").

## 3. What can move to commonMain now, and what needs ports

### 3.1 Inventory of `app/.../data/history` and `data/local`

| File | Pure logic | Platform ties | Verdict |
| --- | --- | --- | --- |
| `data/history/ArchiveQueries.kt` | query keys, `archiveQueryResults` flow operator | `java.time.ZoneId` (swap for `kotlinx.datetime.TimeZone`, already used by `HistoryWindow`) | Move now (S) |
| `ui/history/HistoryPresentation.kt:19-62` | `countHistoryFiles`, `preferredHistoryStation`, `latestHistoryDayEnd` | `ZoneId`, `ArchivedFile` entity | Move after records exist |
| `data/sync/SyncPlanner.kt:13-49` `HivePartition` | regex parsing of Hive names | `String.format` in `isoDate` only | Move now (S), coordinate with sync audit, used by indexer |
| `data/history/HistoryIndexer.kt` | producer-station check, index mapping, backfill loop, `estimateTimes` | `File`, `IOException`, `Log`, Room DAO, `Dispatchers.IO` | Needs `ArchiveStore.reader` + `ArchiveIndex` |
| `data/history/HistoryDeriver.kt` | `daysOf`, `signature`, stale detection, `buildDay`, bucket mapping | `MessageDigest`, `Math.floorDiv`, `String.format`, `Log`, DAOs, `File.isFile` | Needs SHA-256 in common + `ArchiveIndex`, `TierStore`, `DerivedDayStore`, `DerivedFiles` |
| `data/history/DailyDerived.kt` | whole builder, manifest model (already kotlinx.serialization) | `File`, temp + rename, `OutputStream`, `java.time` for `dayLabel` and `builtAt`, `Math.floorDiv` | Needs `DerivedFiles` port (atomic write via `ParquetSink`) |
| `data/history/HistoryRepository.kt` | tier fold, edge reads, raw-row binning, file-summary cache | `FileArchive`, `File`, `synchronized`, `Dispatchers.IO`, `Math.floorDiv` | Needs `ArchiveStore.reader` + `ArchiveIndex` + `TierStore` |
| `data/history/parquet/AppParquetCodecs.kt` | ZSTD registration | `zstd-jni` | Stays Android |
| `data/export/ExportManager.kt` | file selection, entry naming, zip names | `Intent`, `FileProvider`, SAF, `ZipOutputStream` | Extract an `ExportPlan` (entries and names) to common, keep zip and share native |
| `data/local/database/ArchivedFile.kt` | `indexed`, `hasUtc`, `hasEstimatedUtc`, `timelineFirstNs/LastNs`, `info()` | Room annotations | Make a common `ArchiveRecord` with these properties, Room entity maps to it |
| `data/local/database/History.kt` | `MetricBucket`, `DerivedDay` shapes, `replaceDay` semantics | Room | Common `TierBucket`, `DerivedDayRecord`, Room maps |
| `data/local/database/AppDatabase.kt`, `di/DatabaseModule.kt` | none | Room, Hilt | Stays Android (adapter) |
| `KnownDevice.kt`, `StationEpoch.kt`, `SensorInstallation.kt`, `security/*` | device registry | Room, Keystore | Out of this slice, device registry audit. Keep `lanToken` out of any shared schema |
| `ui/history/FileMetricsViewModel.kt` | opens `ParquetFile` from `FileArchive` in a ViewModel | storage in UI layer | Route through the repository once it is common |

Prerequisite missing in common: SHA-256 (used for `archived_file.sha256`, manifest `sha256`, and
the day signature). Options are a small pure Kotlin SHA-256 in `core/protocol` commonMain (tested on
NIST vectors, about 80 lines, no new dependency) or an `expect` with `MessageDigest` and CommonCrypto
`CC_SHA256`. Pure Kotlin is simpler and matches how `Crc32` and `Lz4Block` are done.

### 3.2 Proposed ports (new module `:core:archive`, commonMain, depends on `:core:protocol`, `:core:parquet`)

Keep `:core:*` free of Room (AGENTS.md boundary). Ports and records are plain Kotlin.

```kotlin
package earth.walkthru.aqsync.archive

/** The one canonical layout, relative to a platform root (Android filesDir, iOS Application Support). */
object ArchiveLayout {
    const val ARCHIVE = "archive"
    const val PARTIAL = "archive-partial"
    const val DERIVED = "derived/v1"
    fun isSafeStation(station: String): Boolean            // [0-9A-Za-z_-]+, the stricter of the two
    fun finalPath(key: ArchiveKey): String                  // archive/<station>/<name>
    fun partialPath(key: ArchiveKey): String                // archive-partial/<station>/<name>.partial
    fun partialMetaPath(key: ArchiveKey): String            // ….partial.meta
    fun encodeMeta(size: Long, crc32: Long): String         // "<size> <crc32>"
    fun decodeMeta(text: String): Pair<Long, Long>?
    fun derivedDayPath(station: String, epochDay: Long): String
    fun derivedManifestPath(station: String, epochDay: Long): String
}

data class ArchiveKey(val station: String, val name: String) {
    init { require(ArchiveLayout.isSafeStation(station) && SafeName.isValid(name)) }
}

/** Byte storage. Platform adapters do file I/O only, every decision is in common callers. */
interface ArchiveStore {
    suspend fun partialLengths(station: String): Map<String, Long>
    /** Opens or resumes; resets when the stored meta differs or the partial is longer than [size]. */
    suspend fun openPartial(key: ArchiveKey, size: Long, crc32: Long): PartialWriter
    suspend fun discardPartial(key: ArchiveKey)
    suspend fun exists(key: ArchiveKey): Boolean
    /** Random access to a final or partial file, for verification and ParquetFile.open(reader). */
    fun reader(key: ArchiveKey, partial: Boolean = false): ByteReader
    /** Atomic rename partial -> final. Throws if the final exists. Never replaces. */
    suspend fun commit(key: ArchiveKey)
    suspend fun listFinal(station: String? = null): List<ArchiveKey>
    suspend fun delete(key: ArchiveKey)                       // index row removed by the caller in the same operation
}

interface PartialWriter : AutoCloseable {
    val resumeFrom: Long
    suspend fun write(offset: Long, bytes: ByteArray)
    suspend fun sync()
}

/** Files that are caches of the archive (derived day Parquet and manifest). */
interface DerivedFiles {
    suspend fun writeAtomically(path: String, write: (ParquetSink) -> Unit)
    suspend fun writeTextAtomically(path: String, text: String)
    suspend fun exists(path: String): Boolean
    suspend fun delete(path: String)
    suspend fun list(): List<String>
}

/** Common records. Room entities on Android and the iOS store map to these. */
data class ArchiveRecord(/* station, name, bytes, crc32, sha256, syncedAtEpochMs, sourceDevice,
    index columns, estimate columns, exactly the archived_file v10 columns */) {
    val indexed: Boolean; val hasUtc: Boolean; val hasEstimatedUtc: Boolean
    val timelineFirstNs: Long?; val timelineLastNs: Long?
    fun onTimeline(fromNs: Long, toNs: Long): Boolean   // the withUtcBetween predicate, once, in common
}
data class TierBucket(/* metric_bucket v7 columns */)
data class DerivedDayRecord(/* derived_day columns */)

interface ArchiveIndex {
    fun observeAll(): Flow<List<ArchiveRecord>>
    fun observeStation(station: String): Flow<List<ArchiveRecord>>
    suspend fun get(key: ArchiveKey): ArchiveRecord?
    suspend fun put(record: ArchiveRecord)                    // replace on (station, name)
    suspend fun remove(key: ArchiveKey)
    suspend fun unindexed(limit: Int): List<ArchiveRecord>
    suspend fun timeline(station: String, fromNs: Long, toNs: Long): List<ArchiveRecord>  // telemetry only, ordered
    suspend fun indexedForStation(station: String): List<ArchiveRecord>
    suspend fun stations(): List<String>
}

interface TierStore {
    fun observe(station: String, metricId: String, resolution: Long, fromS: Long, toS: Long): Flow<List<TierBucket>>
    fun observeAllDays(metricId: String): Flow<List<TierBucket>>
    fun observeDaySpan(station: String): Flow<Triple<Int, Long?, Long?>>   // count, first, last
    suspend fun replaceDay(station: String, dayStartS: Long, rows: List<TierBucket>)  // atomic
}

interface DerivedDayStore {
    suspend fun forStation(station: String): List<DerivedDayRecord>
    suspend fun put(day: DerivedDayRecord)
    suspend fun delete(station: String, epochDay: Long)
    suspend fun stations(): List<String>
}
```

Common services on top: `ArchiveVerifier` (streams `reader` through `Crc32` and `ParquetVerify`,
and SHA-256), `ArchiveIndexer` (today's `HistoryIndexer`), `HistoryDeriver`, `DailyDerivedBuilder`,
`HistoryRepository`, `ExportPlan`. The W6 sync engine (`:core:sync`, sync audit) consumes
`ArchiveStore` + `ArchiveIndex` + `ArchiveVerifier`, so "is this synced" becomes `index.get(key)`
matching size and CRC on both platforms. That removes the iOS receipt files and B1 to B4.

### 3.3 iOS persistence choice

Pinned: Room `2.8.5`, KSP `2.3.12`, Kotlin `2.4.20`, CMP `1.12.1`, targets `jvm`, `iosArm64`,
`iosSimulatorArm64` (`gradle/libs.versions.toml:10,18-19`, `build-logic/.../KmpLibraryConventionPlugin.kt:9-23`).

| Option | Fit | Cost |
| --- | --- | --- |
| **Room KMP** (KMP support since Room 2.7.0; 2.8.5 already pinned) | Same entities, same SQL for `withUtcBetween`, same `Flow` observation, schema export, auto migrations. iOS uses `NativeSQLiteDriver` (system libsqlite3, `androidx.sqlite:sqlite-framework`) or `BundledSQLiteDriver`. Every current DAO method is already `suspend` or `Flow`, which Room KMP requires off Android. Also has a JVM target, so the Room adapter can be contract-tested on the host | KSP per iOS target, `@ConstructedBy` + `RoomDatabaseConstructor`, the Room Gradle plugin for `schemaDirectory`. KMP migrations use `migrate(connection: SQLiteConnection)`, so the five hand-written migrations need that override if the Android DB moves later. Must check KSP 2.3.12 with Kotlin 2.4.20 in this build before committing |
| SQLDelight 2.x | Mature KMP, SQL first | Android would carry two DB stacks or rewrite DAOs and the v1-v10 history. Highest churn |
| File receipts only (today's iOS) | Good enough for "is synced" | Cannot serve tiers (about 1 300 bucket rows per station-day, 480 k a year) or timeline queries without loading everything. Would need a second design later |

**Recommendation.** Room KMP, in a new non-core KMP module (for example `:data:store`, targets
`jvm` and iOS), because AGENTS.md keeps Room out of `:core:*`.

- Phase A. `:data:store` defines `ArchiveDatabase` (tables `archived_file`, `metric_bucket`,
  `derived_day` with the exact v10 DDL) plus adapters `RoomArchiveIndex`, `RoomTierStore`,
  `RoomDerivedDayStore` implementing the common ports. iOS uses it with `NativeSQLiteDriver` at
  `Application Support/archive.db`, excluded from backup like the archive.
- Android keeps `AppDatabase` v10 in `:app` untouched and adds thin adapters
  (`ArchivedFileDao` -> `ArchiveIndex`, `MetricBucketDao` -> `TierStore`,
  `DerivedDayDao` -> `DerivedDayStore`) with entity <-> record mappers. No Android migration, no
  risk to installed data.
- Phase B (optional, later, coordinate with the device registry audit). Move the whole
  `AppDatabase` into `:data:store` commonMain so both phones run one schema and one migration
  chain. Requires porting `MIGRATION_1_2` ... `MIGRATION_9_10` (`AppDatabase.kt:50-88`) to
  `SQLiteConnection`, and deciding whether `known_device.lanToken` stays Android-only (iOS keeps
  tokens in Keychain).

## 4. Ordered migration steps

Sizes S (under a day), M (1 to 3 days), L (more). "Owns" lists the files a step edits, chosen so
parallel steps never touch the same file.

| # | Step | Size | Depends on | Owns | commonTest plan |
| --- | --- | --- | --- | --- | --- |
| 1 | `:core:archive` module skeleton, `ArchiveLayout`, `ArchiveKey`, station rule (strict charset), partial meta codec | S | none | new `core/archive/**`, `settings.gradle.kts` (one line) | Paths for dated, unsynced, benchmarks names. Unsafe station and name table (reuse the list from `ios/tests/ArchiveChecks.swift:31-34`). Meta round trip and malformed meta |
| 2 | Common SHA-256 (pure Kotlin) next to `Crc32` | S | none, parallel with 1 | `core/protocol/src/commonMain/.../Sha256.kt`, its test | NIST short and long vectors, chunked vs one-shot, JVM vs iOS run |
| 3 | Move `core/history` fixture tests from `jvmTest` to `commonTest` using the `PARQUET_FIXTURES` pattern; keep `java.time` oracles in `jvmTest` | S | none, parallel | `core/history/src/*Test/**`, `core/history/build.gradle.kts` | Aggregation, TimeEstimate, ParquetIndexer, FileSummary now on simulator too |
| 4 | Ports, common records, `ArchiveVerifier`, in-memory fakes published as test fixtures | M | 1, 2 | `core/archive/src/commonMain/**`, `core/archive/src/commonTest/**` | Verifier: size, CRC, magic, short file never hashed, typed reasons. Fake store contract suite (reset on meta mismatch, never replace on commit, list excludes partials) written once and later run against real adapters |
| 5a | Move `HivePartition`, `ArchiveQueries`, `HistoryPresentation` helpers to common with `TimeZone` | S | 4 | those three files, new common files | Partition parsing table, latest day end across DST |
| 5b | Move `HistoryIndexer` as `ArchiveIndexer` (index, backfill, estimateTimes) on ports | M | 4 | `app/.../data/history/HistoryIndexer.kt` (deleted), new common file | Port `HistoryIndexerTest` to fakes. Producer mismatch keeps raw bytes, refused file keeps row, retry loop finite |
| 5c | Move `HistoryDeriver` + `DailyDerivedBuilder` on ports, `kotlinx-datetime` day labels, common SHA-256. Fix B5 here (one de-dup key used by Aggregator, repository and builder) | M | 4, 2 | `HistoryDeriver.kt`, `DailyDerived.kt` (deleted), new common files | Port `HistoryDeriverTest`. Signature stability (golden hex), stale day rebuild, dropped day, missing daily file retried, tiers row count equals daily file rows on fixtures with and without sequence |
| 5d | Move `HistoryRepository` on ports, `Mutex` instead of `synchronized` | M | 4 | `HistoryRepository.kt` (deleted), new common file | Port `HistoryRepositoryTest`, fractional-offset midnight edges, binning above 3 000 rows |
| 6 | Android adapters: `FileArchive` implements `ArchiveStore` (add symlink check), Room DAO adapters and mappers, Hilt bindings. Remove dead `FileArchive.delete` or make it remove the row too (B6) | M | 5a-5d | `app/.../data/sync/FileArchive.kt`, `app/.../data/local/**` adapter files, DI modules | Run step 4 contract suite against `FileArchive` on JVM. Room adapter test with an in-memory DB |
| 7 | `:data:store` with Room KMP `ArchiveDatabase` (3 tables) and Room port adapters, schema export | L | 4, parallel with 5 and 6 | new `data/store/**`, `gradle/libs.versions.toml` (sqlite driver, room plugin) | Contract suite on JVM with `BundledSQLiteDriver`, and on simulator. `timeline` must equal `ArchiveRecord.onTimeline` on randomized records |
| 8 | iOS `ArchiveStore` in Kotlin `iosMain` (NSFileManager / NSFileHandle), keeping protection class, backup exclusion and symlink checks from `SensorArchive.swift` | M | 1, 4 | new `core/archive/src/iosMain/**` or `data/store/src/iosMain/**` | Step 4 contract suite on simulator. Swift `ArchiveChecks` shrinks to an OS-attribute check |
| 9 | iOS one-shot migration: rename `SensorArchive/<station>/…` to `archive/<station>/…`, import each `.verified.json` into the index (compute SHA-256, run indexer), discard `*.partial` and receipts. Record the decision in `docs/data-model.md`, and settle the rule 4 vs W6 wording | M | 7, 8 | iOS migration file, `docs/data-model.md`, `docs/production-readiness-plan.md` | Fixture tree with receipts, legacy finals, partials, a symlink. Idempotent second run |
| 10 | Switch iOS collection to the shared engine and ports (sync audit's W6), then call `ArchiveIndexer` and `deriveStale` after commits. Deletes `SensorArchive.swift` and the Swift CRC | M | 6-9 and W6 | `ios/App/SensorArchive.swift`, `SensorTransfer.swift` (with the sync audit) | B1 to B4 covered by engine tests in `commonTest`: one conflicting file is reported and the run continues |
| 11 | Common `ExportPlan` (entries, Hive tree, raw vs derived, names). Android zips it, iOS exports the folder or a zip. Add derived export and per-file share on iOS. Use a unique export folder per run on Android (B7) | M | 6, 10 | `ExportManager.kt`, `ios/App/AppDelegate.swift` export section, new common file | Plan excludes partials and receipts, keeps `<station>/<hive>` paths, stable ordering |
| 12 | Unify WHO axis snapping into `:core:airquality` with headroom parameter, used by `ChartAxes` and `HealthAxis` | S | none, parallel | `ChartAxis.kt`, `CellScale.kt`, new `core/airquality` function | Same edge snapping table for both callers, guideline always visible |
| 13 | Optional Phase B: move `AppDatabase` into `:data:store`, migrations to `SQLiteConnection` | L | 7, device registry audit | `app/.../data/local/**`, `data/store/**` | Existing `tools/validate_*_migration.py` plus Room `MigrationTestHelper` on JVM |

Parallel lanes without file overlap: {1, 2, 3, 12} first. Then 4. Then {5a, 5b, 5c, 5d, 7, 8}.
Then {6, 9}. Then 10 with the sync audit. Then 11. 13 is independent and last.

Evidence to record when done: `./gradlew --no-daemon verify verifyIos`, the unsigned simulator build,
then one Android phone run and one iPhone run that sync, chart a day, and export, recorded in
`docs/status.md`. A host or simulator pass does not qualify radio or background behaviour.
