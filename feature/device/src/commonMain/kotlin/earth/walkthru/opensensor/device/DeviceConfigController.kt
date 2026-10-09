package earth.walkthru.opensensor.device

import earth.walkthru.aqsync.data.SensorLabel
import earth.walkthru.aqsync.data.WifiScanState
import earth.walkthru.aqsync.data.protocol.ConfigPatch
import earth.walkthru.aqsync.data.protocol.ConfigValidationException
import earth.walkthru.aqsync.data.protocol.PairMode
import earth.walkthru.aqsync.data.protocol.WifiState
import earth.walkthru.aqsync.settings.DetailLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A question the Device section asks before it changes the device. Only one is open at a time. */
sealed interface DeviceConfigDialog {
    /** `REBOOT`. The link drops and comes back. */
    data object Reboot : DeviceConfigDialog

    /** `lan.rotate_token`. Every phone holding the old token loses Wi-Fi access. */
    data object RotateToken : DeviceConfigDialog

    /** `ble.clear_bonds`. Every paired phone pairs again. */
    data object ForgetBonds : DeviceConfigDialog

    /** `ble.pair=none`. Any phone in range can connect without a passkey. */
    data object PairingNone : DeviceConfigDialog
}

/**
 * The shared Device section logic, platform free. It combines the [source] snapshot with the detail
 * level, owns the four confirmation dialogs, builds and validates every `SET_CONFIG` with
 * [ConfigPatch], and polls the config while the sensor joins a Wi-Fi network. Nothing that reboots,
 * rotates the token, forgets bonds or turns the passkey off reaches the [source] before the user
 * confirmed it. Android hosts it in a ViewModel, iOS in AppRoot.
 *
 * With [readOnReady] the controller reads the config each time the link turns ready with no config
 * yet. iOS needs it, because nothing else reads the config there. Android's repository reads it on
 * connect, so Android leaves it off.
 */
class DeviceConfigController(
    private val scope: CoroutineScope,
    private val source: DeviceConfigSource,
    detailLevel: Flow<DetailLevel>,
    readOnReady: Boolean = false,
) {
    val state: StateFlow<DeviceConfigUiState> = combine(source.snapshot, detailLevel) { snapshot, level ->
        DeviceConfigUiState(snapshot, level)
    }.stateIn(scope, SharingStarted.Eagerly, DeviceConfigUiState(source.snapshot.value))

    private val _dialog = MutableStateFlow<DeviceConfigDialog?>(null)
    val dialog: StateFlow<DeviceConfigDialog?> = _dialog.asStateFlow()

    private val _notices = MutableSharedFlow<DeviceConfigNotice>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Outcomes to show as a snackbar, from this controller and from the platform. */
    val notices: SharedFlow<DeviceConfigNotice> = _notices.asSharedFlow()

    private var sensorSaving = false

    init {
        scope.launch { source.notices.collect { _notices.tryEmit(it) } }
        if (readOnReady) {
            scope.launch {
                source.snapshot.map { DeviceConfigUiState(it).let { s -> s.ready && s.isV2 && it.config == null } }
                    .distinctUntilChanged()
                    .collect { unread -> if (unread) source.refreshConfig() }
            }
        }
        // While the sensor reports Wi-Fi `connecting` on a ready link, read the config every 3 s for
        // up to a minute, so the card shows the join as it happens. Stops when the screen goes.
        scope.launch {
            source.snapshot.map { it.config?.config?.wifi?.wifiState == WifiState.CONNECTING && it.connection.isReady }
                .distinctUntilChanged()
                .collectLatest { polling ->
                    if (polling) {
                        repeat(WIFI_POLL_ATTEMPTS) {
                            delay(WIFI_POLL_MS)
                            source.refreshConfig()
                        }
                    }
                }
        }
    }

    private val now: DeviceConfigUiState get() = DeviceConfigUiState(source.snapshot.value, state.value.level)

    fun refresh() {
        if (now.ready && now.isV2) source.refreshConfig()
    }

    /** Mode `none` opens its confirmation first. The other modes are sent as they are. */
    fun savePairing(choice: PairingChoice) {
        if (!now.canEdit) return
        if (choice.mode == PairMode.NONE) {
            _dialog.value = DeviceConfigDialog.PairingNone
            return
        }
        apply(DeviceConfigChange.PAIRING) {
            blePair(choice.mode)
            if (choice.mode == PairMode.FIXED && !choice.pin.isNullOrEmpty()) blePin(choice.pin)
        }
    }

    fun requestReboot() {
        if (now.canDisrupt) _dialog.value = DeviceConfigDialog.Reboot
    }

    fun requestRotateToken() {
        if (now.canDisrupt) _dialog.value = DeviceConfigDialog.RotateToken
    }

    fun requestForgetBonds() {
        if (now.canDisrupt) _dialog.value = DeviceConfigDialog.ForgetBonds
    }

    /**
     * The user confirmed the open dialog. The dialog closes first, so a second tap sends nothing. The
     * link is checked again, because a sync may have started while the dialog was open.
     */
    fun confirm() {
        val open = _dialog.value ?: return
        _dialog.value = null
        val s = now
        when (open) {
            DeviceConfigDialog.Reboot -> if (s.canDisrupt) source.reboot() else _notices.tryEmit(DeviceConfigNotice.Busy)
            DeviceConfigDialog.RotateToken -> if (s.canDisrupt) source.rotateToken() else _notices.tryEmit(DeviceConfigNotice.Busy)
            DeviceConfigDialog.ForgetBonds -> if (s.canDisrupt) source.clearBonds() else _notices.tryEmit(DeviceConfigNotice.Busy)
            DeviceConfigDialog.PairingNone -> if (s.canEdit) {
                apply(DeviceConfigChange.PAIRING) { blePair(PairMode.NONE) }
            } else {
                _notices.tryEmit(DeviceConfigNotice.Busy)
            }
        }
    }

    /** Cancel, or a tap outside. Nothing is sent. */
    fun dismiss() {
        _dialog.value = null
    }

    fun setWifiOn(on: Boolean) {
        if (now.canEdit && now.snapshot.config?.config?.wifi != null) {
            apply(if (on) DeviceConfigChange.WIFI_ON else DeviceConfigChange.WIFI_OFF) { wifiOn(on) }
        }
    }

    fun scanWifi() {
        if (now.canEdit && now.snapshot.wifiScan !is WifiScanState.Scanning) source.scanWifi()
    }

    fun clearWifiScan() = source.clearWifiScan()

    /** SSID and passphrase in one `SET_CONFIG`, Wi-Fi on. An open network sends an empty passphrase. */
    fun joinWifi(join: WifiJoin) {
        if (!now.canEdit) return
        apply(DeviceConfigChange.WIFI_SENT) {
            wifiSsid(join.ssid)
            wifiPsk(if (join.open) "" else join.psk)
            wifiOn(true)
        }
    }

    fun setLanOn(on: Boolean) {
        if (now.canEdit && now.snapshot.config?.config?.lan != null) {
            apply(if (on) DeviceConfigChange.LAN_ON else DeviceConfigChange.LAN_OFF) { lanOn(on) }
        }
    }

    /** The token travels over Bluetooth only. */
    fun fetchToken() {
        if (now.canEdit && now.overBle) source.fetchToken()
    }

    fun reconnect() = source.reconnect()

    fun reconnectOverBluetooth() {
        if (now.ready && !now.snapshot.busy) source.reconnectOverBluetooth()
    }

    /**
     * Save the fitted module from its sticker. Only a PMS5003T sticker with a real batch date is
     * sent. The phone keeps its record only after the board echoes the same vendor, model, serial
     * and estimate switch.
     */
    fun saveSensor(sticker: String, batchEstimate: Boolean, stickerSource: String) {
        val label = SensorLabel.parse(sticker)
        if (label == null || label.model != SUPPORTED_MODEL) {
            _notices.tryEmit(DeviceConfigNotice.StickerInvalid)
            return
        }
        val s = now
        if (!s.canEdit || s.snapshot.syncRunning || !s.capabilities.sensorIdentity || sensorSaving) {
            _notices.tryEmit(DeviceConfigNotice.SensorBusy)
            return
        }
        val patch = try {
            ConfigPatch.build { sensorIdentity(label.serial, batchEstimate) }
        } catch (e: ConfigValidationException) {
            _notices.tryEmit(DeviceConfigNotice.Problem(e.problem))
            return
        }
        sensorSaving = true
        scope.launch {
            try {
                val acknowledged = source.applySensorConfig(patch).config.sensor
                val confirmed = acknowledged?.serial == label.serial && acknowledged.model == label.model &&
                    acknowledged.vendor == label.vendor && acknowledged.batchCandidate == (if (batchEstimate) 1 else 0)
                if (!confirmed) {
                    _notices.tryEmit(DeviceConfigNotice.SensorNotConfirmed)
                    return@launch
                }
                source.recordSensor(label.serial, label.vendor, label.model, label.batch, stickerSource)
                _notices.tryEmit(DeviceConfigNotice.SensorSaved(batchEstimate))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _notices.tryEmit(DeviceConfigNotice.SensorFailed)
            } finally {
                sensorSaving = false
            }
        }
    }

    private fun apply(change: DeviceConfigChange, block: ConfigPatch.Builder.() -> Unit) {
        val patch = try {
            ConfigPatch.build(block)
        } catch (e: ConfigValidationException) {
            _notices.tryEmit(DeviceConfigNotice.Problem(e.problem))
            return
        }
        source.applyConfig(patch, change)
    }

    companion object {
        const val WIFI_POLL_MS = 3_000L
        const val WIFI_POLL_ATTEMPTS = 20

        /** The only module the sticker card saves today. The others are listed but not supported. */
        const val SUPPORTED_MODEL = "PMS5003T"
    }
}
