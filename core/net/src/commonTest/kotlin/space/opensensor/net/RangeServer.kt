package space.opensensor.net

/**
 * A minimal HTTP range server over [bytes] under [etag], shared by the fake engine and the platform
 * stubs. It honours `Range` (`bytes=-n`, `bytes=a-b`) and `If-Range` like a static object store.
 */
class RangeServer(var bytes: ByteArray, var etag: String?) {
    fun respond(range: String?, ifRange: String?): HttpRangeResponse {
        val size = bytes.size.toLong()
        if (range == null || (ifRange != null && ifRange != etag)) return whole()
        val spec = range.removePrefix("bytes=")
        val (first, last) = if (spec.startsWith("-")) {
            val n = spec.drop(1).toLong()
            if (n == 0L || size == 0L) return unsatisfiable()
            maxOf(0L, size - n) to size - 1
        } else {
            val first = spec.substringBefore('-').toLong()
            val last = spec.substringAfter('-').toLongOrNull() ?: (size - 1)
            if (first >= size) return unsatisfiable()
            first to minOf(last, size - 1)
        }
        return HttpRangeResponse(206, etag, "bytes $first-$last/$size", null, bytes.copyOfRange(first.toInt(), last.toInt() + 1))
    }

    fun whole(): HttpRangeResponse = HttpRangeResponse(200, etag, null, null, bytes.copyOf())

    private fun unsatisfiable() = HttpRangeResponse(416, etag, "bytes */${bytes.size}", null, ByteArray(0))
}

/** The test payload, `size` distinct-looking bytes. */
fun payload(size: Int): ByteArray = ByteArray(size) { (it * 31 + 7).toByte() }
