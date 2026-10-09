package earth.walkthru.opensensor.permissions

/** The runtime permissions either app ever asks for. Location is approximate only. */
enum class AccessKind {
    /** Android BLUETOOTH_SCAN and BLUETOOTH_CONNECT, iOS Core Bluetooth. Never location. */
    BLUETOOTH,

    /** Android ACCESS_LOCAL_NETWORK (API 37 and later), iOS Local Network privacy. */
    LOCAL_NETWORK,

    /** Android POST_NOTIFICATIONS (API 33 and later), iOS user notifications. */
    NOTIFICATIONS,

    /** Android ACCESS_COARSE_LOCATION, iOS When In Use with reduced accuracy. */
    APPROX_LOCATION,

    /**
     * iOS camera, for the VisionKit sticker scanner. Android's Google code scanner reads the barcode
     * in Play services and needs no camera permission, so Android never asks for this one.
     */
    CAMERA,
}

/** The taps that may lead to a permission prompt, plus [APP_LAUNCH], which never does. */
enum class UserAction {
    /** Opening the app. Listed so the table states that nothing is asked here. */
    APP_LAUNCH,

    /** Find sensors (scan) on My devices. */
    FIND_SENSORS,

    /** Opening a found sensor. A refusal still opens its local archive. */
    OPEN_SENSOR,

    /** Reconnect, on the device and Today screens. */
    RECONNECT,

    /** Sync now, Sync all and per file sync. The sync runs whatever the notification answer. */
    SYNC,

    /** Find Wi-Fi sensors, the Wi-Fi sync switch and Allow local network. */
    WIFI_ACTION,

    /** Save Wi-Fi access (the token comes over Bluetooth). A refusal does not block it. */
    SAVE_WIFI_ACCESS,

    /** Near me on the Discover map. */
    NEAR_ME,

    /** Turning the Auto-sync switch on, and Wake when nearby. Enabled whatever the answer. */
    AUTO_SYNC_ON,

    /** Scan on the sensor sticker card. A refusal leaves manual entry in place. */
    SCAN_STICKER,
}

/**
 * What one [UserAction] asks for. [required] permissions gate the action (a refusal stops it, or
 * runs its fallback). [optional] ones are asked first but the action runs whatever the answer.
 */
data class Trigger(val required: Set<AccessKind> = emptySet(), val optional: Set<AccessKind> = emptySet()) {
    val all: Set<AccessKind> get() = required + optional
}

/**
 * The one table from user action to permission, for both apps (docs/store-compliance.md). Every
 * prompt comes from one of these taps, never from launch or composition, so there is no permission
 * wall. Platforms without a permission skip it (Android below API 37 has no local network
 * permission, iOS has no Bluetooth radio switch the app can ask for).
 */
object PermissionTriggers {
    val table: Map<UserAction, Trigger> = mapOf(
        UserAction.APP_LAUNCH to Trigger(),
        UserAction.FIND_SENSORS to Trigger(required = setOf(AccessKind.BLUETOOTH)),
        UserAction.OPEN_SENSOR to Trigger(required = setOf(AccessKind.BLUETOOTH)),
        UserAction.RECONNECT to Trigger(required = setOf(AccessKind.BLUETOOTH)),
        UserAction.SYNC to Trigger(optional = setOf(AccessKind.NOTIFICATIONS)),
        UserAction.WIFI_ACTION to Trigger(required = setOf(AccessKind.LOCAL_NETWORK)),
        UserAction.SAVE_WIFI_ACCESS to Trigger(optional = setOf(AccessKind.LOCAL_NETWORK)),
        UserAction.NEAR_ME to Trigger(required = setOf(AccessKind.APPROX_LOCATION)),
        UserAction.AUTO_SYNC_ON to Trigger(optional = setOf(AccessKind.NOTIFICATIONS)),
        UserAction.SCAN_STICKER to Trigger(required = setOf(AccessKind.CAMERA)),
    )

    /** What [action] asks for. */
    fun of(action: UserAction): Trigger = table.getValue(action)

    /** The taps allowed to show the prompt for [kind]. */
    fun actionsAsking(kind: AccessKind): Set<UserAction> = table.filterValues { kind in it.all }.keys
}
