package space.opensensor.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import space.opensensor.archive.ArchiveIndex
import space.opensensor.archive.ArchiveKey
import space.opensensor.archive.ArchiveLayout
import space.opensensor.archive.ArchiveRecord
import space.opensensor.archive.ArchiveStore
import space.opensensor.archive.ArchiveVerifier
import space.opensensor.archive.ExportRuns
import space.opensensor.archive.PrunedFiles
import space.opensensor.data.protocol.ParquetVerify
import space.opensensor.data.protocol.Sha256
import space.opensensor.data.protocol.toHex
import space.opensensor.data.sync.ArchiveRecordIndexer
import space.opensensor.data.sync.DeviceCoordinator
import space.opensensor.sync.nowEpochMs

/**
 * The startup sweep of #60, run once per process on a background dispatcher (Android through a
 * one-time WorkManager job, iOS from `IosArchive.startIndexRepair`). Idempotent and cheap when there
 * is nothing to do.
 *
 * 1. Temp files of [AtomicFiles] older than [TEMP_AGE_MS], and the older builds' derived temps.
 * 2. Released export runs older than [ExportRuns.MAX_AGE_MS]. A run a live export still leases stays.
 * 3. Partials of a station the phone does not know, after [UNKNOWN_STATION_AGE_MS], and any partial
 *    untouched for [STALE_PARTIAL_AGE_MS]. A fresh partial of a known station is kept for resume.
 * 4. Final files without an index record, left by a crash between the commit and the record. Each is
 *    hashed (size, CRC-32, SHA-256) and checked for `PAR1`, then indexed as adopted.
 * 5. The pre #61 `derived/v1` in app files ([StorageRole.LegacyDerived]), removed whole, and the
 *    derived day files over the quota, through [quota] (#61).
 *
 * It never deletes anything under a [StorageClass.Durable] root: [sweep] refuses any path not under
 * a Scratch or Rebuildable root. It logs counts only, never paths or station ids.
 */
class StorageJanitor(
    private val roots: StorageRoots,
    private val tree: StorageTree,
    private val store: ArchiveStore,
    private val index: ArchiveIndex,
    private val io: CoroutineDispatcher,
    private val indexer: ArchiveRecordIndexer = ArchiveRecordIndexer.None,
    /** Stations the phone knows besides those in the index (paired devices), whose partials are kept. */
    private val knownStations: suspend () -> Collection<String> = { emptyList() },
    private val clock: () -> Long = ::nowEpochMs,
    private val log: (String) -> Unit = {},
    /** Evicts derived day files over the quota. Null skips the quota step. */
    private val quota: DerivedQuota? = null,
    /**
     * The archive's one coordinator, shared with the sync engine. Its writer lease keeps a partial a
     * resume writes from being swept, its station gate covers each adopted record's insert, and an
     * adopted file is on the phone again, so its tombstone goes.
     */
    private val coordinator: DeviceCoordinator = DeviceCoordinator(),
) {
    private val pruned: PrunedFiles get() = coordinator.pruned

    data class Report(
        val tempsSwept: Int = 0,
        val exportsSwept: Int = 0,
        val partialsSwept: Int = 0,
        val partialsKept: Int = 0,
        val adopted: Int = 0,
        /** Unindexed finals that failed the `PAR1` check. Kept in place, never deleted. */
        val unverifiable: Int = 0,
        /** Deletes refused because the path was not under a Scratch or Rebuildable root. */
        val refused: Int = 0,
        /** The pre #61 derived directory was found and removed. */
        val legacyDerivedSwept: Int = 0,
        /** Derived day files evicted to bring the derived root under its quota. */
        val derivedEvicted: Int = 0,
        /** Steps or files that threw. The rest of the sweep went on. */
        val failures: Int = 0,
    ) {
        fun describe(): String =
            "storage janitor: swept $tempsSwept temp, $exportsSwept export, $partialsSwept partial, kept $partialsKept partial, " +
                "adopted $adopted, unverifiable $unverifiable, legacy derived $legacyDerivedSwept, evicted $derivedEvicted, " +
                "refused $refused, failures $failures"
    }

    private var report = Report()

    suspend fun run(): Report {
        report = Report()
        val now = clock()
        step { sweepTemps(now) }
        step { sweepExports(now) }
        step { sweepPartials(now) }
        step { adoptOrphans() }
        step { sweepLegacyDerived() }
        step { enforceQuota() }
        log(report.describe())
        return report
    }

    private suspend fun sweepTemps(now: Long) {
        roots[StorageRole.Temp]?.let { dir ->
            for (entry in list(dir)) {
                if (!entry.isDirectory && now - entry.modifiedEpochMs >= TEMP_AGE_MS) {
                    if (sweep("$dir/${entry.path}")) report = report.copy(tempsSwept = report.tempsSwept + 1)
                }
            }
        }
        // Earlier builds wrote derived temps beside the day files: `.derived-*` on Android, `<target>.<uuid>.tmp` on iOS.
        roots[StorageRole.Derived]?.let { dir ->
            for (entry in list(dir)) {
                val name = entry.path.substringAfterLast('/')
                val legacy = name.startsWith(LEGACY_DERIVED_PREFIX) || LEGACY_UUID_TEMP.matches(name)
                if (!entry.isDirectory && legacy && now - entry.modifiedEpochMs >= TEMP_AGE_MS) {
                    if (sweep("$dir/${entry.path}")) report = report.copy(tempsSwept = report.tempsSwept + 1)
                }
            }
        }
    }

    /**
     * The policy of [ExportRuns], shared with every export space. The first pass checks the leases,
     * then the times come from a second listing, since a run released in between has a fresh time by then.
     */
    private suspend fun sweepExports(now: Long) {
        val dir = roots[StorageRole.Exports] ?: return
        val old = list(dir).filter { ExportRuns.isExpired(it.path, now) { it.modifiedEpochMs } }.map { it.path }
        if (old.isEmpty()) return
        val fresh = list(dir).associate { it.path to it.modifiedEpochMs }
        for (run in old) {
            if (ExportRuns.isExpired(run, now) { fresh[run] }) {
                if (sweep("$dir/$run")) report = report.copy(exportsSwept = report.exportsSwept + 1)
            }
        }
    }

    private suspend fun sweepPartials(now: Long) {
        val dir = roots[StorageRole.Partials] ?: return
        val known = (index.stations() + knownStations()).toSet()
        // A partial and its meta form one download. Its age is the newest of the two.
        val downloads = list(dir).filter { !it.isDirectory }.groupBy { it.path.removeSuffix(".meta") }
        fun isStale(download: String, files: List<StorageEntry>): Boolean {
            val station = download.substringBefore('/', missingDelimiterValue = "")
            val age = now - files.maxOf { it.modifiedEpochMs }
            return age >= STALE_PARTIAL_AGE_MS || (station !in known && age >= UNKNOWN_STATION_AGE_MS)
        }
        for ((download, files) in downloads) {
            if (!isStale(download, files)) {
                report = report.copy(partialsKept = report.partialsKept + 1)
                continue
            }
            // Under the writer lease of that file, a resume either finished opening (its meta is fresh in
            // the second listing) or waits until the sweep is done and starts over from zero.
            withLease(download) {
                val current = list(dir).filter { !it.isDirectory && it.path.removeSuffix(".meta") == download }
                when {
                    current.isEmpty() -> Unit
                    !isStale(download, current) -> report = report.copy(partialsKept = report.partialsKept + 1)
                    else -> {
                        var swept = false
                        for (file in current) swept = sweep("$dir/${file.path}") || swept
                        if (swept) report = report.copy(partialsSwept = report.partialsSwept + 1)
                    }
                }
            }
        }
    }

    /** Runs [block] under the lease of the file behind a `<station>/<name>.partial` path. A stray name no engine writes needs none. */
    private suspend fun withLease(download: String, block: suspend () -> Unit) {
        val key = runCatching {
            ArchiveKey(download.substringBefore('/'), download.substringAfter('/').removeSuffix(ArchiveLayout.PARTIAL_SUFFIX))
        }.getOrNull()
        if (key == null) block() else coordinator.withWriter(key) { block() }
    }

    private suspend fun sweepLegacyDerived() {
        val dir = roots[StorageRole.LegacyDerived] ?: return
        if (list(dir).isEmpty()) return
        if (sweep(dir)) report = report.copy(legacyDerivedSwept = report.legacyDerivedSwept + 1)
    }

    private suspend fun enforceQuota() {
        val derived = quota ?: return
        val result = withContext(io) { derived.enforce() }
        report = report.copy(
            derivedEvicted = report.derivedEvicted + result.evicted,
            refused = report.refused + result.refused,
            failures = report.failures + result.failures,
        )
    }

    private suspend fun adoptOrphans() {
        for ((station, keys) in store.listFinal().groupBy { it.station }) {
            val indexed = index.observeStation(station).first().mapTo(HashSet()) { it.name }
            for (key in keys) {
                if (key.name in indexed) continue
                step { coordinator.withWriter(key) { adopt(key) } }
            }
        }
    }

    /**
     * Under the lease of [key], and the insert under the station's gate, so a file the old file
     * cleanup just deleted is never adopted back. A file left by a crash inside that cleanup still has
     * its removed record, which is dropped with the insert, since the file is on the phone again.
     */
    private suspend fun adopt(key: ArchiveKey) {
        if (!store.exists(key)) return
        val hashed = withContext(io) {
            store.reader(key).use { reader ->
                val sha = Sha256()
                val crc = ArchiveVerifier.crc32(reader) { bytes, n -> sha.update(bytes, 0, n) }
                val n = ParquetVerify.MAGIC.size
                val magic = reader.size >= 2L * n && ParquetVerify.hasMagic(reader.read(0, n), reader.read(reader.size - n, n))
                if (magic) Triple(reader.size, crc, sha.digest().toHex()) else null
            }
        }
        if (hashed == null) {
            report = report.copy(unverifiable = report.unverifiable + 1)
            return
        }
        // The engine may have written the record while this file was hashed. Its record wins: skip the
        // footer parse when it is already there, and insert only if absent so a later one is never replaced.
        if (index.get(key) != null) return
        val (bytes, crc32, sha256) = hashed
        val record = ArchiveRecord(
            station = key.station,
            name = key.name,
            bytes = bytes,
            crc32 = crc32,
            sha256 = sha256,
            syncedAtEpochMs = clock(),
            sourceDevice = ADOPTED_SOURCE,
        )
        val indexed = indexer.indexed(record)
        val adopted = coordinator.withStation(key.station) {
            val inserted = store.exists(key) && index.putIfAbsent(indexed)
            if (inserted) forgetRemoved(key)
            inserted
        }
        if (adopted) report = report.copy(adopted = report.adopted + 1)
    }

    /** A record that stays is harmless, since a phone copy wins in the planner. Settings only counts it. */
    private suspend fun forgetRemoved(key: ArchiveKey) {
        try {
            pruned.remove(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("janitor: removed record drop failed: ${e::class.simpleName}")
        }
    }

    /** Deletes [path] only when it lies under a Scratch or Rebuildable root. Returns whether it did. */
    private suspend fun sweep(path: String): Boolean {
        val cls = roots.classOf(path)
        if (cls != StorageClass.Scratch && cls != StorageClass.Rebuildable) {
            report = report.copy(refused = report.refused + 1)
            return false
        }
        return try {
            withContext(io) { tree.delete(path) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            report = report.copy(failures = report.failures + 1)
            false
        }
    }

    private suspend fun list(dir: String): List<StorageEntry> = withContext(io) { tree.list(dir) }

    private suspend inline fun step(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report = report.copy(failures = report.failures + 1)
            log("storage janitor step failed: ${e::class.simpleName}")
        }
    }

    companion object {
        /** A temp file this old belongs to no live write. Derived builds stream into theirs for minutes. */
        const val TEMP_AGE_MS: Long = 60L * 60_000L

        /** A partial of a station the phone does not know is kept this long, in case its first sync is running. */
        const val UNKNOWN_STATION_AGE_MS: Long = 60L * 60_000L

        /** A partial untouched this long will not be resumed. The device still has the file. */
        const val STALE_PARTIAL_AGE_MS: Long = 30L * 24L * 60L * 60_000L

        /** `sourceDevice` of a record the janitor wrote for an orphan final. */
        const val ADOPTED_SOURCE: String = "adopted"

        private const val LEGACY_DERIVED_PREFIX = ".derived-"
        private val LEGACY_UUID_TEMP = Regex(""".+\.[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\.tmp$""")
    }
}
