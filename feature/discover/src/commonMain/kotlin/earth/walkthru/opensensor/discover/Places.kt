package earth.walkthru.opensensor.discover

/** A searchable place. Search is local, so typing never leaves the phone. */
data class Place(val name: String, val country: String, val lat: Double, val lng: Double)

/**
 * A small built-in list of large cities, enough to move the map in this slice. The full bundled
 * list (GeoNames cities of 15,000 people or more, CC BY 4.0, credited in About) replaces it later.
 */
object Places {
    val all: List<Place> = listOf(
        Place("Accra", "Ghana", 5.6037, -0.187),
        Place("Addis Ababa", "Ethiopia", 9.03, 38.74),
        Place("Alexandria", "Egypt", 31.2001, 29.9187),
        Place("Amsterdam", "Netherlands", 52.3676, 4.9041),
        Place("Athens", "Greece", 37.9838, 23.7275),
        Place("Bangkok", "Thailand", 13.7563, 100.5018),
        Place("Barcelona", "Spain", 41.3874, 2.1686),
        Place("Beijing", "China", 39.9042, 116.4074),
        Place("Berlin", "Germany", 52.52, 13.405),
        Place("Bogotá", "Colombia", 4.711, -74.0721),
        Place("Buenos Aires", "Argentina", -34.6037, -58.3816),
        Place("Cairo", "Egypt", 30.0444, 31.2357),
        Place("Cape Town", "South Africa", -33.9249, 18.4241),
        Place("Casablanca", "Morocco", 33.5731, -7.5898),
        Place("Chicago", "United States", 41.8781, -87.6298),
        Place("Delhi", "India", 28.7041, 77.1025),
        Place("Dubai", "United Arab Emirates", 25.2048, 55.2708),
        Place("Hamburg", "Germany", 53.5511, 9.9937),
        Place("Istanbul", "Türkiye", 41.0082, 28.9784),
        Place("Jakarta", "Indonesia", -6.2088, 106.8456),
        Place("Johannesburg", "South Africa", -26.2041, 28.0473),
        Place("Lagos", "Nigeria", 6.5244, 3.3792),
        Place("Lima", "Peru", -12.0464, -77.0428),
        Place("Lisbon", "Portugal", 38.7223, -9.1393),
        Place("London", "United Kingdom", 51.5072, -0.1276),
        Place("Los Angeles", "United States", 34.0522, -118.2437),
        Place("Madrid", "Spain", 40.4168, -3.7038),
        Place("Manila", "Philippines", 14.5995, 120.9842),
        Place("Mexico City", "Mexico", 19.4326, -99.1332),
        Place("Milan", "Italy", 45.4642, 9.19),
        Place("Mumbai", "India", 19.076, 72.8777),
        Place("Munich", "Germany", 48.1351, 11.582),
        Place("Nairobi", "Kenya", -1.2921, 36.8219),
        Place("New York", "United States", 40.7128, -74.006),
        Place("Paris", "France", 48.8566, 2.3522),
        Place("Porto", "Portugal", 41.1579, -8.6291),
        Place("Rome", "Italy", 41.9028, 12.4964),
        Place("San Francisco", "United States", 37.7749, -122.4194),
        Place("São Paulo", "Brazil", -23.5505, -46.6333),
        Place("Seoul", "South Korea", 37.5665, 126.978),
        Place("Singapore", "Singapore", 1.3521, 103.8198),
        Place("Stockholm", "Sweden", 59.3293, 18.0686),
        Place("Sydney", "Australia", -33.8688, 151.2093),
        Place("Tokyo", "Japan", 35.6762, 139.6503),
        Place("Toronto", "Canada", 43.6532, -79.3832),
        Place("Tunis", "Tunisia", 36.8065, 10.1815),
        Place("Vienna", "Austria", 48.2082, 16.3738),
        Place("Warsaw", "Poland", 52.2297, 21.0122),
    )

    /** Places whose name or country starts a word with [query], ignoring case and accents. */
    fun search(query: String, limit: Int = 6): List<Place> {
        val q = fold(query.trim())
        if (q.isEmpty()) return emptyList()
        return all.filter { p -> words(p.name).any { it.startsWith(q) } || fold(p.name).startsWith(q) || words(p.country).any { it.startsWith(q) } }
            .sortedBy { if (fold(it.name).startsWith(q)) 0 else 1 }
            .take(limit)
    }

    private fun words(s: String) = fold(s).split(' ')

    private fun fold(s: String): String = buildString {
        for (c in s.lowercase()) {
            append(
                when (c) {
                    'á', 'à', 'â', 'ä', 'ã' -> 'a'
                    'é', 'è', 'ê', 'ë' -> 'e'
                    'í', 'ì', 'î', 'ï' -> 'i'
                    'ó', 'ò', 'ô', 'ö', 'õ' -> 'o'
                    'ú', 'ù', 'û', 'ü' -> 'u'
                    'ç' -> 'c'
                    else -> c
                },
            )
        }
    }
}
