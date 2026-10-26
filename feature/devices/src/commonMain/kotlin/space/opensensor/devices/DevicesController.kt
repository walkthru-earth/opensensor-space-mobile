package space.opensensor.devices

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The sensors this phone connected to before. Android reads Room, iOS reads the Keychain
 * credentials and the receipt index. Neither hands a token to this module.
 */
interface KnownDeviceStore {
    val devices: Flow<List<KnownSensor>>

    /**
     * Forgets [id] on this phone. Collected files stay. Called only after the user confirmed.
     * Both drop the saved LAN token and close a live link. Android also drops the companion
     * association. iOS cannot remove the system bond and says so in the dialog.
     */
    suspend fun forget(id: String)
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
 * The state of My devices, from the ports, plus the open Forget confirmation. A plain class, so
 * Android wraps it in a ViewModel and iOS keeps one per screen.
 */
class DevicesController(
    private val scope: CoroutineScope,
    private val known: KnownDeviceStore,
    private val radio: DeviceRadio,
    archive: Flow<Map<String, ArchiveGlance>>,
    private val collect: CollectAllPort,
) {
    private val forget = MutableStateFlow<ForgetTarget?>(null)

    private val list: Flow<DeviceList> = combine(radio.nearby, known.devices, radio.wifi, radio.active, archive) { n, k, w, a, g ->
        DeviceListMerge.merge(n, k, w, a, g)
    }

    val state: StateFlow<DevicesUiState> = combine(list, radio.scanning, radio.scanProblem, collect.running, forget) { l, s, p, c, f ->
        DevicesUiState(list = l, scanning = s, scanProblem = p, collecting = c, forget = f)
    }.stateIn(scope, SharingStarted.Eagerly, DevicesUiState())

    /** Handles the actions that need no platform. The route handles [DevicesAction.Open]. */
    fun onAction(action: DevicesAction) {
        when (action) {
            DevicesAction.StartScan -> radio.startScan()
            DevicesAction.StopScan -> radio.stopScan()
            is DevicesAction.RequestForget -> forget.value = ForgetTarget(action.id, action.name)
            DevicesAction.CancelForget -> forget.value = null
            DevicesAction.ConfirmForget -> {
                val target = forget.value ?: return
                forget.value = null
                scope.launch { known.forget(target.id) }
            }
            DevicesAction.CollectAll -> if (!state.value.collecting) collect.collectAll()
            is DevicesAction.Open -> Unit
        }
    }
}
