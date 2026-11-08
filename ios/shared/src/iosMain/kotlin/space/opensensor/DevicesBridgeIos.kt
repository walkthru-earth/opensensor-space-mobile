package space.opensensor

import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970
import space.opensensor.data.history.LatestDays
import space.opensensor.data.protocol.Advertisement

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
    findScan: () -> Unit,
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
    findScan = findScan,
    stopScan = stopScan,
    forget = forget,
    collect = collect,
    latestDay = { LatestDays.observe(IosArchive.tiers, it) },
    forgetPlaces = IosArchive::forgetPlaces,
)

/**
 * A foreground scan result with the advert's opensensor service data, nil when it carried none.
 * Only the flags byte is kept, so the setup watch can read the setup bit (#272).
 */
fun CompanionState.deviceFound(id: String, name: String, rssi: Int, serviceData: NSData?) =
    deviceFound(id, name, rssi, advertFlags = Advertisement.decode(serviceData?.toByteArray())?.flags)
