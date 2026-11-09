package space.opensensor.devices

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import space.opensensor.data.sync.SetupNeed
import space.opensensor.data.sync.SetupSighting
import space.opensensor.data.sync.ThingSetupWatch

/**
 * The sensors this phone connected to before. Android reads Room, iOS reads the Keychain
 * credentials and the receipt index. Neither hands a token to this module.
 */
interface KnownDeviceStore {
    val devices: Flow<List<KnownSensor>>
}

/** The Bluetooth scan and the Wi-Fi browser, as plain values. */
interface DeviceRadio {
    val nearby: Flow<List<NearbySensor>>
    val scanning: Flow<Boolean>
    val scanProblem: Flow<ScanProblem?>
    val wifi: Flow<List<WifiSensor>>
    val active: Flow<List<ActiveLink>>

    /** Starts scanning. Only called once Bluetooth is allowed. */
    fun startScan()
    fun stopScan()
}

/** One pass over every known sensor, one link per thing. */
interface CollectAllPort {
    val running: Flow<Boolean>
    fun collectAll()
}

/**
 * The state of My devices, from the ports. A plain class, so Android wraps it in a ViewModel and
 * iOS keeps one per screen. Forget lives on the sensor's own Today page.
 *
 * [setup] is the app's one [ThingSetupWatch] (#272). The scan feeds it, and it marks the rows that
 * ask for setup, counts them in [DevicesUiState.setupCount] and drives [setupOpens].
 */
class DevicesController(
    private val scope: CoroutineScope,
    private val known: KnownDeviceStore,
    private val radio: DeviceRadio,
    archive: Flow<Map<String, ArchiveGlance>>,
    private val collect: CollectAllPort,
    private val setup: ThingSetupWatch? = null,
) {
    private val needs: Flow<List<SetupNeed>> = setup?.needs(
        radio.nearby.map { seen -> seen.map { SetupSighting(it.id, it.advertFlags, it.seenMs) } },
    ) ?: flowOf(emptyList())

    private val inputs: Flow<ListInputs> = combine(radio.nearby, known.devices, radio.wifi, radio.active, archive) { n, k, w, a, g -> ListInputs(n, k, w, a, g) }

    private val merged: StateFlow<Merged?> = combine(inputs, needs) { i, n ->
        val ids = n.map { it.id.lowercase() }.toSet()
        Merged(DeviceListMerge.merge(i.nearby, i.known, i.wifi, i.active, i.archive, ids), n.size, autoRequest(n, i))
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val state: StateFlow<DevicesUiState> = combine(merged.filterNotNull(), radio.scanning, radio.scanProblem, collect.running) { m, s, p, c ->
        DevicesUiState(list = m.list, scanning = s, scanProblem = p, collecting = c, setupCount = m.setupCount)
    }.stateIn(scope, SharingStarted.Eagerly, DevicesUiState())

    /**
     * One shot requests to open the wizard by itself (#272), collected by the screen only while it
     * shows. It fires when exactly one sensor asks for setup and the wizard has not opened for it,
     * and only while [ThingSetupWatch.claimAutoOpen] still holds this session's one automatic open.
     * The claim spends it, so a later collector, or a second sensor during the screen fade, gets
     * nothing until the next foreground session.
     */
    val setupOpens: Flow<SetupRequest> = merged
        .map { it?.auto }
        .distinctUntilChanged()
        .filterNotNull()
        .filter { setup?.claimAutoOpen(it.id) == true }

    /**
     * Handles the actions that need no platform. The route handles [DevicesAction.Open],
     * [DevicesAction.Add] and [DevicesAction.SetupMany], and navigates for [DevicesAction.FinishSetup]
     * after this marks the sensor opened.
     */
    fun onAction(action: DevicesAction) {
        when (action) {
            DevicesAction.StartScan -> radio.startScan()
            DevicesAction.StopScan -> radio.stopScan()
            DevicesAction.CollectAll -> if (!state.value.collecting) collect.collectAll()
            is DevicesAction.FinishSetup -> setup?.opened(action.request.id)
            is DevicesAction.Open, is DevicesAction.Add, DevicesAction.SetupMany -> Unit
        }
    }

    private class ListInputs(
        val nearby: List<NearbySensor>,
        val known: List<KnownSensor>,
        val wifi: List<WifiSensor>,
        val active: List<ActiveLink>,
        val archive: Map<String, ArchiveGlance>,
    )

    private class Merged(val list: DeviceList, val setupCount: Int, val auto: SetupRequest?)

    /** The automatic open, only when exactly one sensor asks and its wizard has not run this session. */
    private fun autoRequest(needs: List<SetupNeed>, i: ListInputs): SetupRequest? {
        val need = needs.singleOrNull()?.takeIf { !it.opened } ?: return null
        val known = i.known.firstOrNull { it.id.equals(need.id, ignoreCase = true) }
        val name = i.nearby.firstOrNull { it.id.equals(need.id, ignoreCase = true) }?.name ?: known?.name
        return SetupRequest(known?.id ?: need.id, name, known = known != null, auto = true)
    }
}
