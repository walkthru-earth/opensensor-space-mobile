package earth.walkthru.aqsync.ui.device

import android.content.Context
import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.DeviceConnectionManager
import earth.walkthru.aqsync.data.DeviceEvent
import earth.walkthru.aqsync.data.SensorLabel
import earth.walkthru.aqsync.data.TimedStatus
import earth.walkthru.aqsync.data.TransportFactory
import earth.walkthru.aqsync.data.TransportPreference
import earth.walkthru.aqsync.data.WifiScanState
import earth.walkthru.aqsync.data.background.AutoSyncScheduler
import earth.walkthru.aqsync.data.background.CompanionPresence
import earth.walkthru.aqsync.data.ble.ConfigSnapshot
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.fake.FakeTransport
import earth.walkthru.aqsync.data.history.DeviceCapabilities
import earth.walkthru.aqsync.data.lan.LanDevice
import earth.walkthru.aqsync.data.local.database.KnownDevice
import earth.walkthru.aqsync.data.local.database.KnownDeviceDao
import earth.walkthru.aqsync.data.local.database.SensorInstallation
import earth.walkthru.aqsync.data.local.database.SensorInstallationDao
import earth.walkthru.aqsync.data.protocol.ConfigPatch
import earth.walkthru.aqsync.data.protocol.ConfigValidationException
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.PairMode
import earth.walkthru.aqsync.data.protocol.WifiState
import earth.walkthru.aqsync.data.settings.AppSettings
import earth.walkthru.aqsync.data.settings.DetailLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    val detailLevel: DetailLevel = DetailLevel.SIMPLE,
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

    /** What this device can do, from `INFO` (shared mapping, no schema strings in the UI). */
    val capabilities: DeviceCapabilities get() = DeviceCapabilities.from(info)
    val advancedMode: Boolean get() = detailLevel.developer
    val isV2: Boolean get() = capabilities.configurable
    val ready: Boolean get() = connection.isReady
    val canEdit: Boolean get() = ready && isV2 && !busy

    /**
     * Actions that drop the link (reboot, rotate token, forget bonds) are also refused while a
     * transfer runs: a `REBOOT` mid-sync would kill the download and finalize the RAM batch under it.
     */
    val canDisrupt: Boolean get() = canEdit && !syncRunning
    val overBle: Boolean get() = transport == TransportKind.BLE || transport == TransportKind.FAKE
}

@HiltViewModel(assistedFactory = DeviceViewModel.Factory::class)
class DeviceViewModel @AssistedInject constructor(
    @param:ApplicationContext private val context: Context,
    manager: DeviceConnectionManager,
    @Assisted private val deviceAddress: String,
    private val transportFactory: TransportFactory,
    private val companionPresence: CompanionPresence,
    settings: AppSettings,
    knownDeviceDao: KnownDeviceDao,
    private val sensorInstallationDao: SensorInstallationDao,
) : ViewModel() {
    private val repository = manager.controller(deviceAddress)
    private val enrolledDevice = knownDeviceDao.observeAll().map { devices -> devices.firstOrNull { it.address == deviceAddress } }
    private val fakeEndpoint = deviceAddress == FakeTransport.FAKE_ADDRESS || deviceAddress == FakeTransport.FAKE_LAN_ADDRESS

    val sensorInstallation: StateFlow<SensorInstallation?> = sensorInstallationDao.observeCurrent(deviceAddress)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val sensorHistory: StateFlow<List<SensorInstallation>> = sensorInstallationDao.observeHistory(deviceAddress)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @AssistedFactory
    interface Factory {
        fun create(deviceAddress: String): DeviceViewModel
    }

    companion object {
        const val WIFI_POLL_MS = 3_000L
        const val WIFI_POLL_ATTEMPTS = 20
    }

    private val localEvents = MutableSharedFlow<DeviceEvent>(extraBufferCapacity = 4)

    private val _launchRequests = MutableSharedFlow<IntentSender>(extraBufferCapacity = 1)

    /** System dialogs the route must launch (`StartIntentSenderForResult`), the companion association confirm. Read-only. */
    val launchRequests: SharedFlow<IntentSender> = _launchRequests.asSharedFlow()
    private val companionBusy = MutableStateFlow(false)

    /** Set while a companion setup dropped a Bluetooth session that should come back afterwards. */
    private var reconnectAfterCompanion: Pair<String, String?>? = null

    /** Repository snackbar events plus local validation errors. */
    val events: Flow<DeviceEvent> = merge(repository.events, localEvents)

    private val sessionCore = combine(
        repository.session,
        repository.connectionState,
        repository.info,
        repository.status,
        repository.config,
    ) { session, conn, info, status, config ->
        DeviceUiState(
            address = session?.address ?: deviceAddress,
            name = session?.transport?.displayName,
            connection = conn,
            endpoint = session?.transport?.endpoint,
            info = info,
            status = status,
            config = config,
            isFake = repository.isFake || fakeEndpoint,
            bonded = session?.address?.let { repository.isFake || transportFactory.isBonded(it) } == true,
        )
    }
    private val core = combine(sessionCore, enrolledDevice) { base, enrolled -> base.copy(name = base.name ?: enrolled?.name) }

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
        combine(settings.detailLevel, companionBusy) { a, b -> a to b },
    ) { base, scan, (lanDevices, lanErr, known), (busy, syncRunning, transport), (level, companionBusy) ->
        base.copy(
            transport = transport,
            wifiScan = scan,
            known = known,
            onLan = base.info?.station?.let { lanDevices[it] },
            lanDiscoveryError = lanErr,
            busy = busy,
            syncRunning = syncRunning,
            detailLevel = level,
            companionSupported = companionPresence.supported && !base.isFake,
            companionBusy = companionBusy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceUiState())

    init {
        // While the device reports Wi-Fi `connecting` on a ready link, re-read the config every 3 s
        // (up to a minute) so the card shows the join progress. This used to be a LaunchedEffect in
        // the composable. It now lives with the screen's ViewModel and stops when the entry is popped.
        viewModelScope.launch {
            combine(repository.config, repository.connectionState) { config, conn ->
                config?.config?.wifi?.wifiState == WifiState.CONNECTING && conn.isReady
            }.distinctUntilChanged().collectLatest { polling ->
                if (polling) {
                    repeat(WIFI_POLL_ATTEMPTS) {
                        delay(WIFI_POLL_MS)
                        repository.refreshConfig()
                    }
                }
            }
        }
    }

    fun refreshConfig() = repository.refreshConfig()
    fun disconnect() = repository.disconnect()

    /** Save the physical module label only after the board echoes its full identity. */
    fun setSensorIdentity(sticker: String, batchCandidate: Boolean, source: String) {
        val state = uiState.value
        val label = SensorLabel.parse(sticker)
        if (label == null || label.model != "PMS5003T") {
            localEvents.tryEmit(DeviceEvent.Error(context.getString(R.string.msg_sticker_invalid)))
            return
        }
        if (!state.canEdit || state.syncRunning || !state.capabilities.sensorIdentity) {
            localEvents.tryEmit(DeviceEvent.Error(context.getString(R.string.msg_sensor_busy)))
            return
        }
        val info = state.info ?: return
        val station = info.station ?: return
        val patch = ConfigPatch.build { sensorIdentity(label.serial, batchCandidate) }
        viewModelScope.launch {
            try {
                val acknowledged = repository.applySensorConfig(patch).config.sensor
                if (acknowledged?.serial != label.serial || acknowledged?.model != label.model ||
                    acknowledged?.vendor != label.vendor || acknowledged?.batchCandidate != (if (batchCandidate) 1 else 0)
                ) {
                    localEvents.tryEmit(DeviceEvent.Error(context.getString(R.string.msg_sensor_not_confirmed)))
                    return@launch
                }
                sensorInstallationDao.record(
                    SensorInstallation(
                        address = deviceAddress,
                        station = station,
                        deviceId = info.dev,
                        vendor = label.vendor,
                        model = label.model,
                        serial = label.serial,
                        batch = label.batch,
                        startedAtEpochMs = System.currentTimeMillis(),
                        source = source,
                    )
                )
                localEvents.tryEmit(DeviceEvent.Info(context.getString(if (batchCandidate) R.string.msg_sensor_saved_on else R.string.msg_sensor_saved_off)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                localEvents.tryEmit(DeviceEvent.Error(context.getString(R.string.msg_sensor_failed, e.message.orEmpty())))
            }
        }
    }

    // ------------------------------------------------------------ auto-sync

    /** Flip the Room row; the scheduler observes the count and (un)registers the periodic work. */
    fun setAutoSync(enabled: Boolean) {
        val addr = uiState.value.address ?: return
        viewModelScope.launch {
            repository.setAutoSync(addr, enabled)
            localEvents.tryEmit(
                DeviceEvent.Info(
                    if (enabled) {
                        context.getString(R.string.files_autosync_event_on, AutoSyncScheduler.PERIOD_MINUTES.toInt())
                    } else {
                        context.getString(R.string.files_autosync_event_off)
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
        if (!s.companionSupported || s.companionBusy || s.busy || s.syncRunning) return
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
                    if (!_launchRequests.tryEmit(sender)) {
                        finishCompanion(addr, context.getString(R.string.msg_wake_no_dialog))
                    }
                },
                onError = { reason -> finishCompanion(addr, context.getString(R.string.msg_wake_failed, reason)) },
            )
        }
    }

    /** The system confirm dialog returned; [approved] when the user accepted the association. */
    fun onCompanionResult(approved: Boolean) {
        val addr = uiState.value.address ?: reconnectAfterCompanion?.first ?: return
        viewModelScope.launch {
            if (!approved) {
                finishCompanion(addr, context.getString(R.string.msg_wake_cancelled))
                return@launch
            }
            val id = companionPresence.onAssociated(addr)
            if (id == null) {
                finishCompanion(addr, context.getString(R.string.msg_wake_no_link))
                return@launch
            }
            repository.setCompanionAssociation(addr, id)
            finishCompanion(addr, context.getString(R.string.msg_wake_on), ok = true)
        }
    }

    /** Remove the association ("wake when nearby" off); the Auto-sync switch and periodic run are untouched. */
    fun removeCompanion() {
        val s = uiState.value
        val addr = s.address ?: return
        viewModelScope.launch {
            companionPresence.disassociate(addr, s.known?.companionAssociationId)
            repository.setCompanionAssociation(addr, null)
            localEvents.tryEmit(DeviceEvent.Info(context.getString(R.string.msg_wake_off)))
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
        repository.applyConfig(patch, context.getString(R.string.msg_pairing_saved))
    }

    fun setPin(pin: String) {
        val patch = try {
            ConfigPatch.build { blePin(pin) }
        } catch (e: ConfigValidationException) {
            localEvents.tryEmit(DeviceEvent.Error(e.message ?: "invalid PIN"))
            return
        }
        repository.applyConfig(patch, context.getString(R.string.msg_pin_saved))
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
        repository.applyConfig(patch, context.getString(R.string.msg_wifi_sent))
    }

    fun setWifiOn(on: Boolean) = repository.applyConfig(ConfigPatch.build { wifiOn(on) }, context.getString(if (on) R.string.msg_wifi_on else R.string.msg_wifi_off))
    fun setLanOn(on: Boolean) = repository.applyConfig(ConfigPatch.build { lanOn(on) }, context.getString(if (on) R.string.msg_lan_on else R.string.msg_lan_off))

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
        repository.connect(deviceAddress, s.name, fake = fakeEndpoint)
    }
}
