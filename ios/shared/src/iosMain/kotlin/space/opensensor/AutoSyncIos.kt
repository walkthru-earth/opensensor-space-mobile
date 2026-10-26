package space.opensensor

import kotlinx.coroutines.MainScope
import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970

/** The Auto-sync switches in UserDefaults. Peripheral ids and times only, never a credential. */
private class UserDefaultsAutoSync(private val defaults: NSUserDefaults) : AutoSyncStorage {
    override fun load(): String? = defaults.stringForKey(KEY)
    override fun save(text: String) = defaults.setObject(text, KEY)

    private companion object {
        // Separate from devices.known.v1, so the known record format stays five fields.
        const val KEY = "devices.autosync.v1"
    }
}

private fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

/**
 * The Auto-sync switch store. [notificationStatus] returns the raw UNAuthorizationStatus.
 * [requestNotifications] shows the system prompt and calls back on the main thread. [changed]
 * reschedules the background task.
 */
fun autoSyncSettings(
    notificationStatus: () -> Long,
    requestNotifications: (done: () -> Unit) -> Unit,
    changed: () -> Unit,
): AutoSyncSettings = AutoSyncSettings(
    storage = UserDefaultsAutoSync(NSUserDefaults.standardUserDefaults),
    now = ::nowMs,
    notificationStatus = notificationStatus,
    requestNotifications = requestNotifications,
    changed = changed,
)

/** The background run over the Swift adapters, on the main thread. See [BackgroundSyncBridge] for the ports. */
fun backgroundSyncBridge(
    devices: DevicesBridge,
    settings: AutoSyncSettings,
    browse: (timeoutMs: Long, done: (names: List<String>, stations: List<String>) -> Unit) -> Unit,
    sync: (id: String, wifiName: String?, done: (BackgroundStepResult) -> Unit) -> Unit,
    isOwned: (id: String) -> Boolean,
    log: (String) -> Unit,
): BackgroundSyncBridge = BackgroundSyncBridge(devices, settings, MainScope(), browse, sync, isOwned, log)
