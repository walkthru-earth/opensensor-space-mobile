package earth.walkthru.aqsync.ui.device

import android.content.Context
import android.content.IntentSender
import android.util.Log
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
import earth.walkthru.aqsync.data.DeviceMessage
import earth.walkthru.aqsync.data.DeviceOwnership
import earth.walkthru.aqsync.data.TimedStatus
import earth.walkthru.aqsync.data.TransportFactory
import earth.walkthru.aqsync.data.TransportPreference
import earth.walkthru.aqsync.data.WifiScanState
import earth.walkthru.aqsync.data.background.AutoSyncScheduler
import earth.walkthru.aqsync.data.background.CompanionPresence
import earth.walkthru.aqsync.data.ble.ConfigSnapshot
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.StationStatus
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.fake.FakeTransport
import earth.walkthru.aqsync.data.history.DeviceCapabilities
import earth.walkthru.aqsync.data.lan.LanDevice
import earth.walkthru.aqsync.data.local.database.KnownDevice
import earth.walkthru.aqsync.data.local.database.KnownDeviceDao
import earth.walkthru.aqsync.data.local.database.SensorInstallation
import earth.walkthru.aqsync.data.local.database.SensorInstallationDao
import earth.walkthru.aqsync.data.protocol.ConfigPatch
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.settings.AppSettings
import earth.walkthru.aqsync.data.settings.DetailLevel
import earth.walkthru.aqsync.ui.components.ScreenMessage
import earth.walkthru.opensensor.device.DeviceConfigChange
import earth.walkthru.opensensor.device.DeviceConfigController
import earth.walkthru.opensensor.device.DeviceConfigNotice
import earth.walkthru.opensensor.device.DeviceConfigSnapshot
import earth.walkthru.opensensor.device.DeviceConfigSource
import earth.walkthru.opensensor.device.SensorRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNot
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
    /** A background worker or Collect all holds this device, see [StationStatus]. */
    val heldInBackground: Boolean = false,
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
    val canEdit: Boolean get() = ready && isV2 && !busy && !heldInBackground

    /** The status every station page reads, the same rule Today and Files use. */
    val station: StationStatus get() = StationStatus(connection, syncRunning = syncRunning, heldInBackground = heldInBackground)

    /**
     * Actions that drop the link (reboot, rotate token, forget bonds) are also refused while a
     * transfer runs: a `REBOOT` mid-sync would kill the download and finalize the RAM batch under it.
     */
    val canDisrupt: Boolean get() = canEdit && !station.busy
    val overBle: Boolean get() = transport == TransportKind.BLE || transport == TransportKind.FAKE

    /** What the shared Device section reads. The LAN token itself never leaves the repository. */
    fun toConfigSnapshot(sensorRecord: SensorRecord?) = DeviceConfigSnapshot(
        address = address,
        name = name,
        connection = connection,
        transport = transport,
        info = info,
        config = config,
        wifiScan = wifiScan,
        lanSeen = onLan != null,
        lanEndpoint = onLan?.let { "${it.host?.hostAddress}:${it.port}" },
        lanDiscoveryError = lanDiscoveryError,
        hasLanToken = known?.hasLanToken == true,
        busy = busy,
        syncRunning = syncRunning,
        heldInBackground = heldInBackground,
        sensorRecord = sensorRecord,
    )
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
    val lanReconnectAvailable: StateFlow<Boolean> = combine(enrolledDevice, repository.lanDevices) { known, discovered ->
        hasLanReconnectTarget(known, discovered)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    private val fakeEndpoint = deviceAddress == FakeTransport.FAKE_ADDRESS || deviceAddress == FakeTransport.FAKE_LAN_ADDRESS

    val sensorInstallation: StateFlow<SensorInstallation?> = sensorInstallationDao.observeCurrent(deviceAddress)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val sensorHistory: StateFlow<List<SensorInstallation>> = sensorInstallationDao.observeHistory(deviceAddress)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @AssistedFactory
    interface Factory {
        fun create(deviceAddress: String): DeviceViewModel
    }

    private val localEvents = MutableSharedFlow<ScreenMessage>(extraBufferCapacity = 4)

    private val _launchRequests = MutableSharedFlow<IntentSender>(extraBufferCapacity = 1)

    /** System dialogs the route must launch (`StartIntentSenderForResult`), the companion association confirm. Read-only. */
    val launchRequests: SharedFlow<IntentSender> = _launchRequests.asSharedFlow()
    private val companionBusy = MutableStateFlow(false)

    /** Set while a companion setup dropped a Bluetooth session that should come back afterwards. */
    private var reconnectAfterCompanion: Pair<String, String?>? = null

    /**
     * Repository snackbar events plus local validation errors. A secret refused on LAN goes to the
     * shared controller instead, whose snackbar offers the Bluetooth reconnect.
     */
    val events: Flow<ScreenMessage> = merge(
        repository.events.screenMessages(),
        localEvents,
    )

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
    private val activity = combine(repository.busy, repository.sync, repository.activeTransport, repository.ownership) { busy, sync, transport, owner ->
        Activity(busy, sync.running, transport, owner == DeviceOwnership.BACKGROUND)
    }

    val uiState: StateFlow<DeviceUiState> = combine(
        core,
        repository.wifiScan,
        lan,
        activity,
        combine(settings.detailLevel, companionBusy) { a, b -> a to b },
    ) { base, scan, (lanDevices, lanErr, known), activity, (level, companionBusy) ->
        base.copy(
            transport = activity.transport,
            wifiScan = scan,
            known = known,
            onLan = base.info?.station?.let { lanDevices[it] },
            lanDiscoveryError = lanErr,
            busy = activity.busy,
            syncRunning = activity.syncRunning,
            heldInBackground = activity.heldInBackground,
            detailLevel = level,
            companionSupported = companionPresence.supported && !base.isFake,
            companionBusy = companionBusy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceUiState())

    /** This phone's record of the fitted module, for the sticker card. */
    private val sensorRecord = combine(sensorInstallation, sensorHistory) { current, history ->
        current?.let { SensorRecord(it.serial, history.size) }
    }

    /**
     * The shared Device section over this repository. Pairing, Wi-Fi,
     * Sync over Wi-Fi, paired phones and the sticker card run through it, with its four
     * confirmations and its Wi-Fi join poll. Repository outcomes still arrive through [events].
     */
    private val configSource = RepositoryDeviceConfigSource(
        snapshot = combine(uiState, sensorRecord) { s, record -> s.toConfigSnapshot(record) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, DeviceConfigSnapshot(address = deviceAddress)),
    )
    val controller = DeviceConfigController(viewModelScope, configSource, settings.detailLevel)

    fun disconnect() = repository.disconnect()

    private inner class RepositoryDeviceConfigSource(override val snapshot: StateFlow<DeviceConfigSnapshot>) : DeviceConfigSource {
        override val notices: Flow<DeviceConfigNotice> = repository.events.configNotices()

        override fun refreshConfig() = repository.refreshConfig()
        override suspend fun readLocation() = repository.readLocation()
        override suspend fun writeLocation(patch: ConfigPatch) = repository.writeLocation(patch)

        override fun applyConfig(patch: ConfigPatch, change: DeviceConfigChange) = repository.applyConfig(
            patch,
            when (change) {
                DeviceConfigChange.PAIRING -> DeviceMessage.ConfigChange.PAIRING
                DeviceConfigChange.WIFI_SENT -> DeviceMessage.ConfigChange.WIFI_SENT
                DeviceConfigChange.WIFI_ON -> DeviceMessage.ConfigChange.WIFI_ON
                DeviceConfigChange.WIFI_OFF -> DeviceMessage.ConfigChange.WIFI_OFF
                DeviceConfigChange.LAN_ON -> DeviceMessage.ConfigChange.LAN_ON
                DeviceConfigChange.LAN_OFF -> DeviceMessage.ConfigChange.LAN_OFF
                DeviceConfigChange.BONDS_CLEARED -> DeviceMessage.ConfigChange.BONDS_CLEARED
            },
        )

        override fun reboot() = repository.rebootAndReconnect()
        override fun scanWifi() = repository.startWifiScan()
        override fun clearWifiScan() = repository.clearWifiScan()
        override fun fetchToken() = repository.fetchToken()
        override fun rotateToken() = repository.rotateToken()
        override fun clearBonds() = repository.clearBonds()
        override suspend fun applySensorConfig(patch: ConfigPatch): ConfigSnapshot = repository.applySensorConfig(patch)

        /** Room keeps the installation with the station and device id from `INFO`. */
        override suspend fun recordSensor(serial: String, vendor: String, model: String, batch: String, source: String) {
            val info = uiState.value.info
            val station = checkNotNull(info?.station) { "no station in INFO" }
            sensorInstallationDao.record(
                SensorInstallation(
                    address = deviceAddress,
                    station = station,
                    deviceId = info.dev,
                    vendor = vendor,
                    model = model,
                    serial = serial,
                    batch = batch,
                    startedAtEpochMs = System.currentTimeMillis(),
                    source = source,
                ),
            )
        }

        override fun reconnect() = this@DeviceViewModel.reconnect()
        override fun reconnectOverBluetooth() = this@DeviceViewModel.reconnectOverBluetooth()
    }

    // ------------------------------------------------------------ auto-sync

    /** Flip the Room row; the scheduler observes the count and (un)registers the periodic work. */
    fun setAutoSync(enabled: Boolean) {
        val addr = uiState.value.address ?: return
        viewModelScope.launch {
            repository.setAutoSync(addr, enabled)
            localEvents.tryEmit(
                ScreenMessage.Text(
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
                onError = { reason ->
                    Log.w("DeviceViewModel", "companion association failed ($reason)")
                    finishCompanion(addr, context.getString(R.string.msg_wake_failed))
                },
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
            localEvents.tryEmit(ScreenMessage.Text(context.getString(R.string.msg_wake_off)))
        }
    }

    private fun finishCompanion(addr: String, message: String, ok: Boolean = false) {
        companionBusy.value = false
        localEvents.tryEmit(ScreenMessage.Text(message, isError = !ok))
        reconnectAfterCompanion?.let { (address, name) ->
            reconnectAfterCompanion = null
            if (address == addr) repository.connect(address, name, fake = false, preference = TransportPreference.BLE_ONLY)
        }
    }

    // -------------------------------------------------------------- links

    private fun reconnectOverBluetooth() {
        val s = uiState.value
        val addr = s.address ?: return
        repository.disconnect()
        repository.connect(addr, s.name, fake = s.isFake, preference = TransportPreference.BLE_ONLY)
    }

    fun reconnect() {
        val s = uiState.value
        repository.connect(deviceAddress, s.name, fake = fakeEndpoint)
    }

    fun reconnectLanOnly() {
        val s = uiState.value
        repository.connect(deviceAddress, s.name, preference = TransportPreference.LAN_ONLY)
    }
}

/** What the link is doing for the Device page: a command in flight, a transfer, the pipe and the owner. */
private data class Activity(val busy: Boolean, val syncRunning: Boolean, val transport: TransportKind?, val heldInBackground: Boolean)

private fun DeviceEvent.isSecretNeedsBluetooth(): Boolean =
    this is DeviceEvent.Error && message == DeviceMessage.SecretNeedsBluetooth

/** Repository events for the Device page snackbar. A secret refused on LAN is left to [configNotices]. */
internal fun Flow<DeviceEvent>.screenMessages(): Flow<ScreenMessage> =
    filterNot { it.isSecretNeedsBluetooth() }.map(ScreenMessage::Device)

/** The secret refused on LAN, as the shared controller's notice that offers the Bluetooth reconnect. */
internal fun Flow<DeviceEvent>.configNotices(): Flow<DeviceConfigNotice> =
    filter { it.isSecretNeedsBluetooth() }.map { DeviceConfigNotice.SecretNeedsBluetooth }
