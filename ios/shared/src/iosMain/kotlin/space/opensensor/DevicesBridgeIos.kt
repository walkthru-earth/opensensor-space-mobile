package space.opensensor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import space.opensensor.data.history.LatestDays
import space.opensensor.data.local.database.KnownDevice
import space.opensensor.data.local.database.KnownDeviceDao
import space.opensensor.data.local.database.ThingEpoch
import space.opensensor.data.local.database.ThingEpochDao
import space.opensensor.data.protocol.Advertisement

/**
 * The known records in the Room `known_device` table Android uses (#303). Names, things and ids
 * only. The LAN token stays in the Keychain, so its column stays empty on iOS. Earlier things are
 * the table's thing epochs. Writes leave the main thread in order, and only the newest list waits.
 */
private class RoomKnownRecords(private val devices: KnownDeviceDao, private val epochs: ThingEpochDao) : KnownRecordStorage {
    private val writes = Channel<List<KnownRecord>>(Channel.CONFLATED)

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            for (list in writes) {
                try {
                    write(list)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    println("known sensors not saved: ${e::class.simpleName}")
                }
            }
        }
    }

    // Read once, before any sensor opens, so the continuity check always sees the saved sensors.
    override fun load(): List<KnownRecord> = try {
        runBlocking(Dispatchers.Default) {
            devices.allByLastConnected().mapNotNull { row ->
                val thing = row.thing?.takeIf(SyncWire::isThingId) ?: return@mapNotNull null
                val device = row.deviceId?.takeIf { HARDWARE_ID.matches(it) } ?: return@mapNotNull null
                val older = epochs.forDevice(row.address).sortedByDescending { it.firstSeenEpochMs }.map { it.thing }.filter { it != thing }.distinct()
                KnownRecord(row.address, row.name, thing, device.lowercase(), row.lastConnectedEpochMs, older)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println("known sensors not read: ${e::class.simpleName}")
        emptyList()
    }

    override fun save(records: List<KnownRecord>) {
        writes.trySend(records)
    }

    private suspend fun write(list: List<KnownRecord>) {
        val keep = list.mapTo(HashSet()) { it.peripheral.uppercase() }
        devices.allByLastConnected().filter { it.address !in keep }.forEach { devices.delete(it.address) }
        for (r in list) {
            val address = r.peripheral.uppercase()
            val seen = r.lastSeenMs ?: 0L
            val old = devices.get(address)
            devices.upsert(
                KnownDevice(
                    address = address,
                    name = r.name,
                    thing = r.thing,
                    deviceId = r.device,
                    firmware = old?.firmware,
                    lastSeenEpochMs = seen,
                    lastConnectedEpochMs = r.lastSeenMs,
                ),
            )
            // Older things first seen before the current one, newest of them last before it.
            r.olderThings.asReversed().forEachIndexed { i, older ->
                epochs.recordIfAbsent(ThingEpoch(address, older, r.device, seen - r.olderThings.size + i, observedFromAuthenticatedBle = true))
            }
            epochs.recordIfAbsent(ThingEpoch(address, r.thing, r.device, seen, observedFromAuthenticatedBle = true))
        }
    }

    private companion object {
        val HARDWARE_ID = Regex("[0-9a-fA-F]{12}")
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
    storage = RoomKnownRecords(IosArchive.knownDevices, IosArchive.thingEpochs),
    now = { (NSDate().timeIntervalSince1970 * 1000.0).toLong() },
    startScan = startScan,
    tapScan = tapScan,
    findScan = findScan,
    stopScan = stopScan,
    forget = forget,
    collect = collect,
    latestDay = { LatestDays.observe(IosArchive.tiers, it) },
    forgetPlaces = IosArchive::forgetPlaces,
    forgetSettings = settingsRepository::forgetSensor,
)

/**
 * A foreground scan result with the advert's opensensor service data, nil when it carried none.
 * Only the flags byte is kept, so the setup watch can read the setup bit (#272).
 */
fun CompanionState.deviceFound(id: String, name: String, rssi: Int, serviceData: NSData?) =
    deviceFound(id, name, rssi, advertFlags = Advertisement.decode(serviceData?.toByteArray())?.flags)
