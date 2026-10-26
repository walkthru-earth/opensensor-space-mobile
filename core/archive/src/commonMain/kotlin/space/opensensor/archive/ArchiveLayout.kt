package space.opensensor.archive

import kotlinx.datetime.LocalDate
import space.opensensor.data.protocol.SafeName

/**
 * The one on-phone archive layout, as paths relative to a platform root (Android `filesDir`, iOS
 * `Application Support`). Both apps build every archive, partial and derived path here.
 *
 * ```
 * archive/<thing>/<hive name>                         verified, immutable copies
 * archive-partial/<thing>/<hive name>.partial         in-progress download, absolute offsets
 * archive-partial/<thing>/<hive name>.partial.meta    "<size> <crc32 decimal>" from the OPENED frame
 * derived/v1/<thing>/day=YYYY-MM-DD.parquet           derived daily file, a cache of the archive
 * derived/v1/<thing>/day=YYYY-MM-DD.manifest.json     its manifest
 * ```
 *
 * The Hive name is kept verbatim below the thing directory, so the archive mirrors the microSD
 * card and `tools/export_parquet.py`. A file reaches `archive/` only by an atomic rename after
 * [ArchiveVerifier] passed. The "is synced" receipt is the [ArchiveIndex] record, never a file.
 *
 * This is Android's layout (`FileArchive`, `DerivedStore`). The iOS app keeps finals under
 * [LEGACY_IOS_ROOT]. Older iOS builds kept partials beside them as `<name>.<size>-<crc hex>.partial`
 * and a [LEGACY_RECEIPT_SUFFIX] receipt per file. Those names are listed here only so iOS adopts the
 * partials and imports the receipts into Room once (`LegacyReceiptImport`).
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

    /** The older iOS per-file receipt, `<hive name>.verified.json` holding at least `{size, crc}`. */
    const val LEGACY_RECEIPT_SUFFIX: String = ".verified.json"

    /**
     * Thing ids become one directory name. `[0-9A-Za-z._-]+`, never `.` or `..`. The dot is allowed
     * because the firmware name whitelist has it (plan D2).
     */
    fun isSafeThing(thing: String): Boolean {
        if (thing.isEmpty() || thing == "." || thing == "..") return false
        for (c in thing) if (!(c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z' || c == '.' || c == '_' || c == '-')) return false
        return true
    }

    /**
     * True for a Hive name an archive may hold. The firmware's own rule ([SafeName]) plus no `.`
     * segment, which the iOS archive also refuses, so `a/./b.parquet` cannot alias `a/b.parquet`.
     * Partials, metas and receipts never pass.
     */
    fun isArchiveName(name: String): Boolean = SafeName.isValid(name) && !name.startsWith("./") && !name.contains("/./")

    fun thingDir(thing: String): String = "$ARCHIVE/${checkedThing(thing)}"
    fun partialThingDir(thing: String): String = "$PARTIAL/${checkedThing(thing)}"

    fun finalPath(key: ArchiveKey): String = "$ARCHIVE/${key.thing}/${key.name}"
    fun partialPath(key: ArchiveKey): String = "$PARTIAL/${key.thing}/${key.name}$PARTIAL_SUFFIX"
    fun partialMetaPath(key: ArchiveKey): String = "$PARTIAL/${key.thing}/${key.name}$META_SUFFIX"

    /** The Hive name of a partial, from its path relative to [partialThingDir], or null for anything else (a `.meta`, a stray file). */
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

    fun derivedThingDir(thing: String): String = "$DERIVED/${checkedThing(thing)}"
    fun derivedDayPath(thing: String, epochDay: Long): String = "${derivedThingDir(thing)}/day=${dayLabel(epochDay)}.parquet"
    fun derivedManifestPath(thing: String, epochDay: Long): String = "${derivedThingDir(thing)}/day=${dayLabel(epochDay)}.manifest.json"

    private fun checkedThing(thing: String): String {
        require(isSafeThing(thing)) { "unsafe thing id" }
        return thing
    }
}

/** One archive file: the thing directory and the Hive name below it. Both are checked once, here. */
data class ArchiveKey(val thing: String, val name: String) {
    init {
        require(ArchiveLayout.isSafeThing(thing)) { "unsafe thing id" }
        require(ArchiveLayout.isArchiveName(name)) { "unsafe archive name" }
    }
}

/** What the device's OPENED frame promised for a file. A partial resumes only against the same pair. */
data class PartialMeta(val size: Long, val crc32: Long)
