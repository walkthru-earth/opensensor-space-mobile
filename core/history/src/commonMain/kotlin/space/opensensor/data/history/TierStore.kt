package space.opensensor.data.history

/** One UTC thing-day of tiers: resolution, then metric id, then the buckets in time order. */
typealias DayTiers = Map<Resolution, Map<String, List<TierBucket>>>

/**
 * Keeps each thing-day's tier buckets under its source signature, so a new process reads the tiers
 * without decoding any Parquet (#63). A cache: a missing, stale or unreadable entry is rebuilt from the
 * archive, and nothing here is ever the only copy of anything.
 */
interface TierStore {
    /** The day's tiers when they were saved under [signature], otherwise null. */
    suspend fun load(thing: String, epochDay: Long, signature: String): DayTiers?

    suspend fun save(thing: String, epochDay: Long, signature: String, tiers: DayTiers)

    /** Drops the thing's saved days that are not in [days]. */
    suspend fun retain(thing: String, days: Set<Long>)

    /** The thing's saved days, whatever their signature. */
    suspend fun savedDays(thing: String): Set<Long>

    /**
     * The day's tiers with the signature they were saved under, whatever it is, otherwise null. History
     * shows a day a waiting record holds this way, see [ThingDays.kept].
     */
    suspend fun loadSaved(thing: String, epochDay: Long): SavedDay?

    companion object {
        /** The shared pass's buckets in the form used on disk and in the tier reader. */
        fun fromBuckets(buckets: List<Bucket>): DayTiers = buckets.groupBy { it.resolution }.mapValues { (_, list) ->
            val perMetric = HashMap<String, MutableList<TierBucket>>()
            for (b in list) for ((id, t) in b.tierBuckets()) perMetric.getOrPut(id) { ArrayList() } += t
            perMetric
        }

        /** Keeps nothing, every process rebuilds its tiers from the archive. */
        val NONE: TierStore = object : TierStore {
            override suspend fun load(thing: String, epochDay: Long, signature: String): DayTiers? = null
            override suspend fun save(thing: String, epochDay: Long, signature: String, tiers: DayTiers) = Unit
            override suspend fun retain(thing: String, days: Set<Long>) = Unit
            override suspend fun savedDays(thing: String): Set<Long> = emptySet()
            override suspend fun loadSaved(thing: String, epochDay: Long): SavedDay? = null
        }
    }
}

/** One saved day, its tiers and the source signature they were built from. */
class SavedDay(val signature: String, val tiers: DayTiers)

/**
 * The binary form of one saved day: a version, the signature, then per resolution and metric the
 * buckets as fixed width fields, little endian. About 48 bytes a bucket.
 */
object TierCodec {
    /** 4 = template column ids, per metric aggregates and the footer sample interval (#186), 5 = the resolved template in the signature. */
    internal const val VERSION = 5

    fun encode(signature: String, tiers: DayTiers): ByteArray {
        val out = Out()
        out.int(VERSION)
        out.string(signature)
        out.int(tiers.size)
        for ((resolution, perMetric) in tiers) {
            out.int(resolution.ordinal)
            out.int(perMetric.size)
            for ((metric, buckets) in perMetric) {
                out.string(metric)
                out.int(buckets.size)
                for (b in buckets) {
                    out.long(b.startEpochS)
                    out.int(b.rows)
                    out.int(b.validRows)
                    out.int(b.estimatedRows)
                    out.int(b.mean.toRawBits())
                    out.int(b.min.toRawBits())
                    out.int(b.max.toRawBits())
                    out.long(b.sampleIntervalMs)
                }
            }
        }
        return out.toByteArray()
    }

    /** The tiers, or null when [bytes] were saved under another signature, by another version, or are damaged. */
    fun decode(bytes: ByteArray, signature: String): DayTiers? = decodeIf(bytes) { it == signature }?.tiers

    /** The tiers and their signature whatever it is, or null when [bytes] are of another version or damaged. */
    fun decodeSaved(bytes: ByteArray): SavedDay? = decodeIf(bytes) { true }

    private fun decodeIf(bytes: ByteArray, accept: (String) -> Boolean): SavedDay? = try {
        val inp = In(bytes)
        val signature = if (inp.int() != VERSION) null else inp.string().takeIf(accept)
        if (signature == null) {
            null
        } else {
            val resolutions = Resolution.entries
            val tiers = LinkedHashMap<Resolution, Map<String, List<TierBucket>>>()
            repeat(inp.count(8)) {
                val resolution = resolutions[inp.int()]
                val perMetric = LinkedHashMap<String, List<TierBucket>>()
                repeat(inp.count(8)) {
                    val metric = inp.string()
                    perMetric[metric] = List(inp.count(40)) {
                        TierBucket(inp.long(), inp.int(), inp.int(), inp.int(), Float.fromBits(inp.int()), Float.fromBits(inp.int()), Float.fromBits(inp.int()), inp.long())
                    }
                }
                tiers[resolution] = perMetric
            }
            if (inp.remaining != 0) null else SavedDay(signature, tiers)
        }
    } catch (e: IndexOutOfBoundsException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private class Out {
        private var bytes = ByteArray(4_096)
        private var size = 0

        private fun ensure(n: Int) {
            if (size + n > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + n))
        }

        fun int(v: Int) {
            ensure(4)
            for (i in 0 until 4) bytes[size++] = (v ushr (8 * i)).toByte()
        }

        fun long(v: Long) {
            ensure(8)
            for (i in 0 until 8) bytes[size++] = (v ushr (8 * i)).toByte()
        }

        fun string(s: String) {
            val utf8 = s.encodeToByteArray()
            int(utf8.size)
            ensure(utf8.size)
            utf8.copyInto(bytes, size)
            size += utf8.size
        }

        fun toByteArray(): ByteArray = bytes.copyOf(size)
    }

    private class In(private val bytes: ByteArray) {
        private var at = 0
        val remaining: Int get() = bytes.size - at

        fun int(): Int {
            if (remaining < 4) throw IndexOutOfBoundsException()
            var v = 0
            for (i in 0 until 4) v = v or ((bytes[at++].toInt() and 0xFF) shl (8 * i))
            return v
        }

        fun long(): Long {
            if (remaining < 8) throw IndexOutOfBoundsException()
            var v = 0L
            for (i in 0 until 8) v = v or ((bytes[at++].toLong() and 0xFF) shl (8 * i))
            return v
        }

        /** A non-negative count no larger than the bytes left, so a damaged file cannot ask for a huge list. */
        fun count(bytesPerItem: Int = 1): Int = int().also { require(it in 0..remaining / bytesPerItem) }

        fun string(): String {
            val n = count()
            return bytes.decodeToString(at, at + n).also { at += n }
        }
    }
}
