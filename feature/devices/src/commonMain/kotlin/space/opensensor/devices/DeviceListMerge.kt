package space.opensensor.devices

/**
 * Merges the radios and the known store into one list. Pure, so both apps and the tests read the
 * same rules.
 *
 * - A nearby sensor is matched to a known one by id. Nearby rows sort by signal, strongest first.
 * - A known sensor that does not advertise is listed under known, most recently seen first.
 * - A known sensor whose thing is on the Wi-Fi carries a Wi-Fi tag, with or without access.
 * - A Wi-Fi sensor whose thing matches no known sensor is listed on its own. It cannot be opened
 *   until it is connected over Bluetooth once, because the token comes over Bluetooth only.
 * - The archive glance is keyed by thing.
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
        val wifiThings = wifi.mapNotNull { it.thing }.toSet()

        fun wifiTag(k: KnownSensor?): WifiTag? = when {
            k?.thing == null || k.thing !in wifiThings -> null
            k.hasWifiAccess -> WifiTag.WITH_ACCESS
            else -> WifiTag.NO_ACCESS
        }

        val nearbyRows = nearby.distinctBy { it.id }.sortedByDescending { it.rssi }.map { n ->
            val k = knownById[n.id]
            DeviceRow(
                id = n.id,
                name = n.name ?: k?.name,
                thing = k?.thing,
                rssi = n.rssi,
                bonded = n.bonded,
                known = k != null,
                wifi = wifiTag(k),
                lastSeenMs = k?.lastSeenMs,
                archive = k?.thing?.let(archive::get),
            )
        }
        val nearbyIds = nearbyRows.map { it.id }.toSet()
        val knownRows = known.distinctBy { it.id }.filter { it.id !in nearbyIds }
            .sortedByDescending { it.lastSeenMs ?: Long.MIN_VALUE }
            .map { k ->
                DeviceRow(
                    id = k.id,
                    name = k.name,
                    thing = k.thing,
                    rssi = null,
                    bonded = false,
                    known = true,
                    wifi = wifiTag(k),
                    lastSeenMs = k.lastSeenMs,
                    archive = k.thing?.let(archive::get),
                )
            }
        val knownThings = known.mapNotNull { it.thing }.toSet()
        val wifiOnly = wifi.filter { it.thing == null || it.thing !in knownThings }.distinctBy { it.name }.sortedBy { it.name }
        return DeviceList(
            active = active.distinctBy { it.id },
            nearby = nearbyRows,
            known = knownRows,
            wifiOnly = wifiOnly,
            knownCount = known.distinctBy { it.id }.size,
        )
    }
}
