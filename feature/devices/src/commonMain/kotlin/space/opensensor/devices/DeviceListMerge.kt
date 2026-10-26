package space.opensensor.devices

/**
 * Merges the radios and the known store into one list. Pure, so both apps and the tests read the
 * same rules.
 *
 * - A nearby sensor is matched to a known one by id. Nearby rows sort by signal, strongest first.
 * - A known sensor that does not advertise is listed under known, most recently seen first.
 * - A known sensor whose station is on the Wi-Fi carries a Wi-Fi tag, with or without access.
 * - A Wi-Fi sensor whose station matches no known sensor is listed on its own. It cannot be opened
 *   until it is connected over Bluetooth once, because the token comes over Bluetooth only.
 * - The archive glance is keyed by station.
 */
object DeviceListMerge {
    fun merge(
        nearby: List<NearbySensor>,
        known: List<KnownSensor>,
        wifi: List<WifiSensor>,
        active: List<ActiveLink>,
        archive: Map<String, ArchiveGlance>,
    ): DeviceList {
        val knownById = known.associateBy { it.id }
        val wifiStations = wifi.mapNotNull { it.station }.toSet()

        fun wifiTag(k: KnownSensor?): WifiTag? = when {
            k?.station == null || k.station !in wifiStations -> null
            k.hasWifiAccess -> WifiTag.WITH_ACCESS
            else -> WifiTag.NO_ACCESS
        }

        val nearbyRows = nearby.distinctBy { it.id }.sortedByDescending { it.rssi }.map { n ->
            val k = knownById[n.id]
            DeviceRow(
                id = n.id,
                name = n.name ?: k?.name,
                station = k?.station,
                rssi = n.rssi,
                bonded = n.bonded,
                known = k != null,
                wifi = wifiTag(k),
                lastSeenMs = k?.lastSeenMs,
                archive = k?.station?.let(archive::get),
            )
        }
        val nearbyIds = nearbyRows.map { it.id }.toSet()
        val knownRows = known.distinctBy { it.id }.filter { it.id !in nearbyIds }
            .sortedByDescending { it.lastSeenMs ?: Long.MIN_VALUE }
            .map { k ->
                DeviceRow(
                    id = k.id,
                    name = k.name,
                    station = k.station,
                    rssi = null,
                    bonded = false,
                    known = true,
                    wifi = wifiTag(k),
                    lastSeenMs = k.lastSeenMs,
                    archive = k.station?.let(archive::get),
                )
            }
        val knownStations = known.mapNotNull { it.station }.toSet()
        val wifiOnly = wifi.filter { it.station == null || it.station !in knownStations }.distinctBy { it.name }.sortedBy { it.name }
        return DeviceList(
            active = active.distinctBy { it.id },
            nearby = nearbyRows,
            known = knownRows,
            wifiOnly = wifiOnly,
            knownCount = known.distinctBy { it.id }.size,
        )
    }
}
