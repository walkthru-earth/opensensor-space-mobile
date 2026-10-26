package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.history.parquet.ParquetFile
import earth.walkthru.aqsync.data.history.parquet.ParquetFileMetadata

/**
 * The `opensensor` footer of a raw file, or null when it has none the app can trust. Every footer
 * value the app reads (station, purpose, digests and cadence) comes from here. No unprefixed footer
 * key is read.
 */
val ParquetFileMetadata.footer: OpenSensorFooter? get() = OpenSensorFooter.of(keyValueMetadata)

/**
 * The boot of this file's rows, the first row of the first row group that holds both `boot_id_hi` and
 * `boot_id_lo`, as 32 lowercase hex digits. That is the text firmware writes as `firmware.boot_id` and
 * sends in its clock receipts, so the app takes the boot from the rows and not from the footer. Null
 * when the file has no such row.
 */
fun ParquetFile.rowBootId(): String? {
    if (rowGroupCount == 0) return null
    val hiLeaf = metadata.leaf("boot_id_hi") ?: return null
    val loLeaf = metadata.leaf("boot_id_lo") ?: return null
    val hi = LongColumn.of(readColumn(hiLeaf, 0))
    val lo = LongColumn.of(readColumn(loLeaf, 0))
    for (i in 0 until minOf(hi.size, lo.size)) {
        if (hi.has(i) && lo.has(i)) return bootHex(hi.values[i], lo.values[i])
    }
    return null
}

/** [hi] then [lo] as unsigned 16 digit lowercase hex, the firmware `%016llx%016llx` boot text. */
internal fun bootHex(hi: Long, lo: Long): String =
    hi.toULong().toString(16).padStart(16, '0') + lo.toULong().toString(16).padStart(16, '0')
