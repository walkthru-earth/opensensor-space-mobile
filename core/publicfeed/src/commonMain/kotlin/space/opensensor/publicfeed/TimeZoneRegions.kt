package space.opensensor.publicfeed

/**
 * Where the map opens when no camera was saved: a region from the phone's IANA time zone, so the
 * app never asks for location at launch (docs/devices-and-discovery.md, "Centring the map").
 * Known zones land on their country or metro. Others fall back to their continent, like an
 * overview. The table is bundled and approximate on purpose.
 */
object TimeZoneRegions {
    private val zones: Map<String, GeoBounds> = mapOf(
        // Africa
        "Africa/Cairo" to GeoBounds(24.7, 22.0, 36.9, 31.7),
        "Africa/Nairobi" to GeoBounds(33.9, -4.7, 41.9, 5.0),
        "Africa/Lagos" to GeoBounds(2.7, 4.2, 14.7, 13.9),
        "Africa/Johannesburg" to GeoBounds(16.4, -34.9, 32.9, -22.1),
        "Africa/Casablanca" to GeoBounds(-13.2, 27.6, -1.0, 35.9),
        "Africa/Accra" to GeoBounds(-3.3, 4.7, 1.2, 11.2),
        "Africa/Addis_Ababa" to GeoBounds(33.0, 3.4, 48.0, 14.9),
        "Africa/Algiers" to GeoBounds(-8.7, 19.0, 12.0, 37.1),
        "Africa/Tunis" to GeoBounds(7.5, 30.2, 11.6, 37.5),
        // Europe
        "Europe/Berlin" to GeoBounds(5.9, 47.3, 15.0, 55.1),
        "Europe/London" to GeoBounds(-8.6, 49.9, 1.8, 58.7),
        "Europe/Paris" to GeoBounds(-4.8, 42.3, 8.2, 51.1),
        "Europe/Madrid" to GeoBounds(-9.3, 36.0, 3.3, 43.8),
        "Europe/Lisbon" to GeoBounds(-9.5, 36.9, -6.2, 42.2),
        "Europe/Rome" to GeoBounds(6.6, 36.6, 18.5, 47.1),
        "Europe/Amsterdam" to GeoBounds(3.3, 50.7, 7.2, 53.6),
        "Europe/Brussels" to GeoBounds(2.5, 49.5, 6.4, 51.5),
        "Europe/Vienna" to GeoBounds(9.5, 46.4, 17.2, 49.0),
        "Europe/Zurich" to GeoBounds(5.9, 45.8, 10.5, 47.8),
        "Europe/Warsaw" to GeoBounds(14.1, 49.0, 24.2, 54.9),
        "Europe/Stockholm" to GeoBounds(11.0, 55.3, 24.2, 69.1),
        "Europe/Athens" to GeoBounds(19.4, 34.8, 28.3, 41.8),
        "Europe/Istanbul" to GeoBounds(26.0, 35.8, 44.8, 42.1),
        "Europe/Kyiv" to GeoBounds(22.1, 44.4, 40.2, 52.4),
        "Europe/Moscow" to GeoBounds(27.0, 41.2, 60.0, 68.0),
        // Asia
        "Asia/Dubai" to GeoBounds(51.5, 22.6, 56.4, 26.1),
        "Asia/Riyadh" to GeoBounds(34.5, 16.4, 55.7, 32.2),
        "Asia/Kolkata" to GeoBounds(68.1, 6.7, 97.4, 35.5),
        "Asia/Jakarta" to GeoBounds(95.0, -11.0, 141.0, 6.1),
        "Asia/Singapore" to GeoBounds(103.6, 1.2, 104.1, 1.5),
        "Asia/Bangkok" to GeoBounds(97.3, 5.6, 105.6, 20.5),
        "Asia/Shanghai" to GeoBounds(73.5, 18.2, 134.8, 53.6),
        "Asia/Tokyo" to GeoBounds(129.4, 31.0, 145.5, 45.5),
        "Asia/Seoul" to GeoBounds(124.6, 33.1, 130.9, 38.6),
        "Asia/Manila" to GeoBounds(116.9, 4.6, 126.6, 21.1),
        // Americas
        "America/New_York" to GeoBounds(-84.8, 24.5, -66.9, 47.5),
        "America/Chicago" to GeoBounds(-104.1, 25.8, -84.8, 49.4),
        "America/Denver" to GeoBounds(-114.1, 31.3, -101.0, 49.0),
        "America/Los_Angeles" to GeoBounds(-124.8, 32.5, -114.1, 49.0),
        "America/Toronto" to GeoBounds(-95.2, 41.7, -74.3, 56.9),
        "America/Vancouver" to GeoBounds(-139.1, 48.3, -114.0, 60.0),
        "America/Mexico_City" to GeoBounds(-117.1, 14.5, -86.7, 32.7),
        "America/Bogota" to GeoBounds(-79.0, -4.2, -66.9, 12.5),
        "America/Lima" to GeoBounds(-81.3, -18.4, -68.7, -0.04),
        "America/Sao_Paulo" to GeoBounds(-53.1, -25.3, -44.2, -19.8),
        "America/Argentina/Buenos_Aires" to GeoBounds(-73.6, -55.1, -53.6, -21.8),
        "America/Santiago" to GeoBounds(-75.7, -56.0, -66.4, -17.5),
        // Oceania
        "Australia/Sydney" to GeoBounds(140.9, -37.5, 153.6, -28.2),
        "Australia/Melbourne" to GeoBounds(140.9, -39.2, 150.0, -33.9),
        "Australia/Perth" to GeoBounds(112.9, -35.2, 129.0, -13.7),
        "Pacific/Auckland" to GeoBounds(166.4, -47.3, 178.6, -34.4),
    )

    private val continents: Map<String, GeoBounds> = mapOf(
        "Africa" to GeoBounds(-18.0, -35.0, 52.0, 37.5),
        "Europe" to GeoBounds(-11.0, 35.0, 32.0, 66.0),
        "Asia" to GeoBounds(26.0, -10.0, 146.0, 55.0),
        "America" to GeoBounds(-125.0, -40.0, -60.0, 50.0),
        "Australia" to GeoBounds(112.0, -44.0, 154.0, -10.0),
        "Pacific" to GeoBounds(150.0, -48.0, 180.0, 0.0),
        "Atlantic" to GeoBounds(-40.0, 25.0, 0.0, 66.0),
        "Indian" to GeoBounds(40.0, -40.0, 100.0, 10.0),
    )

    /** The whole world, for a zone with no entry (`UTC` and the `Etc` zones). */
    val WORLD = GeoBounds(-170.0, -55.0, 170.0, 70.0)

    fun regionFor(zoneId: String): GeoBounds = zones[zoneId] ?: continents[zoneId.substringBefore('/')] ?: WORLD
}
