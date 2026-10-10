package earth.walkthru.aqsync.storage

import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.archive.ArchiveVerifier
import earth.walkthru.aqsync.data.protocol.ParquetVerify
import earth.walkthru.aqsync.data.protocol.Sha256
import earth.walkthru.aqsync.data.protocol.toHex
import earth.walkthru.aqsync.data.sync.ArchiveRecordIndexer
import earth.walkthru.aqsync.sync.nowEpochMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The startup sweep of #60, run once per process on a background dispatcher (Android through a
 * one-time WorkManager job, iOS from `IosArchive.startIndexRepair`). Idempotent and cheap when there
 * is nothing to do.
 *
 * 1. Temp files of [AtomicFiles] older than [TEMP_AGE_MS], and the older builds' derived temps.
 * 2. Export runs older than [EXPORT_AGE_MS].
 * 3. Partials of a station the phone does not know, after [UNKNOWN_STATION_AGE_MS], and any partial
 *    untouched for [STALE_PARTIAL_AGE_MS]. A fresh partial of a known station is kept for resume.
 * 4. Final files without an index record, left by a crash between the commit and the record. Each is
 *    hashed (size, CRC-32, SHA-256) and checked for `PAR1`, then indexed as adopted.
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
) {
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
        /** Steps or files that threw. The rest of the sweep went on. */
        val failures: Int = 0,
    ) {
        fun describe(): String =
            "storage janitor: swept $tempsSwept temp, $exportsSwept export, $partialsSwept partial, kept $partialsKept partial, " +
                "adopted $adopted, unverifiable $unverifiable, refused $refused, failures $failures"
    }

    private var report = Report()

    suspend fun run(): Report {
        report = Report()
        val now = clock()
        step { sweepTemps(now) }
        step { sweepExports(now) }
        step { sweepPartials(now) }
        step { adoptOrphans() }
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

    private suspend fun sweepExports(now: Long) {
        val dir = roots[StorageRole.Exports] ?: return
        for (entry in list(dir)) {
            if ('/' in entry.path || !entry.path.startsWith(EXPORT_RUN_PREFIX)) continue
            if (now - entry.modifiedEpochMs >= EXPORT_AGE_MS) {
                if (sweep("$dir/${entry.path}")) report = report.copy(exportsSwept = report.exportsSwept + 1)
            }
        }
    }

    private suspend fun sweepPartials(now: Long) {
        val dir = roots[StorageRole.Partials] ?: return
        val known = (index.stations() + knownStations()).toSet()
        // A partial and its meta form one download. Its age is the newest of the two.
        val downloads = list(dir).filter { !it.isDirectory }.groupBy { it.path.removeSuffix(".meta") }
        for ((download, files) in downloads) {
            val station = download.substringBefore('/', missingDelimiterValue = "")
            val age = now - files.maxOf { it.modifiedEpochMs }
            val stale = age >= STALE_PARTIAL_AGE_MS || (station !in known && age >= UNKNOWN_STATION_AGE_MS)
            if (!stale) {
                report = report.copy(partialsKept = report.partialsKept + 1)
                continue
            }
            var swept = false
            for (file in files) swept = sweep("$dir/${file.path}") || swept
            if (swept) report = report.copy(partialsSwept = report.partialsSwept + 1)
        }
    }

    private suspend fun adoptOrphans() {
        for ((station, keys) in store.listFinal().groupBy { it.station }) {
            val indexed = index.observeStation(station).first().mapTo(HashSet()) { it.name }
            for (key in keys) {
                if (key.name in indexed) continue
                step { adopt(key) }
            }
        }
    }

    private suspend fun adopt(key: ArchiveKey) {
        val hashed = withContext(io) {
            val reader = store.reader(key)
            val sha = Sha256()
            val crc = ArchiveVerifier.crc32(reader) { bytes, n -> sha.update(bytes, 0, n) }
            val n = ParquetVerify.MAGIC.size
            val magic = reader.size >= 2L * n && ParquetVerify.hasMagic(reader.read(0, n), reader.read(reader.size - n, n))
            if (magic) Triple(reader.size, crc, sha.digest().toHex()) else null
        }
        if (hashed == null) {
            report = report.copy(unverifiable = report.unverifiable + 1)
            return
        }
        // The engine may have written the record while this file was hashed. Its record wins.
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
        index.put(indexer.indexed(record))
        report = report.copy(adopted = report.adopted + 1)
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
        /** A temp file this old belongs to no live write. */
        const val TEMP_AGE_MS: Long = 60_000L

        /** An export run this old has left the share sheet. */
        const val EXPORT_AGE_MS: Long = 60L * 60_000L

        /** A partial of a station the phone does not know is kept this long, in case its first sync is running. */
        const val UNKNOWN_STATION_AGE_MS: Long = 60L * 60_000L

        /** A partial untouched this long will not be resumed. The device still has the file. */
        const val STALE_PARTIAL_AGE_MS: Long = 30L * 24L * 60L * 60_000L

        /** `sourceDevice` of a record the janitor wrote for an orphan final. */
        const val ADOPTED_SOURCE: String = "adopted"

        private const val EXPORT_RUN_PREFIX = "run-"
        private const val LEGACY_DERIVED_PREFIX = ".derived-"
        private val LEGACY_UUID_TEMP = Regex(""".+\.[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\.tmp$""")
    }
}
