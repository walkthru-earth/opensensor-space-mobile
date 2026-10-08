package earth.walkthru.aqsync.data.history

/**
 * The identity of one logged row, used to drop the copy that a FLUSH file or a second sync of the same
 * window puts in another file. The tiers ([Aggregator]), the day chart and the derived daily file all
 * de-duplicate with this one key, so their row counts agree (docs/history-pipeline.md).
 *
 * A row with a `sequence` is `(boot, sequence)`. A row without one (older files, or a null cell) is
 * `(boot, timeline ns)`, where the timeline ns is the row's UTC or its estimate. A missing boot half
 * counts as 0. The two kinds never match each other.
 */
data class RowKey(val bootHi: Long, val bootLo: Long, val bySequence: Boolean, val value: Long) {
    companion object {
        fun of(bootHi: Long?, bootLo: Long?, sequence: Long?, timelineNs: Long): RowKey =
            if (sequence != null) RowKey(bootHi ?: 0L, bootLo ?: 0L, true, sequence) else RowKey(bootHi ?: 0L, bootLo ?: 0L, false, timelineNs)
    }
}

/** The rows seen so far in one pass. [firstSight] is true once per [RowKey]. */
class RowKeys {
    private val seen = HashSet<RowKey>()

    fun firstSight(bootHi: Long?, bootLo: Long?, sequence: Long?, timelineNs: Long): Boolean = seen.add(RowKey.of(bootHi, bootLo, sequence, timelineNs))
}
