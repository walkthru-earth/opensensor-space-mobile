package earth.walkthru.aqsync.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import earth.walkthru.aqsync.data.DeviceConnectionRepository
import earth.walkthru.aqsync.data.DeviceEvent
import earth.walkthru.aqsync.data.TimedStatus
import earth.walkthru.aqsync.data.TransportFactory
import earth.walkthru.aqsync.data.TransportPreference
import earth.walkthru.aqsync.data.WifiScanState
import earth.walkthru.aqsync.data.background.AutoSyncScheduler
import earth.walkthru.aqsync.data.ble.ConfigSnapshot
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.lan.LanDevice
import earth.walkthru.aqsync.data.local.database.KnownDevice
import earth.walkthru.aqsync.data.protocol.ConfigPatch
import earth.walkthru.aqsync.data.protocol.ConfigValidationException
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.PairMode
import earth.walkthru.aqsync.data.settings.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeviceUiState(
    val address: String? = null,
    val name: String? = null,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val transport: TransportKind? = null,
    val endpoint: String? = null,
    val info: DeviceInfo? = null,
    val status: TimedStatus? = null,
    val config: ConfigSnapshot? = null,
    val wifiScan: WifiScanState = WifiScanState.Idle,
    val known: KnownDevice? = null,
    /** This device as currently seen on the LAN (by station), if at all. */
    val onLan: LanDevice? = null,
    val lanDiscoveryError: String? = null,
    /** Phone holds a Bluetooth bond for this address (checked when the screen opens / config refreshes). */
    val bonded: Boolean = false,
    val busy: Boolean = false,
    /** A file transfer is running on this session (interactive or worker-fed). */
    val syncRunning: Boolean = false,
    val isFake: Boolean = false,
    val advancedMode: Boolean = false,
) {
    /** Per-device Auto-sync switch (Room `known_device.autoSync`). */
    val autoSync: Boolean get() = known?.autoSync == true
    /** Unattended BLE needs a bond; LAN needs a stored token. Without either the switch would do nothing. */
    val autoSyncReachable: Boolean get() = known?.hasLanToken == true || bonded
    val isV2: Boolean get() = (info?.proto ?: 1) >= 2
    val ready: Boolean get() = connection.isReady
    val canEdit: Boolean get() = ready && isV2 && !busy
    /**
     * Actions that drop the link (reboot, rotate token, forget bonds) are also refused while a
     * transfer runs: a `REBOOT` mid-sync would kill the download and finalize the RAM batch under it.
     */
    val canDisrupt: Boolean get() = canEdit && !syncRunning
    val overBle: Boolean get() = transport == TransportKind.BLE || transport == TransportKind.FAKE
}

@HiltViewModel
class DeviceViewModel @Inject constructor(
    private val repository: DeviceConnectionRepository,
    private val transportFactory: TransportFactory,
    settings: AppSettings,
) : ViewModel() {

    private val localEvents = MutableSharedFlow<DeviceEvent>(extraBufferCapacity = 4)

    /** Repository snackbar events plus local validation errors. */
    val events: Flow<DeviceEvent> = merge(repository.events, localEvents)

    private val core = combine(
        repository.session, repository.connectionState, repository.info, repository.status, repository.config,
    ) { session, conn, info, status, config ->
        DeviceUiState(
            address = session?.address,
            name = session?.transport?.displayName,
            connection = conn,
            transport = session?.transport?.kind,
            endpoint = session?.transport?.endpoint,
            info = info,
            status = status,
            config = config,
            isFake = repository.isFake,
            bonded = session?.address?.let { repository.isFake || transportFactory.isBonded(it) } == true,
        )
    }

    private val lan = combine(repository.lanDevices, repository.lanDiscoveryError, repository.currentKnownDevice) { devices, err, known ->
        Triple(devices, err, known)
    }

    private val activity = combine(repository.busy, repository.sync) { busy, sync -> busy to sync.running }

    val uiState: StateFlow<DeviceUiState> = combine(
        core, repository.wifiScan, lan, activity, settings.advancedMode,
    ) { base, scan, (lanDevices, lanErr, known), (busy, syncRunning), advanced ->
        base.copy(
            wifiScan = scan,
            known = known,
            onLan = base.info?.station?.let { lanDevices[it] },
            lanDiscoveryError = lanErr,
            busy = busy,
            syncRunning = syncRunning,
            advancedMode = advanced,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceUiState())

    fun refreshConfig() = repository.refreshConfig()
    fun disconnect() = repository.disconnect()

    // ------------------------------------------------------------ auto-sync

    /** Flip the Room row; the scheduler observes the count and (un)registers the periodic work. */
    fun setAutoSync(enabled: Boolean) {
        val addr = uiState.value.address ?: return
        viewModelScope.launch {
            repository.setAutoSync(addr, enabled)
            localEvents.tryEmit(
                DeviceEvent.Info(
                    if (enabled) "Auto-sync on: new files are pulled about every ${AutoSyncScheduler.PERIOD_MINUTES} min, even with the app closed."
                    else "Auto-sync off for this device."
                )
            )
        }
    }

    // ------------------------------------------------------------- pairing

    fun setPairing(mode: PairMode, pin: String?) {
        val patch = try {
            ConfigPatch.build {
                blePair(mode)
                if (mode == PairMode.FIXED && !pin.isNullOrEmpty()) blePin(pin)
            }
        } catch (e: ConfigValidationException) {
            localEvents.tryEmit(DeviceEvent.Error(e.message ?: "invalid value"))
            return
        }
        repository.applyConfig(patch, "Pairing settings stored")
    }

    fun setPin(pin: String) {
        val patch = try {
            ConfigPatch.build { blePin(pin) }
        } catch (e: ConfigValidationException) {
            localEvents.tryEmit(DeviceEvent.Error(e.message ?: "invalid PIN"))
            return
        }
        repository.applyConfig(patch, "PIN stored")
    }

    fun reboot() = repository.rebootAndReconnect()
    fun clearBonds() = repository.clearBonds()

    // ---------------------------------------------------------------- Wi-Fi

    fun scanWifi() = repository.startWifiScan()
    fun clearScan() = repository.clearWifiScan()

    fun joinWifi(ssid: String, psk: String, open: Boolean) {
        val patch = try {
            ConfigPatch.build {
                wifiSsid(ssid)
                wifiPsk(if (open) "" else psk)
                wifiOn(true)
            }
        } catch (e: ConfigValidationException) {
            localEvents.tryEmit(DeviceEvent.Error(e.message ?: "invalid Wi-Fi settings"))
            return
        }
        repository.applyConfig(patch, "Wi-Fi settings sent; the device is connecting")
    }

    fun setWifiOn(on: Boolean) = repository.applyConfig(ConfigPatch.build { wifiOn(on) }, if (on) "Wi-Fi radio on" else "Wi-Fi radio off")
    fun setLanOn(on: Boolean) = repository.applyConfig(ConfigPatch.build { lanOn(on) }, if (on) "LAN sync server on" else "LAN sync server off")

    // ------------------------------------------------------------------ LAN

    fun fetchToken() = repository.fetchToken()
    fun rotateToken() = repository.rotateToken()

    fun reconnectOverBluetooth() {
        val s = uiState.value
        val addr = s.address ?: return
        repository.disconnect()
        repository.connect(addr, s.name, fake = s.isFake, preference = TransportPreference.BLE_ONLY)
    }

    fun reconnect() {
        val s = uiState.value
        val addr = s.address ?: return
        repository.connect(addr, s.name, fake = s.isFake)
    }
}
