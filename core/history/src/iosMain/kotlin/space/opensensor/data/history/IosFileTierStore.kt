@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package space.opensensor.data.history

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSFileTypeSymbolicLink
import space.opensensor.archive.ArchiveLayout
import space.opensensor.data.history.parquet.LocalFileReader
import space.opensensor.storage.AtomicFiles
import space.opensensor.storage.StorageClass

/**
 * [TierStore] for iOS (#63): one small binary file per thing-day next to the derived day file,
 * `derived/v1/<thing>/day=YYYY-MM-DD.tiers` under `Library/Caches`, which iOS may purge. The quota
 * evicts day files and tiers, a purge or a Clear makes the next pass rebuild the tiers.
 */
class IosFileTierStore(root: String, private val atomic: AtomicFiles) : TierStore {
    private val root = root.trimEnd('/')
    private val fm get() = NSFileManager.defaultManager
    private val protection = mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication)

    private fun path(thing: String, epochDay: Long) = checked("$root/${ArchiveLayout.derivedDayPath(thing, epochDay).removeSuffix(".parquet")}$SUFFIX")

    override suspend fun load(thing: String, epochDay: Long, signature: String): DayTiers? =
        read(thing, epochDay) { TierCodec.decode(it, signature) }

    override suspend fun loadSaved(thing: String, epochDay: Long): SavedDay? = read(thing, epochDay, TierCodec::decodeSaved)

    private suspend fun <T> read(thing: String, epochDay: Long, decode: (ByteArray) -> T?): T? = withContext(Dispatchers.IO) {
        val path = path(thing, epochDay)
        if (fm.attributesOfItemAtPath(path, null)?.get(NSFileType) != NSFileTypeRegular) return@withContext null
        try {
            LocalFileReader(path).use { reader ->
                if (reader.size > MAX_BYTES) return@use null
                decode(reader.read(0, reader.size.toInt()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun savedDays(thing: String): Set<Long> = withContext(Dispatchers.IO) {
        val directory = checked("$root/${ArchiveLayout.derivedThingDir(thing)}")
        fm.contentsOfDirectoryAtPath(directory, null).orEmpty().filterIsInstance<String>().mapNotNullTo(HashSet()) { name ->
            if (!name.startsWith("day=") || !name.endsWith(SUFFIX)) return@mapNotNullTo null
            try {
                LocalDate.parse(name.removePrefix("day=").removeSuffix(SUFFIX)).toEpochDays()
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    override suspend fun save(thing: String, epochDay: Long, signature: String, tiers: DayTiers) = withContext(Dispatchers.IO) {
        val path = path(thing, epochDay)
        try {
            val directory = path.substringBeforeLast('/')
            check(fm.createDirectoryAtPath(directory, true, protection, null)) { "cannot create tier directory" }
            atomic.writeAtomically(path, TierCodec.encode(signature, tiers), StorageClass.Rebuildable)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A cache write that fails costs a rebuild next time, never the tiers in memory.
            println("tier cache write failed: ${e::class.simpleName}")
        }
    }

    override suspend fun retain(thing: String, days: Set<Long>) = withContext(Dispatchers.IO) {
        val directory = checked("$root/${ArchiveLayout.derivedThingDir(thing)}")
        val keep = days.mapTo(HashSet()) { path(thing, it).substringAfterLast('/') }
        for (name in fm.contentsOfDirectoryAtPath(directory, null).orEmpty().filterIsInstance<String>()) {
            if (name.startsWith("day=") && name.endsWith(SUFFIX) && name !in keep) fm.removeItemAtPath(checked("$directory/$name"), null)
        }
    }

    /** Refuses a symlink at the file or any parent, before Foundation or POSIX follows it. */
    private fun checked(path: String): String {
        var current = path
        while (current == root || current.startsWith("$root/")) {
            check(fm.attributesOfItemAtPath(current, null)?.get(NSFileType) != NSFileTypeSymbolicLink) { "refusing a symbolic link in tier files" }
            if (current == root) break
            current = current.substringBeforeLast('/')
        }
        return path
    }

    private companion object {
        const val SUFFIX = ".tiers"

        /** Far above a real day (three tiers of every metric is about 100 KB), a guard against a damaged file. */
        const val MAX_BYTES = 8L * 1024 * 1024
    }
}
