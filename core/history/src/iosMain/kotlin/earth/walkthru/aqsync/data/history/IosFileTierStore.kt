@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.archive.ArchiveLayout
import earth.walkthru.aqsync.data.history.parquet.LocalFileReader
import earth.walkthru.aqsync.storage.AtomicFiles
import earth.walkthru.aqsync.storage.StorageClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeRegular

/**
 * [TierStore] for iOS (#63): one small binary file per station-day next to the derived day file,
 * `derived/v1/<station>/day=YYYY-MM-DD.tiers` under `Library/Caches`, which iOS may purge. The quota
 * evicts only `.parquet` day files, a purge or a Clear makes the next pass rebuild the tiers.
 */
class IosFileTierStore(root: String, private val atomic: AtomicFiles) : TierStore {
    private val root = root.trimEnd('/')
    private val fm get() = NSFileManager.defaultManager
    private val protection = mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication)

    private fun path(station: String, epochDay: Long) = "$root/${ArchiveLayout.derivedDayPath(station, epochDay).removeSuffix(".parquet")}$SUFFIX"

    override suspend fun load(station: String, epochDay: Long, signature: String): DayTiers? = withContext(Dispatchers.IO) {
        val path = path(station, epochDay)
        if (fm.attributesOfItemAtPath(path, null)?.get(NSFileType) != NSFileTypeRegular) return@withContext null
        try {
            LocalFileReader(path).use { reader ->
                if (reader.size > MAX_BYTES) return@use null
                TierCodec.decode(reader.read(0, reader.size.toInt()), signature)
            }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun save(station: String, epochDay: Long, signature: String, tiers: DayTiers) = withContext(Dispatchers.IO) {
        val path = path(station, epochDay)
        try {
            val directory = path.substringBeforeLast('/')
            check(fm.createDirectoryAtPath(directory, true, protection, null)) { "cannot create tier directory" }
            atomic.writeAtomically(path, TierCodec.encode(signature, tiers), StorageClass.Rebuildable)
        } catch (e: Exception) {
            // A cache write that fails costs a rebuild next time, never the tiers in memory.
            println("tier cache write failed: ${e::class.simpleName}")
        }
    }

    override suspend fun retain(station: String, days: Set<Long>) = withContext(Dispatchers.IO) {
        val directory = "$root/${ArchiveLayout.derivedStationDir(station)}"
        val keep = days.mapTo(HashSet()) { path(station, it).substringAfterLast('/') }
        for (name in fm.contentsOfDirectoryAtPath(directory, null).orEmpty().filterIsInstance<String>()) {
            if (name.startsWith("day=") && name.endsWith(SUFFIX) && name !in keep) fm.removeItemAtPath("$directory/$name", null)
        }
    }

    private companion object {
        const val SUFFIX = ".tiers"

        /** Far above a real day (three tiers of every metric is about 100 KB), a guard against a damaged file. */
        const val MAX_BYTES = 8L * 1024 * 1024
    }
}
