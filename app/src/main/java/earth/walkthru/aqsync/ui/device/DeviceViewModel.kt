package earth.walkthru.aqsync.ui.device

import android.content.IntentSender
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
import earth.walkthru.aqsync.data.background.CompanionPresence
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
    /** The phone has the companion-device feature (the "wake when nearby" setup can be offered). */
    val companionSupported: Boolean = false,
    /** A companion association request is in flight (system dialog pending or just launched). */
    val companionBusy: Boolean = false,
) {
    /** Per-device Auto-sync switch (Room `known_device.autoSync`). */
    val autoSync: Boolean get() = known?.autoSync == true

    /** "Wake when nearby": a Companion Device Manager association is recorded for this logger. */
    val companion: Boolean get() = known?.hasCompanion == true

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
    private val companionPresence: CompanionPresence,
    settings: AppSettings,
) : ViewModel() {

    private val localEvents = MutableSharedFlow<DeviceEvent>(extraBufferCapacity = 4)

    /** System dialogs the screen must launch (`StartIntentSenderForResult`): the companion association confirm. */
    val launchRequests = MutableSharedFlow<IntentSender>(extraBufferCapacity = 1)
    private val companionBusy = MutableStateFlow(false)

    /** Set while a companion setup dropped a Bluetooth session that should come back afterwards. */
    private var reconnectAfterCompanion: Pair<String, String?>? = null

    /** Repository snackbar events plus local validation errors. */
    val events: Flow<DeviceEvent> = merge(repository.events, localEvents)

    private val core = combine(
        repository.session,
        repository.connectionState,
        repository.info,
        repository.status,
        repository.config,
    ) { session, conn, info, status, config ->
        DeviceUiState(
            address = session?.address,
            name = session?.transport?.displayName,
            connection = conn,
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

    // The transport chip reads the repository's activeTransport flow, not the session object (see there).
    private val activity = combine(repository.busy, repository.sync, repository.activeTransport) { busy, sync, transport ->
        Triple(busy, sync.running, transport)
    }

    val uiState: StateFlow<DeviceUiState> = combine(
        core,
        repository.wifiScan,
        lan,
        activity,
        combine(settings.advancedMode, companionBusy) { a, b -> a to b },
    ) { base, scan, (lanDevices, lanErr, known), (busy, syncRunning, transport), (advanced, companionBusy) ->
        base.copy(
            transport = transport,
            wifiScan = scan,
            known = known,
            onLan = base.info?.station?.let { lanDevices[it] },
            lanDiscoveryError = lanErr,
            busy = busy,
            syncRunning = syncRunning,
            advancedMode = advanced,
            companionSupported = companionPresence.supported && !base.isFake,
            companionBusy = companionBusy,
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
                    if (enabled) {
                        "Auto-sync on: new files are pulled about every ${AutoSyncScheduler.PERIOD_MINUTES} min, even with the app closed."
                    } else {
                        "Auto-sync off for this device."
                    }
                )
            )
        }
    }

    // ------------------------------------------------------ wake when nearby

    /**
     * Start the one-time Companion Device Manager association for this logger. The system dialog
     * needs the device to be advertising, which it is not while this phone holds its single BLE
     * connection — so a Bluetooth session is dropped first and restored after the dialog; a LAN
     * session stays. The [IntentSender] reaches the screen through [launchRequests].
     */
    fun setupCompanion() {
        val s = uiState.value
        val addr = s.address ?: return
        if (!s.companionSupported || s.companionBusy) return
        companionBusy.value = true
        viewModelScope.launch {
            if (s.overBle && s.connection != ConnectionState.Disconnected) {
                reconnectAfterCompanion = addr to s.name
                repository.disconnectNow()
            }
            companionPresence.associate(
                addr,
                Dispatchers.Main.asExecutor(),
                onIntent = { sender ->
                    if (!launchRequests.tryEmit(sender)) {
                        finishCompanion(addr, "Could not open the system dialog")
                    }
                },
                onError = { reason -> finishCompanion(addr, "Wake when nearby not set up: $reason") },
            )
        }
    }

    /** The system confirm dialog returned; [approved] when the user accepted the association. */
    fun onCompanionResult(approved: Boolean) {
        val addr = uiState.value.address ?: reconnectAfterCompanion?.first ?: return
        viewModelScope.launch {
            if (!approved) {
                finishCompanion(addr, "Wake when nearby not set up (cancelled)")
                return@launch
            }
            val id = companionPresence.onAssociated(addr)
            if (id == null) {
                finishCompanion(addr, "Wake when nearby not set up: the system recorded no association")
                return@launch
            }
            repository.setCompanionAssociation(addr, id)
            finishCompanion(addr, "Wake when nearby is on: a sync starts when this phone comes into range of the logger.", ok = true)
        }
    }

    /** Remove the association ("wake when nearby" off); the Auto-sync switch and periodic run are untouched. */
    fun removeCompanion() {
        val s = uiState.value
        val addr = s.address ?: return
        viewModelScope.launch {
            companionPresence.disassociate(addr, s.known?.companionAssociationId)
            repository.setCompanionAssociation(addr, null)
            localEvents.tryEmit(DeviceEvent.Info("Wake when nearby is off."))
        }
    }

    private fun finishCompanion(addr: String, message: String, ok: Boolean = false) {
        companionBusy.value = false
        localEvents.tryEmit(if (ok) DeviceEvent.Info(message) else DeviceEvent.Error(message))
        reconnectAfterCompanion?.let { (address, name) ->
            reconnectAfterCompanion = null
            if (address == addr) repository.connect(address, name, fake = false, preference = TransportPreference.BLE_ONLY)
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
