package earth.walkthru.aqsync.archive

/**
 * One verified file in the phone archive, the plain form of Android's `archived_file` row (Room v10)
 * that both phones store behind [ArchiveIndex]. A record exists only after [ArchiveVerifier] passed
 * and the file was renamed into place, so a record is the "is synced" receipt.
 *
 * The index columns (schema v4) are null until the file is indexed, and the estimate columns
 * (schema v6) are set only for files without UTC rows whose boot later got a time anchor.
 */
data class ArchiveRecord(
    val station: String,
    /** Relative Hive path exactly as the device reported it. */
    val name: String,
    val bytes: Long,
    /** CRC-32 (IEEE) as an unsigned 32-bit value. */
    val crc32: Long,
    /** Lowercase hex SHA-256 of the file. */
    val sha256: String,
    val syncedAtEpochMs: Long,
    /** Bluetooth address, LAN host or fake id of the device the copy came from. */
    val sourceDevice: String,
    val indexedAtEpochMs: Long? = null,
    /** Why indexing failed, else null. The file stays in the archive either way. */
    val indexError: String? = null,
    val rows: Long? = null,
    val rowGroups: Int? = null,
    val schemaVersion: String? = null,
    val bootId: String? = null,
    /** `telemetry` for station data. Other purposes (benchmark copies) stay out of history. */
    val purpose: String? = null,
    val utcRows: Long? = null,
    val firstUtcNs: Long? = null,
    val lastUtcNs: Long? = null,
    val firstMonoUs: Long? = null,
    val lastMonoUs: Long? = null,
    val firstSeq: Long? = null,
    val lastSeq: Long? = null,
    val pmValidRows: Long? = null,
    val estFirstUtcNs: Long? = null,
    val estLastUtcNs: Long? = null,
    val estAnchorFile: String? = null,
) {
    val key: ArchiveKey get() = ArchiveKey(station, name)

    val indexed: Boolean get() = indexedAtEpochMs != null && indexError == null

    /** Station data, the only purpose history reads. */
    val isTelemetry: Boolean get() = purpose == null || purpose == TELEMETRY

    /** The file has real UTC rows. */
    val hasUtc: Boolean get() = (utcRows ?: 0L) > 0L

    /** The file is placed on the timeline by an estimate only. */
    val hasEstimatedUtc: Boolean get() = !hasUtc && estFirstUtcNs != null && estLastUtcNs != null

    /** Real or estimated span start and end, whichever the file has. */
    val timelineFirstNs: Long? get() = if (hasUtc) firstUtcNs else estFirstUtcNs
    val timelineLastNs: Long? get() = if (hasUtc) lastUtcNs else estLastUtcNs

    /** The copy matches what the device lists now, so it is synced and need not be read again. */
    fun matches(size: Long, crc32: Long): Boolean = bytes == size && this.crc32 == crc32

    /**
     * A telemetry file on the timeline in `[fromNs, toNs)`, by real UTC or by its estimate. The same
     * predicate as Android's `ArchivedFileDao.withUtcBetween` query, written once for every store.
     */
    fun onTimeline(fromNs: Long, toNs: Long): Boolean =
        indexError == null && isTelemetry &&
            (timelineLastNs ?: Long.MIN_VALUE) >= fromNs &&
            (timelineFirstNs ?: Long.MAX_VALUE) < toNs

    companion object {
        const val TELEMETRY: String = "telemetry"

        /** The order [ArchiveIndex.timeline] returns, by real or estimated first time, unplaced last. */
        val TIMELINE_ORDER: Comparator<ArchiveRecord> = compareBy(nullsLast()) { it.timelineFirstNs }
    }
}
