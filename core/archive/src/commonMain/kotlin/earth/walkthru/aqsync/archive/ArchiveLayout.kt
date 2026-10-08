package earth.walkthru.aqsync.archive

import earth.walkthru.aqsync.data.protocol.SafeName
import kotlinx.datetime.LocalDate

/**
 * The one on-phone archive layout, as paths relative to a platform root (Android `filesDir`, iOS
 * `Application Support`). Both apps build every archive, partial and derived path here.
 *
 * ```
 * archive/<station>/<hive name>                         verified, immutable copies
 * archive-partial/<station>/<hive name>.partial         in-progress download, absolute offsets
 * archive-partial/<station>/<hive name>.partial.meta    "<size> <crc32 decimal>" from the OPENED frame
 * derived/v1/<station>/day=YYYY-MM-DD.parquet           derived daily file, a cache of the archive
 * derived/v1/<station>/day=YYYY-MM-DD.manifest.json     its manifest
 * ```
 *
 * The Hive name is kept verbatim below the station directory, so the archive mirrors the microSD
 * card and `tools/export_parquet.py`. A file reaches `archive/` only by an atomic rename after
 * [ArchiveVerifier] passed. The "is synced" receipt is the [ArchiveIndex] record, never a file.
 *
 * This is Android's layout (`FileArchive`, `DerivedStore`). The iOS app today keeps finals under
 * [LEGACY_IOS_ROOT], partials beside them as `<name>.<size>-<crc hex>.partial`, and a
 * [LEGACY_RECEIPT_SUFFIX] receipt per file. Those names are listed here only so the iOS move onto
 * this layout (platform parity plan, wave 3) recognises them.
 */
object ArchiveLayout {
    /** Verified copies. */
    const val ARCHIVE: String = "archive"

    /** Resumable downloads and their metadata, never inside [ARCHIVE]. */
    const val PARTIAL: String = "archive-partial"

    /** Derived daily files, a cache rebuilt from [ARCHIVE]. */
    const val DERIVED: String = "derived/v1"

    const val PARTIAL_SUFFIX: String = ".partial"
    const val META_SUFFIX: String = ".partial.meta"

    /** The iOS archive root before the move, under Application Support. */
    const val LEGACY_IOS_ROOT: String = "SensorArchive"

    /** The iOS per-file receipt before the move, `<hive name>.verified.json` holding `{size, crc}`. */
    const val LEGACY_RECEIPT_SUFFIX: String = ".verified.json"

    /**
     * Station ids become one directory name. `[0-9A-Za-z._-]+`, never `.` or `..`. The dot is allowed
     * because the firmware name whitelist has it (plan D2).
     */
    fun isSafeStation(station: String): Boolean {
        if (station.isEmpty() || station == "." || station == "..") return false
        for (c in station) if (!(c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z' || c == '.' || c == '_' || c == '-')) return false
        return true
    }

    /**
     * True for a Hive name an archive may hold. The firmware's own rule ([SafeName]) plus no `.`
     * segment, which the iOS archive also refuses, so `a/./b.parquet` cannot alias `a/b.parquet`.
     * Partials, metas and receipts never pass.
     */
    fun isArchiveName(name: String): Boolean = SafeName.isValid(name) && !name.startsWith("./") && !name.contains("/./")

    fun stationDir(station: String): String = "$ARCHIVE/${checkedStation(station)}"
    fun partialStationDir(station: String): String = "$PARTIAL/${checkedStation(station)}"

    fun finalPath(key: ArchiveKey): String = "$ARCHIVE/${key.station}/${key.name}"
    fun partialPath(key: ArchiveKey): String = "$PARTIAL/${key.station}/${key.name}$PARTIAL_SUFFIX"
    fun partialMetaPath(key: ArchiveKey): String = "$PARTIAL/${key.station}/${key.name}$META_SUFFIX"

    /** The Hive name of a partial, from its path relative to [partialStationDir], or null for anything else (a `.meta`, a stray file). */
    fun nameOfPartial(relative: String): String? =
        relative.takeIf { it.endsWith(PARTIAL_SUFFIX) }?.removeSuffix(PARTIAL_SUFFIX)?.takeIf(::isArchiveName)

    /** The partial metadata line, `"<size> <crc32>"`, both decimal. */
    fun encodeMeta(meta: PartialMeta): String = "${meta.size} ${meta.crc32}"

    /** Reads [encodeMeta] output. Null for anything malformed, which resets the partial. */
    fun decodeMeta(text: String): PartialMeta? {
        val parts = text.trim().split(' ')
        if (parts.size != 2) return null
        val size = parts[0].toLongOrNull() ?: return null
        val crc = parts[1].toLongOrNull() ?: return null
        if (size < 0 || crc < 0 || crc > 0xFFFF_FFFFL) return null
        return PartialMeta(size, crc)
    }

    /** `YYYY-MM-DD` of a UTC epoch day, the label in derived file names and manifests. */
    fun dayLabel(epochDay: Long): String = LocalDate.fromEpochDays(epochDay).toString()

    fun derivedStationDir(station: String): String = "$DERIVED/${checkedStation(station)}"
    fun derivedDayPath(station: String, epochDay: Long): String = "${derivedStationDir(station)}/day=${dayLabel(epochDay)}.parquet"
    fun derivedManifestPath(station: String, epochDay: Long): String = "${derivedStationDir(station)}/day=${dayLabel(epochDay)}.manifest.json"

    private fun checkedStation(station: String): String {
        require(isSafeStation(station)) { "unsafe station id: $station" }
        return station
    }
}

/** One archive file: the station directory and the Hive name below it. Both are checked once, here. */
data class ArchiveKey(val station: String, val name: String) {
    init {
        require(ArchiveLayout.isSafeStation(station)) { "unsafe station id: $station" }
        require(ArchiveLayout.isArchiveName(name)) { "unsafe archive name" }
    }
}

/** What the device's OPENED frame promised for a file. A partial resumes only against the same pair. */
data class PartialMeta(val size: Long, val crc32: Long)
