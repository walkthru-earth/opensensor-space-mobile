package space.opensensor.data.history

import space.opensensor.archive.ArchiveRecord
import space.opensensor.data.protocol.ParquetVerify
import space.opensensor.data.protocol.sha256Hex

/**
 * The identity of one derived thing-day on both phones (#304). Android records it in
 * `derived_day.sourceSignature` and the daily manifest, iOS keeps its saved tiers under it, and the
 * iOS daily manifest starts from the same [VERSION]. A change to what a day holds bumps [VERSION]
 * once and every derived day on either phone rebuilds.
 */
object DeriveSignature {
    /**
     * The derive rules. 2 = per-metric rows, 3 = shared RowKey, 4 = single pass, 5 = canonical
     * duplicate source order, 6 = MICROS UTC and field_id in the daily file, 7 = the corrected tier
     * of a derived metric, 8 = template datastreams and the footer sample interval, 9 = the app_day
     * entry in the daily file, 10 = the resolved template in the signature.
     */
    const val VERSION = 10

    /** The salt line every signature starts with. */
    const val SALT = "derive-v$VERSION"

    /**
     * SHA-256, lower-case hex, over [SALT] and the sorted [ArchiveHistoryTiers.sourceLine]s of
     * [files], one per line. A new anchor re-dates a file and a newly resolved template gives it
     * metrics, so both are part of the identity, and the order of [files] is not.
     */
    fun of(files: List<ArchiveRecord>, templates: FileTemplates, checks: RowChecks = RowChecks.INSTALLED): String {
        val text = buildString {
            append(SALT).append('\n')
            files.map { ArchiveHistoryTiers.sourceLine(it, templates, checks) }.sorted().forEach { append(it).append('\n') }
        }
        return ParquetVerify.sha256Hex(text.encodeToByteArray())
    }
}
