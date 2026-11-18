package space.opensensor.archive

import space.opensensor.archive.IosFiles.Kind
import space.opensensor.data.history.parquet.LocalFileReader

/**
 * Every regular file under [root] as an [ExportEntry] named `<prefix>/<relative path>`, for the daily
 * export of the derived files in `Library/Caches/derived/v1` (#301). The names match the JVM
 * `directoryEntries` of Android. A link is never listed or followed. A file gone by the time the zip
 * opens it throws [ArchiveFileMissingException], so the zip leaves it out (#168).
 */
fun directoryEntries(root: String, prefix: String): List<ExportEntry> {
    val base = root.trimEnd('/')
    return IosFiles.walk(base).mapNotNull { relative ->
        val path = "$base/$relative"
        if (IosFiles.kind(path) != Kind.FILE) return@mapNotNull null
        ExportEntry("$prefix/$relative", IosFiles.modifiedEpochMs(path) ?: 0L) { openListed(path) }
    }
}

/**
 * Removes each `day=….manifest.json` under the derived [root] whose `day=….parquet` is gone, the mark
 * the derived quota or an iOS purge leaves (`QuotaEnforcer`). The next derive pass then builds that day
 * again, as Android's `ensureDailyFiles` does before its daily export. Returns how many it removed.
 */
fun forgetEvictedDays(root: String): Int {
    val base = root.trimEnd('/')
    var removed = 0
    for (relative in IosFiles.walk(base)) {
        if (!relative.endsWith(MANIFEST) || !relative.substringAfterLast('/').startsWith("day=")) continue
        val manifest = "$base/$relative"
        if (IosFiles.kind(manifest) != Kind.FILE) continue
        val day = manifest.removeSuffix(MANIFEST) + ".parquet"
        if (IosFiles.kind(day) == Kind.ABSENT && IosFiles.isGone(day)) {
            IosFiles.remove(manifest)
            removed++
        }
    }
    return removed
}

private const val MANIFEST = ".manifest.json"

private fun openListed(path: String): LocalFileReader {
    when (IosFiles.kind(path)) {
        Kind.FILE -> Unit
        Kind.ABSENT -> if (IosFiles.isGone(path)) throw ArchiveFileMissingException("derived file is gone") else error("derived file cannot be read")
        else -> error("derived file is not a file")
    }
    return LocalFileReader(path)
}
