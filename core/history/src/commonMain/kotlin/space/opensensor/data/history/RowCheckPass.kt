package space.opensensor.data.history

import space.opensensor.model.RowCheck

/**
 * The [RowCheck] inputs of one thing-day, captured while [DailyParquetBuilder.pass] times the rows,
 * so no source is decoded twice (#91). Rows are grouped by check, each group runs once over its rows
 * in timeline order, and the flags go back to each kept row. With an [aggregator] each flagged row is
 * also marked there, for the tier companions. Raw values are only read.
 */
internal class RowCheckPass(private val aggregator: Aggregator?) {
    private val groups = LinkedHashMap<RowCheck, Group>()

    /** One check's rows. [kept] is each row's kept index, [values] its check columns, [clean] its cleaned metric values. */
    class Group(val check: RowCheck, cleaned: Int) {
        var size = 0
        var kept = IntArray(INITIAL)
        val values = Array(check.columns.size) { DoubleArray(INITIAL) }
        val clean = Array(cleaned) { DoubleArray(INITIAL) }

        /** Drops the rows of a failed source, every row at or after kept index [first]. */
        fun truncate(first: Int) {
            while (size > 0 && kept[size - 1] >= first) size--
        }

        fun grow() {
            val c = kept.size * 2
            kept = kept.copyOf(c)
            for (k in values.indices) values[k] = values[k].copyOf(c)
            for (k in clean.indices) clean[k] = clean[k].copyOf(c)
        }
    }

    /** The reader of one source's check columns, null when [check] is null. */
    class Source(val group: Group, val columns: List<DoubleColumn?>)

    /** The group of [check] for a source whose [column] reads its decoded columns, null without a check. */
    fun source(check: RowCheck?, column: (String) -> DoubleColumn?): Source? {
        if (check == null) return null
        val group = groups.getOrPut(check) { Group(check, aggregator?.cleaned?.size ?: 0) }
        return Source(group, check.columns.map(column))
    }

    /** Captures row [i] of [source], kept at index [keptIndex], and its cleaned metric values from [tier]. */
    fun add(source: Source, keptIndex: Int, i: Int, tier: Aggregator.Columns?) {
        val g = source.group
        if (g.size == g.kept.size) g.grow()
        val at = g.size++
        g.kept[at] = keptIndex
        for (k in source.columns.indices) {
            val col = source.columns[k]
            g.values[k][at] = if (col != null && col.has(i)) col.values[i] else Double.NaN
        }
        if (tier != null && aggregator != null) {
            for (j in g.clean.indices) g.clean[j][at] = aggregator.valueOf(tier, aggregator.cleaned[j], i)
        }
    }

    /** Forgets the rows of a failed source, from kept index [first] on. */
    fun truncate(first: Int) {
        for (g in groups.values) g.truncate(first)
    }

    /** The checks that read any kept row, in first use order. */
    val checks: List<RowCheck> get() = groups.values.filter { it.size > 0 }.map { it.check }

    /**
     * Runs every check and returns each kept row's flags, -1 for a row no check read, or null when no
     * check read a row. [keptUtc] holds the kept rows' timeline instants in nanoseconds.
     */
    fun flags(keptUtc: LongArray, kept: Int): IntArray? {
        if (groups.values.none { it.size > 0 }) return null
        val out = IntArray(kept) { -1 }
        for (g in groups.values) {
            if (g.size == 0) continue
            val times = LongArray(g.size) { keptUtc[g.kept[it]] }
            val order = IntArray(g.size) { it }
            DailyParquetBuilder.sortByTimeline(order, times)
            val timeMs = LongArray(g.size) { times[order[it]].floorDiv(NS_PER_MS) }
            val values = g.values.map { v -> DoubleArray(g.size) { v[order[it]] } }
            val flags = g.check.flags(timeMs, values)
            for (p in 0 until g.size) {
                val e = order[p]
                val k = g.kept[e]
                out[k] = flags[p]
                if (flags[p] != 0) aggregator?.flag(keptUtc[k], flags[p]) { j -> g.clean[j][e] }
            }
        }
        return out
    }

    private companion object {
        const val INITIAL = 256
        const val NS_PER_MS = 1_000_000L
    }
}
