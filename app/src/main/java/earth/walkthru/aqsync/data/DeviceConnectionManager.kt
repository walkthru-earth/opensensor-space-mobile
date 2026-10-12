package earth.walkthru.aqsync.data

import earth.walkthru.aqsync.collection.StopSource
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.lan.LanDiscoveryState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.concurrent.withLock

/** One process-owned controller per stable public BLE endpoint; UI selection never closes another. */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DeviceConnectionManager @Inject constructor(
    private val factory: Provider<DeviceConnectionRepository>,
    private val discovery: LanDiscoveryState,
    private val admission: CollectionAdmission,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controllers = MutableStateFlow<Map<String, DeviceConnectionRepository>>(emptyMap())

    private val lock = ReentrantLock()

    fun controller(address: String): DeviceConnectionRepository = lock.withLock {
        controllers.value[address] ?: factory.get().also {
            it.bind(address)
            controllers.value = controllers.value + (address to it)
        }
    }

    val lanDevices get() = discovery.devices
    val lanDiscoveryError get() = discovery.error
    fun setLanDiscoveryActive(active: Boolean) = discovery.setActive(active)

    val snapshots: StateFlow<List<DeviceSnapshot>> = controllers.flatMapLatest { all ->
        if (all.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(
                all.map { (address, controller) ->
                    combine(controller.connectionState, controller.sync, controller.reconnecting, controller.foregroundNeeded, controller.ownership) { connection, progress, reconnect, needed, ownership ->
                        DeviceSnapshot(address, controller.currentDisplayName, connection, progress, reconnect, needed, controller.backgroundOwner.value, ownership)
                    }
                }
            ) { it.toList() }
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun isOwned(address: String): Boolean = controllers.value[address]?.interactiveSessionAddress != null

    fun hasInteractiveCollection(): Boolean = admission.hasInteractive

    /** Notification Stop targets interactive transfers only; workers retain their own cancel action. */
    fun cancelInteractive(by: StopSource = StopSource.USER) {
        controllers.value.values.filter { it.backgroundOwner.value == null }.forEach {
            it.stop(by)
        }
    }
}

data class DeviceSnapshot(
    val address: String,
    val name: String?,
    val connection: ConnectionState,
    val progress: SyncProgress,
    val reconnect: ReconnectState?,
    val foregroundNeeded: Boolean,
    val backgroundOwner: String?,
    val ownership: DeviceOwnership = DeviceOwnership.IDLE,
)

/**
 * App-wide "a collection is queued or running": a one-time background request waits or runs
 * ([workActive]) or any device is held by an interactive or background collection.
 */
fun anyCollectionActive(workActive: Boolean, devices: List<DeviceSnapshot>): Boolean =
    workActive || devices.any { it.ownership != DeviceOwnership.IDLE || it.progress.running }

/** Aggregate counts without assigning one device's current filename or reconnect state to another. */
fun collectionProgress(devices: List<DeviceSnapshot>): SyncProgress = SyncProgress(
    running = devices.any { it.progress.running },
    filesTotal = devices.sumOf { it.progress.filesTotal },
    filesDone = devices.sumOf { it.progress.filesDone },
    bytesTotal = devices.sumOf { it.progress.bytesTotal },
    bytesDone = devices.sumOf { it.progress.bytesDone },
    bytesPerSecond = devices.sumOf { it.progress.bytesPerSecond },
    windowsRecovered = devices.sumOf { it.progress.windowsRecovered },
)
