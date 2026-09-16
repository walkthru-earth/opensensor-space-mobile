package earth.walkthru.aqsync.ui.scan

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import earth.walkthru.aqsync.BuildConfig
import earth.walkthru.aqsync.data.DeviceConnectionRepository
import earth.walkthru.aqsync.data.DeviceEvent
import earth.walkthru.aqsync.data.ble.BleScanner
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.ScannedDevice
import earth.walkthru.aqsync.data.fake.FakeTransport
import earth.walkthru.aqsync.data.local.database.KnownDevice
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ScanUiState(
    val scanning: Boolean = false,
    val scanError: String? = null,
    /** Advertisers seen in the last ~15 s, strongest first. */
    val nearby: List<ScannedDevice> = emptyList(),
    val known: List<KnownDevice> = emptyList(),
    val connection: ConnectionState = ConnectionState.Disconnected,
    /** Address we are currently trying to connect to, for the row spinner. */
    val connectingTo: String? = null,
    val showFakeEntry: Boolean = BuildConfig.DEBUG,
    /** Stations currently advertised on the LAN, for the "on LAN" tag. */
    val lanStations: Set<String> = emptySet(),
)

@HiltViewModel
class ScanViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DeviceConnectionRepository,
) : ViewModel() {

    private val scanning = MutableStateFlow(false)
    private val scanError = MutableStateFlow<String?>(null)
    private val seen = MutableStateFlow<Map<String, ScannedDevice>>(emptyMap())
    private val connectingTo = MutableStateFlow<String?>(null)

    val events: SharedFlow<DeviceEvent> = repository.events

    val uiState: StateFlow<ScanUiState> = combine(
        scanning, scanError, seen, repository.knownDevices, repository.connectionState, connectingTo, repository.lanDevices,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val nearby = (values[2] as Map<String, ScannedDevice>).values
            .sortedByDescending { it.rssi }
        @Suppress("UNCHECKED_CAST")
        ScanUiState(
            scanning = values[0] as Boolean,
            scanError = values[1] as String?,
            nearby = nearby,
            known = values[3] as List<KnownDevice>,
            connection = values[4] as ConnectionState,
            connectingTo = values[5] as String?,
            lanStations = (values[6] as Map<String, *>).keys,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScanUiState())

    private var scanJob: Job? = null
    private var pruneJob: Job? = null

    fun startScan() {
        if (scanJob?.isActive == true) return
        scanError.value = null
        scanning.value = true
        scanJob = viewModelScope.launch {
            BleScanner.scan(context).collect { ev ->
                when (ev) {
                    is BleScanner.Event.Seen -> {
                        seen.update { it + (ev.device.address to ev.device) }
                        repository.touchSeen(ev.device.address, ev.device.name)
                    }
                    is BleScanner.Event.Failed -> {
                        scanError.value = ev.message
                        stopScan()
                    }
                }
            }
        }
        pruneJob?.cancel()
        pruneJob = viewModelScope.launch {
            while (isActive) {
                delay(3_000)
                val cutoff = System.currentTimeMillis() - STALE_MS
                seen.update { m -> m.filterValues { it.lastSeenEpochMs >= cutoff } }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        pruneJob?.cancel()
        pruneJob = null
        scanning.value = false
    }

    fun connect(address: String, name: String?) {
        stopScan()
        connectingTo.value = address
        repository.connect(address, name)
    }

    fun connectFake() {
        stopScan()
        connectingTo.value = FakeTransport.FAKE_ADDRESS
        repository.connect(FakeTransport.FAKE_ADDRESS, "AQ-6b40 (fake)", fake = true)
    }

    /** Debug: the same fake, behaving like the LAN pipe (payload_max 1024, GET_TOKEN refused). */
    fun connectFakeLan() {
        stopScan()
        connectingTo.value = FakeTransport.FAKE_LAN_ADDRESS
        repository.connect(FakeTransport.FAKE_LAN_ADDRESS, "AQ-6b40 (fake LAN)", fake = true)
    }

    fun clearConnecting() {
        connectingTo.value = null
    }

    fun forget(address: String) {
        viewModelScope.launch { repository.forget(address) }
    }

    override fun onCleared() {
        stopScan()
    }

    companion object {
        const val STALE_MS = 15_000L
    }
}
