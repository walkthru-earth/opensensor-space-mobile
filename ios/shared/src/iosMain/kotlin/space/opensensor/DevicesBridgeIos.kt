package space.opensensor

import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970
import space.opensensor.data.history.LatestDays

/** The known records in UserDefaults. Names, things and ids only, never a credential. */
private class UserDefaultsKnownRecords(private val defaults: NSUserDefaults) : KnownRecordStorage {
    override fun load(): String? = defaults.stringForKey(KEY)
    override fun save(text: String) = defaults.setObject(text, KEY)

    private companion object {
        const val KEY = "devices.known.v1"
    }
}

/**
 * Builds the My devices bridge over the app's [state] and the shared [sync] engine's receipt
 * index. Swift passes its adapter calls. [forget] receives the peripheral id and, when known, the
 * hardware id whose Keychain credential to remove. [collect] receives the sensor id and the Wi-Fi
 * service name when the step goes over Wi-Fi. Each card's day comes from the History tiers. A
 * forgotten sensor's Discover cells are dropped from the archive database.
 */
fun devicesBridge(
    state: CompanionState,
    sync: SensorSync,
    startScan: () -> Unit,
    tapScan: () -> Unit,
    stopScan: () -> Unit,
    forget: (peripheral: String, device: String?) -> Unit,
    collect: (id: String, wifiName: String?) -> Unit,
): DevicesBridge = DevicesBridge(
    state = state,
    archive = sync.index.observeAll(),
    storage = UserDefaultsKnownRecords(NSUserDefaults.standardUserDefaults),
    now = { (NSDate().timeIntervalSince1970 * 1000.0).toLong() },
    startScan = startScan,
    tapScan = tapScan,
    stopScan = stopScan,
    forget = forget,
    collect = collect,
    latestDay = { LatestDays.observe(IosArchive.tiers, it) },
    forgetPlaces = IosArchive::forgetPlaces,
)
