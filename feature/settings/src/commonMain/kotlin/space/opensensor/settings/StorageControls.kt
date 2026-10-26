package space.opensensor.settings

/** Bytes this app keeps on the phone, by what may be deleted (#61). */
data class StorageUsage(
    /** Synced sensor files, their receipts and clock anchors. Only old format files can be removed, by the user. */
    val sensorFilesBytes: Long,
    /** Daily History files, rebuilt from the sensor files when missing. */
    val historyCacheBytes: Long,
    /** Basemap tiles, downloaded again when the map needs them. */
    val mapCacheBytes: Long,
    /** Partial downloads, temporary files and export runs. */
    val temporaryBytes: Long,
)

/** UTC epoch days `[fromEpochDay, toEpochDay]`, both ends included, as the date range picker gives them. */
data class OldFilesRange(val fromEpochDay: Long, val toEpochDay: Long) {
    init {
        require(fromEpochDay <= toEpochDay) { "range ends before it starts" }
    }
}

/**
 * The dry run of an old format cleanup. [skipped] counts files whose footer could not be read, which
 * are kept. [handle] is the host's own plan, given back to [StorageControls.removeOldFiles] so only the
 * files counted here can be removed.
 */
data class OldFilesPlan(
    val files: Int,
    val bytes: Long,
    val stations: Int,
    val range: OldFilesRange?,
    val skipped: Int = 0,
    val handle: Any? = null,
)

/**
 * What a cleanup did. [kept] files stayed (no longer old, changed or gone since the count), [failed]
 * files could not be deleted. [rebuildFailed] is true when the files went but their History days were
 * not rebuilt yet.
 */
data class OldFilesResult(
    val removed: Int,
    val bytes: Long,
    val kept: Int,
    val failed: Int = 0,
    val rebuildFailed: Boolean = false,
)

/**
 * What the Settings Storage card measures and clears. The host wires its archive roots, derived quota
 * and map cache. Clearing touches rebuildable caches only. Old format sensor files leave the phone
 * only through [removeOldFiles], after the user confirms. Sync skips a removed file until
 * [downloadRemovedAgain]. Nothing here ever reaches a device.
 */
interface StorageControls {
    suspend fun usage(): StorageUsage

    suspend fun clearHistoryCache()

    suspend fun clearMapCache()

    /** The UTC days the phone archive spans, or null when it holds no dated file. Changes nothing. */
    suspend fun archiveDays(): OldFilesRange?

    /** The old format files in [range], or in the whole phone archive when null. Changes nothing. */
    suspend fun findOldFiles(range: OldFilesRange?): OldFilesPlan

    /** Removes the files of [plan] from this phone, then rebuilds the History days they fed. */
    suspend fun removeOldFiles(plan: OldFilesPlan): OldFilesResult

    /** How many removed files sync skips. */
    suspend fun removedFiles(): Int

    /** Forgets the removed files, so the next sync downloads them again when a sensor still has them. */
    suspend fun downloadRemovedAgain()
}

/** A byte count in megabytes with one decimal, the unit the Storage card uses. */
internal fun megabytes(bytes: Long): String {
    val tenths = (bytes.coerceAtLeast(0) * 10 + 524_288) / 1_048_576
    return "${tenths / 10}.${tenths % 10}"
}

/**
 * The days the date picker offers, the days of [archive] plus one on each side. Null offers every day.
 * The picker never returns a day the archive cannot hold, nor one whose milliseconds overflow.
 */
internal fun pickableDays(archive: OldFilesRange?): LongRange? = archive?.let { (it.fromEpochDay - 1)..(it.toEpochDay + 1) }

/** The proleptic Gregorian year of a UTC epoch day. */
internal fun yearOf(epochDay: Long): Int = civil(epochDay).first.toInt()

/** A UTC epoch day as `yyyy-mm-dd`, the proleptic Gregorian date of the date picker. */
internal fun isoDate(epochDay: Long): String {
    val (year, month, day) = civil(epochDay)
    return "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
}

/** Year, month and day of a UTC epoch day. */
private fun civil(epochDay: Long): Triple<Long, Long, Long> {
    // Days to civil date (Howard Hinnant), valid for every Long day the picker can return.
    val z = epochDay + 719_468
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = yoe + era * 400 + if (month <= 2) 1 else 0
    return Triple(year, month, day)
}
