package space.opensensor.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import space.opensensor.data.history.parquet.Gzip
import space.opensensor.map.resources.Res

/**
 * One bundled place from GeoNames cities15000 (CC BY 4.0, credited in the map info). [ascii] is its
 * ASCII name, empty when the same as [name]. [ar] is its Arabic name, empty when GeoNames has none.
 * [country] is the ISO 3166 alpha-2 code. [lat] and [lng] are rounded to about 1 km and only move
 * the camera. Search is local, so typing never leaves the phone.
 */
@Immutable
data class Place(
    val name: String,
    val ascii: String,
    val ar: String,
    val country: String,
    val lat: Double,
    val lng: Double,
    val population: Int,
) {
    /** The name to show, Arabic in an Arabic interface when there is one. */
    fun label(arabic: Boolean): String = if (arabic && ar.isNotEmpty()) ar else name
}

/**
 * The bundled places, searchable offline. Lines come largest first, so equal matches keep the
 * larger place on top. Build it with [parse], or get the bundled one from [PlaceSearch.index].
 */
class PlaceIndex internal constructor(val places: List<Place>) {
    // Folded names per place, the name, the ASCII name and the Arabic name, each once.
    private val keys: List<List<String>> = places.map { p -> listOf(p.name, p.ascii, p.ar).filter { it.isNotEmpty() }.map(::fold).distinct() }

    /**
     * Up to [limit] places for [query], ignoring case and accents. A name equal to the query comes
     * first, then names it starts, then names with a word it starts, each in population order.
     * Arabic also ignores hamza forms, diacritics and the article "ال" on each word.
     */
    fun search(query: String, limit: Int = 6): List<Place> {
        val q = fold(query)
        if (q.isEmpty() || limit <= 0) return emptyList()
        val ranked = List(3) { ArrayList<Place>(limit) }
        for (i in places.indices) {
            val rank = keys[i].minOfOrNull { rank(it, q) } ?: continue
            if (rank < 3 && ranked[rank].size < limit) ranked[rank] += places[i]
            if (ranked[0].size >= limit) break
        }
        return ranked.flatten().take(limit)
    }

    private fun rank(key: String, q: String): Int = when {
        key == q -> 0
        key.startsWith(q) -> 1
        key.contains(" $q") -> 2
        else -> 3
    }

    companion object {
        /** The bundled file, written by tools/places/make_places.py. */
        const val FILE = "files/places/cities15000.tsv.gz"

        /** Parses the tab separated lines of [FILE]. A malformed line is skipped. */
        fun parse(text: String): PlaceIndex = PlaceIndex(
            text.lineSequence().mapNotNull { line ->
                val f = line.split('\t')
                if (f.size < 7 || f[0].isEmpty()) return@mapNotNull null
                val lat = f[4].toDoubleOrNull() ?: return@mapNotNull null
                val lng = f[5].toDoubleOrNull() ?: return@mapNotNull null
                Place(f[0], f[1], f[2], f[3], lat, lng, f[6].toIntOrNull() ?: 0)
            }.toList(),
        )

        /**
         * The search form of [s]. Lower case, Latin accents dropped, Arabic hamza forms, ta marbuta
         * and alef maqsura unified, diacritics and tatweel dropped, and "ال" taken off each word.
         */
        internal fun fold(s: String): String {
            val folded = buildString {
                for (c in s.trim().lowercase()) {
                    if (c in 'ً'..'ْ' || c == 'ـ') continue
                    append(LATIN[c] ?: ARABIC[c] ?: c)
                }
            }
            return folded.split(' ', '-', '\'').filter { it.isNotEmpty() }.joinToString(" ") { w ->
                if (w.length > 2 && w.startsWith("ال")) w.substring(2) else w
            }
        }

        private val LATIN: Map<Char, Char> = buildMap {
            "áàâäãåā".forEach { put(it, 'a') }
            "éèêëē".forEach { put(it, 'e') }
            "íìîïī".forEach { put(it, 'i') }
            "óòôöõøō".forEach { put(it, 'o') }
            "úùûüū".forEach { put(it, 'u') }
            put('ç', 'c')
            put('ñ', 'n')
            put('ş', 's')
            put('ğ', 'g')
            put('ı', 'i')
        }

        private val ARABIC: Map<Char, Char> = mapOf('أ' to 'ا', 'إ' to 'ا', 'آ' to 'ا', 'ٱ' to 'ا', 'ى' to 'ي', 'ة' to 'ه')
    }
}

/** The bundled [PlaceIndex], read once per process and kept. */
object PlaceSearch {
    private val lock = Mutex()
    private var cached: PlaceIndex? = null

    /** The bundled places, unzipped and parsed off the main thread on first use. */
    suspend fun index(): PlaceIndex = cached ?: lock.withLock {
        cached ?: withContext(Dispatchers.Default) { PlaceIndex.parse(gunzip(Res.readBytes(PlaceIndex.FILE)).decodeToString()) }.also { cached = it }
    }

    /** One gzip member, sized by its trailer (the uncompressed length, little endian). */
    internal fun gunzip(bytes: ByteArray): ByteArray {
        require(bytes.size >= 18) { "not a gzip file" }
        val n = bytes.size
        val size = (bytes[n - 4].toInt() and 0xFF) or ((bytes[n - 3].toInt() and 0xFF) shl 8) or
            ((bytes[n - 2].toInt() and 0xFF) shl 16) or ((bytes[n - 1].toInt() and 0xFF) shl 24)
        return Gzip.decompress(bytes, size)
    }
}

/** The bundled places for a composable, null while they load or when they cannot be read. */
@Composable
fun rememberPlaceIndex(): PlaceIndex? {
    val index by produceState<PlaceIndex?>(null) { value = catchingNonCancel { PlaceSearch.index() }.getOrNull() }
    return index
}

/** The name of country [code] in [languageTag], the code itself when the platform has none. */
expect fun countryName(code: String, languageTag: String): String
