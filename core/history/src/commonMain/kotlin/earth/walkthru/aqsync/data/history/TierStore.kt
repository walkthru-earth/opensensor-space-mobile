package earth.walkthru.aqsync.data.history

/** One UTC station-day of tiers: resolution, then metric id, then the buckets in time order. */
typealias DayTiers = Map<Resolution, Map<String, List<TierBucket>>>

/**
 * Keeps each station-day's tier buckets under its source signature, so a new process reads the tiers
 * without decoding any Parquet (#63). A cache: a missing, stale or unreadable entry is rebuilt from the
 * archive, and nothing here is ever the only copy of anything.
 */
interface TierStore {
    /** The day's tiers when they were saved under [signature], otherwise null. */
    suspend fun load(station: String, epochDay: Long, signature: String): DayTiers?

    suspend fun save(station: String, epochDay: Long, signature: String, tiers: DayTiers)

    /** Drops the station's saved days that are not in [days]. */
    suspend fun retain(station: String, days: Set<Long>)

    companion object {
        /** Keeps nothing, every process rebuilds its tiers from the archive. */
        val NONE: TierStore = object : TierStore {
            override suspend fun load(station: String, epochDay: Long, signature: String): DayTiers? = null
            override suspend fun save(station: String, epochDay: Long, signature: String, tiers: DayTiers) = Unit
            override suspend fun retain(station: String, days: Set<Long>) = Unit
        }
    }
}

/**
 * The binary form of one saved day: a version, the signature, then per resolution and metric the
 * buckets as fixed width fields, little endian. About 40 bytes a bucket.
 */
object TierCodec {
    private const val VERSION = 1

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
                }
            }
        }
        return out.toByteArray()
    }

    /** The tiers, or null when [bytes] were saved under another signature, by another version, or are damaged. */
    fun decode(bytes: ByteArray, signature: String): DayTiers? = try {
        val inp = In(bytes)
        if (inp.int() != VERSION || inp.string() != signature) {
            null
        } else {
            val resolutions = Resolution.entries
            val tiers = LinkedHashMap<Resolution, Map<String, List<TierBucket>>>()
            repeat(inp.count()) {
                val resolution = resolutions[inp.int()]
                val perMetric = LinkedHashMap<String, List<TierBucket>>()
                repeat(inp.count()) {
                    val metric = inp.string()
                    perMetric[metric] = List(inp.count()) {
                        TierBucket(inp.long(), inp.int(), inp.int(), inp.int(), Float.fromBits(inp.int()), Float.fromBits(inp.int()), Float.fromBits(inp.int()))
                    }
                }
                tiers[resolution] = perMetric
            }
            if (inp.remaining != 0) null else tiers
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
        fun count(): Int = int().also { require(it in 0..remaining) }

        fun string(): String {
            val n = count()
            return bytes.decodeToString(at, at + n).also { at += n }
        }
    }
}
