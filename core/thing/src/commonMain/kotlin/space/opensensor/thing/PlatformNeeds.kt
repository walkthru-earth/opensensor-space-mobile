package space.opensensor.thing

import space.opensensor.data.protocol.LanProtocol

/**
 * What one kind, or the app shell, asks of the Android manifest. [permissions] are `uses-permission`
 * names, [foregroundServiceTypes] the `foregroundServiceType` values of its services.
 */
data class AndroidNeeds(
    val permissions: Set<String> = emptySet(),
    val foregroundServiceTypes: Set<String> = emptySet(),
) {
    operator fun plus(other: AndroidNeeds) = AndroidNeeds(permissions + other.permissions, foregroundServiceTypes + other.foregroundServiceTypes)
}

/**
 * What one kind, or the app shell, asks of the iOS bundle. [infoKeys] are the gated `Info.plist`
 * privacy keys ([isGatedInfoKey]). [bonjourServices], [backgroundModes] and [backgroundTaskIds] are the
 * values of `NSBonjourServices`, `UIBackgroundModes` and `BGTaskSchedulerPermittedIdentifiers`.
 * [entitlements] are entitlement keys. [accessedApis] maps each `PrivacyInfo.xcprivacy` required
 * reason API category to its reasons, and [collectedDataTypes] lists its collected data types.
 */
data class IosNeeds(
    val infoKeys: Set<String> = emptySet(),
    val bonjourServices: Set<String> = emptySet(),
    val backgroundModes: Set<String> = emptySet(),
    val backgroundTaskIds: Set<String> = emptySet(),
    val entitlements: Set<String> = emptySet(),
    val accessedApis: Map<String, Set<String>> = emptyMap(),
    val collectedDataTypes: Set<String> = emptySet(),
) {
    operator fun plus(other: IosNeeds) = IosNeeds(
        infoKeys + other.infoKeys,
        bonjourServices + other.bonjourServices,
        backgroundModes + other.backgroundModes,
        backgroundTaskIds + other.backgroundTaskIds,
        entitlements + other.entitlements,
        (accessedApis.keys + other.accessedApis.keys).associateWith { accessedApis[it].orEmpty() + other.accessedApis[it].orEmpty() },
        collectedDataTypes + other.collectedDataTypes,
    )

    companion object {
        /** Info.plist keys a store review reads without a usage string, so the gate checks them too. */
        private val FLAG_KEYS = setOf("NSLocationDefaultAccuracyReduced", "NSSupportsLiveActivities")

        /** Whether the gate checks Info.plist [key], every usage string, a temporary usage dictionary or a flag key. */
        fun isGatedInfoKey(key: String): Boolean = "UsageDescription" in key || key in FLAG_KEYS
    }
}

/** What one kind, or the app shell, asks of both platforms. */
data class PlatformNeeds(
    val android: AndroidNeeds = AndroidNeeds(),
    val ios: IosNeeds = IosNeeds(),
) {
    operator fun plus(other: PlatformNeeds) = PlatformNeeds(android + other.android, ios + other.ios)
}

/**
 * The one table of what each kind asks of the Android manifest and the iOS bundle (#227). The
 * manifest and bundle gate tests (`ManifestPermissionsTest`, `IosBundleGateTest`) fail when a
 * declared entry is missing or a present entry is declared by no enabled kind and not by [APP].
 * A new permission, mode or entitlement lands here with its kind, in the same release, with its
 * store notes in docs/store-compliance.md.
 */
object PlatformNeedsTable {
    private const val LOCATION_WHEN_IN_USE = "NSLocationWhenInUseUsageDescription"
    private const val LOCATION_REDUCED = "NSLocationDefaultAccuracyReduced"
    private const val USER_DEFAULTS = "NSPrivacyAccessedAPICategoryUserDefaults"

    /**
     * The app shell without any kind. Discover reads the public feed and Near me asks for an
     * approximate fix. The phone archive, its storage budget and the elapsed time clock serve every
     * kind, so their required reasons sit here.
     */
    val APP = PlatformNeeds(
        android = AndroidNeeds(
            permissions = setOf(
                "android.permission.INTERNET",
                // Merged from the maplibre-compose manifest for the Discover map, a normal permission.
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.ACCESS_COARSE_LOCATION",
            ),
        ),
        ios = IosNeeds(
            infoKeys = setOf(LOCATION_WHEN_IN_USE, LOCATION_REDUCED),
            accessedApis = mapOf(
                // File dates in the app container archive (core/archive, core/storage).
                "NSPrivacyAccessedAPICategoryFileTimestamp" to setOf("C617.1"),
                // Free space checked before writing archive and export files.
                "NSPrivacyAccessedAPICategoryDiskSpace" to setOf("E174.1"),
                // ProcessInfo.systemUptime for elapsed time.
                "NSPrivacyAccessedAPICategorySystemBootTime" to setOf("35F9.1"),
                // The Discover map camera, chip choices and app settings.
                USER_DEFAULTS to setOf("CA92.1"),
            ),
        ),
    )

    /**
     * An opensensor board over Bluetooth LE and the LAN, synced by hand, by Auto-sync and when it
     * comes near, with its sticker scan and its approximate area from Use phone location.
     */
    val OPENSENSOR_BOARD = PlatformNeeds(
        android = AndroidNeeds(
            permissions = setOf(
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.ACCESS_LOCAL_NETWORK",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
                "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.WAKE_LOCK",
                "android.permission.REQUEST_COMPANION_RUN_IN_BACKGROUND",
                "android.permission.REQUEST_COMPANION_USE_DATA_IN_BACKGROUND",
                "android.permission.REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND",
                "android.permission.REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE",
                "android.permission.RECEIVE_BOOT_COMPLETED",
                "android.permission.ACCESS_COARSE_LOCATION",
            ),
            foregroundServiceTypes = setOf("connectedDevice", "dataSync"),
        ),
        ios = IosNeeds(
            infoKeys = setOf(
                "NSBluetoothAlwaysUsageDescription",
                "NSLocalNetworkUsageDescription",
                "NSCameraUsageDescription",
                LOCATION_WHEN_IN_USE,
                LOCATION_REDUCED,
            ),
            bonjourServices = setOf(LanProtocol.SERVICE_TYPE),
            backgroundModes = setOf("processing", "bluetooth-central"),
            backgroundTaskIds = setOf("space.opensensor.app.autosync", "space.opensensor.app.sync.*"),
            accessedApis = mapOf(
                // The Auto-sync switches, their last run times and the bonded sensor ids.
                USER_DEFAULTS to setOf("CA92.1"),
            ),
        ),
    )

    /** Each kind's needs by kind id. A kind missing here fails the gates. */
    val KINDS: Map<KindId, PlatformNeeds> = mapOf(OpenSensorBoardKind.id to OPENSENSOR_BOARD)

    /** What [APP] and the [enabled] kinds ask for together. Throws when a kind has no row. */
    fun of(enabled: List<ThingKind>): PlatformNeeds = enabled.fold(APP) { all, kind ->
        all + requireNotNull(KINDS[kind.id]) { "kind ${kind.id} declares no platform needs" }
    }
}
