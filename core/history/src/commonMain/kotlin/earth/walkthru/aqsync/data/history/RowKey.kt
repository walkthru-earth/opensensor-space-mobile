package earth.walkthru.aqsync.data.history

/**
 * The identity of one logged row, used to drop the copy that a FLUSH file or a second sync of the same
 * window puts in another file. The tiers ([Aggregator]), the day chart and the derived daily file all
 * de-duplicate with this one key, so their row counts agree (docs/history-pipeline.md).
 *
 * A row with a `sequence` is `(boot, sequence)`. A row without one (older files, or a null cell) is
 * `(boot, timeline ns)`, where the timeline ns is the row's UTC or its estimate. A missing boot half
 * counts as 0. The two kinds never match each other.
 *
 * [RowKeys] stores the same four fields in primitive arrays, this class names the identity for tests
 * and docs.
 */
data class RowKey(val bootHi: Long, val bootLo: Long, val bySequence: Boolean, val value: Long) {
    companion object {
        fun of(bootHi: Long?, bootLo: Long?, sequence: Long?, timelineNs: Long): RowKey =
            if (sequence != null) RowKey(bootHi ?: 0L, bootLo ?: 0L, true, sequence) else RowKey(bootHi ?: 0L, bootLo ?: 0L, false, timelineNs)

        /** The 64-bit hash [RowKeys] probes with. Equal keys hash equally, unequal keys may collide. */
        fun hash(bootHi: Long, bootLo: Long, bySequence: Boolean, value: Long): Long {
            var h = mix(bootHi + GOLDEN)
            h = mix(h xor bootLo)
            h = mix(h xor value)
            return if (bySequence) h.inv() else h
        }

        private const val GOLDEN = -0x61c8864680b583ebL

        // The 64-bit finaliser of MurmurHash3.
        private fun mix(x: Long): Long {
            var z = x
            z = (z xor (z ushr 33)) * -0xae502812aa7333L
            z = (z xor (z ushr 33)) * -0x3b314601e57a13adL
            return z xor (z ushr 33)
        }
    }
}

/**
 * The rows seen so far in one pass. [firstSight] is true once per [RowKey]. An open-addressing set of
 * 64-bit hashes with the key fields in parallel `LongArray`s, so a row costs no object. A hash match is
 * confirmed field by field, a collision of two different rows keeps both.
 */
class RowKeys internal constructor(private val hasher: (Long, Long, Boolean, Long) -> Long) {
    constructor() : this(RowKey::hash)

    private var hashes = LongArray(INITIAL)
    private var his = LongArray(INITIAL)
    private var los = LongArray(INITIAL)
    private var values = LongArray(INITIAL)

    /** 0 is an empty slot, [TIME] and [SEQUENCE] say which kind of key the slot holds. */
    private var kinds = ByteArray(INITIAL)
    private var mask = INITIAL - 1

    /** Distinct keys held. */
    var size: Int = 0
        private set

    fun firstSight(bootHi: Long?, bootLo: Long?, sequence: Long?, timelineNs: Long): Boolean =
        if (sequence != null) firstSight(bootHi ?: 0L, bootLo ?: 0L, true, sequence) else firstSight(bootHi ?: 0L, bootLo ?: 0L, false, timelineNs)

    /** The unboxed form of [firstSight] for callers that read the columns as primitive arrays. */
    fun firstSight(bootHi: Long, bootLo: Long, bySequence: Boolean, value: Long): Boolean {
        val kind = if (bySequence) SEQUENCE else TIME
        val h = hasher(bootHi, bootLo, bySequence, value)
        var i = slot(h)
        while (true) {
            val k = kinds[i]
            if (k == EMPTY) break
            if (hashes[i] == h && k == kind && values[i] == value && los[i] == bootLo && his[i] == bootHi) return false
            i = (i + 1) and mask
        }
        hashes[i] = h
        his[i] = bootHi
        los[i] = bootLo
        values[i] = value
        kinds[i] = kind
        if (++size * 4 > hashes.size * 3) grow()
        return true
    }

    private fun slot(h: Long): Int = (h xor (h ushr 32)).toInt() and mask

    private fun grow() {
        val oldHashes = hashes
        val oldHis = his
        val oldLos = los
        val oldValues = values
        val oldKinds = kinds
        val capacity = oldHashes.size * 2
        hashes = LongArray(capacity)
        his = LongArray(capacity)
        los = LongArray(capacity)
        values = LongArray(capacity)
        kinds = ByteArray(capacity)
        mask = capacity - 1
        for (j in oldHashes.indices) {
            if (oldKinds[j] == EMPTY) continue
            var i = slot(oldHashes[j])
            while (kinds[i] != EMPTY) i = (i + 1) and mask
            hashes[i] = oldHashes[j]
            his[i] = oldHis[j]
            los[i] = oldLos[j]
            values[i] = oldValues[j]
            kinds[i] = oldKinds[j]
        }
    }

    private companion object {
        const val INITIAL = 64
        const val EMPTY: Byte = 0
        const val TIME: Byte = 1
        const val SEQUENCE: Byte = 2
    }
}
