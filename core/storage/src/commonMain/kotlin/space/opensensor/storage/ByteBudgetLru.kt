package space.opensensor.storage

/**
 * A least recently used map bounded by an estimated byte budget (#61). [sizeOf] estimates what one
 * entry keeps alive. A [get] or [put] makes the entry the most recent, and [put] evicts the least
 * recent entries until the total is within [budgetBytes]. An entry larger than the whole budget is
 * not kept. Not thread safe, callers hold their own lock.
 */
class ByteBudgetLru<K, V>(
    val budgetBytes: Long,
    private val sizeOf: (K, V) -> Long,
) {
    init {
        require(budgetBytes >= 0) { "budget must not be negative" }
    }

    // A LinkedHashMap keeps insertion order on every platform, so a touch is a remove and a put.
    private val entries = LinkedHashMap<K, Pair<V, Long>>()

    /** Estimated bytes of every entry now. */
    var sizeBytes: Long = 0
        private set

    val size: Int get() = entries.size

    val keys: Set<K> get() = entries.keys

    operator fun get(key: K): V? {
        val entry = entries.remove(key) ?: return null
        entries[key] = entry
        return entry.first
    }

    operator fun set(key: K, value: V) = put(key, value)

    fun put(key: K, value: V) {
        remove(key)
        val bytes = sizeOf(key, value).coerceAtLeast(0)
        if (bytes > budgetBytes) return
        entries[key] = value to bytes
        sizeBytes += bytes
        trimTo(budgetBytes)
    }

    fun remove(key: K): V? {
        val entry = entries.remove(key) ?: return null
        sizeBytes -= entry.second
        return entry.first
    }

    fun removeAll(predicate: (K) -> Boolean) {
        for (key in entries.keys.filter(predicate)) remove(key)
    }

    /** Evicts least recent entries until the total is at most [bytes]. */
    fun trimTo(bytes: Long) {
        val iterator = entries.entries.iterator()
        while (sizeBytes > bytes && iterator.hasNext()) {
            sizeBytes -= iterator.next().value.second
            iterator.remove()
        }
    }

    fun clear() {
        entries.clear()
        sizeBytes = 0
    }

    /** Moderate trims to half the budget, Critical clears. */
    fun trim(level: MemoryPressure.Level) = when (level) {
        MemoryPressure.Level.Moderate -> trimTo(budgetBytes / 2)
        MemoryPressure.Level.Critical -> clear()
    }
}
