package earth.walkthru.opensensor

import androidx.compose.runtime.snapshotFlow
import earth.walkthru.aqsync.data.WifiScanState
import earth.walkthru.aqsync.data.ble.ConfigSnapshot
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.DeviceErrorException
import earth.walkthru.aqsync.data.ble.DeviceSession
import earth.walkthru.aqsync.data.ble.InvalidConfigException
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.ble.WifiUnavailableException
import earth.walkthru.aqsync.data.protocol.ConfigPatch
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.opensensor.device.DeviceConfigChange
import earth.walkthru.opensensor.device.DeviceConfigCommand
import earth.walkthru.opensensor.device.DeviceConfigNotice
import earth.walkthru.opensensor.device.DeviceConfigSnapshot
import earth.walkthru.opensensor.device.DeviceConfigSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock

/** The Swift verbs Device settings uses. Only ids cross, the token stays in the Keychain. */
internal class DeviceConfigLinks(
    /** A fresh link to the sensor with this hardware id, under Today's command guards, or null while busy. */
    val openConfigLink: (String) -> SensorLink?,
    /** Reads the Wi-Fi access token over Bluetooth into the Keychain, the companion page's Save Wi-Fi access. */
    val saveWifiAccess: () -> Unit,
    /** Drops the Keychain token and any Wi-Fi session of this hardware id, after a rotation. */
    val forgetWifiAccess: (String) -> Unit,
    /** The user's open, which cancels Collect all first. For the Reconnect buttons only. */
    val connect: (String) -> Unit,
    /** Explicit bonded Bluetooth recovery, even while this sensor has an authenticated LAN link. */
    val connectBluetooth: (String) -> Unit,
    /** Opens the peripheral without touching Collect all. Swift refuses it while a request holds another link. */
    val reopen: (String) -> Unit,
    val disconnect: () -> Unit,
    /** Browses for sensors on Wi-Fi, which shows the local network prompt the first time. */
    val scanLan: () -> Unit,
    /** The VisionKit sticker scanner, or null where the hardware has none. See [ScanSticker]. */
    val scanSticker: ScanSticker?,
)

/**
 * The iOS side of the shared Device settings (`:feature:device`) for one Bluetooth peripheral.
 * Swift owns the peripheral, so every config request borrows a fresh [SensorLink] through
 * [DeviceConfigLinks.openConfigLink], under the same guards as a Today command, and closing it runs
 * the companion's settle step, so a queued Collect all, Auto-sync or idle close goes on afterwards.
 * Frames go through the redacting recorder Debug shows. The Wi-Fi access token is read and stored
 * by Swift, so it never reaches Kotlin.
 */
internal class IosDeviceConfigSource(
    private val address: String,
    private val state: CompanionState,
    private val devices: DevicesBridge,
    private val links: DeviceConfigLinks,
    private val scope: CoroutineScope,
) : DeviceConfigSource {
    private val lent = Mutex()
    private val config = MutableStateFlow<ConfigSnapshot?>(null)
    private val wifiScan = MutableStateFlow<WifiScanState>(WifiScanState.Idle)
    private val working = MutableStateFlow(false)
    private val _notices = MutableSharedFlow<DeviceConfigNotice>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** This sensor's hardware id from the authenticated LAN session or the open Bluetooth link. */
    private fun device(): String? =
        state.info?.dev?.takeIf { state.connected && state.selectedId.equals(address, ignoreCase = true) }
            ?: state.connectedLanForStation(devices.stationFor(address))?.let { state.lanInfo[it]?.dev }

    private val companion = snapshotFlow {
        val lan = state.connectedLanForStation(devices.stationFor(address))
        val lanInfo = lan?.let(state.lanInfo::get)
        val bleReady = state.connected && state.selectedId.equals(address, ignoreCase = true)
        val ready = lanInfo != null || bleReady
        val info = state.info.takeIf { bleReady } ?: lanInfo
        CompanionView(
            ready = ready,
            info = info,
            transport = if (bleReady) TransportKind.BLE else TransportKind.LAN,
            lanSeen = lan != null || (info != null && info.station in state.lanStations.values),
            busy = state.wifiAccessBusy || state.today.busy || (lan?.let { state.lanSessions[it]?.busy } == true),
            syncRunning = state.syncBusy || (lan?.let { state.lanSessions[it]?.busy } == true),
        )
    }

    override val snapshot: StateFlow<DeviceConfigSnapshot> = combine(
        companion,
        config,
        wifiScan,
        working,
        devices.wifiAccessDevices,
    ) { view, cfg, scan, busy, withAccess ->
        DeviceConfigSnapshot(
            address = address,
            name = devices.nameFor(address),
            connection = if (view.ready) ConnectionState.Ready(0) else ConnectionState.Disconnected,
            transport = view.transport,
            info = view.info,
            config = cfg.takeIf { view.ready },
            wifiScan = scan,
            lanSeen = view.lanSeen,
            hasLanToken = view.info?.dev?.lowercase()?.let { it in withAccess } == true,
            busy = busy || view.busy,
            syncRunning = view.syncRunning,
        )
    }.stateIn(scope, SharingStarted.Eagerly, DeviceConfigSnapshot(address = address, name = devices.nameFor(address), transport = TransportKind.BLE))

    override val notices: Flow<DeviceConfigNotice> = _notices.asSharedFlow()

    override fun refreshConfig() {
        scope.launch {
            // Quiet while the link is lent elsewhere. The Wi-Fi poll calls this every few seconds.
            val result = try {
                borrow { it.getConfig() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: DeviceErrorException) {
                _notices.tryEmit(DeviceConfigNotice.Rejected(DeviceConfigCommand.GET_CONFIG))
                return@launch
            } catch (_: Exception) {
                _notices.tryEmit(DeviceConfigNotice.Failed(DeviceConfigCommand.GET_CONFIG))
                return@launch
            }
            if (result != null) config.value = result
        }
    }

    override fun applyConfig(patch: ConfigPatch, change: DeviceConfigChange) {
        command(DeviceConfigCommand.SET_CONFIG) { session ->
            val snap = session.setConfig(patch)
            config.value = snap
            _notices.tryEmit(DeviceConfigNotice.Saved(change, snap.rebootRequired))
        }
    }

    override fun clearBonds() = applyConfig(ConfigPatch.build { clearBonds() }, DeviceConfigChange.BONDS_CLEARED)

    override fun reboot() {
        if (state.syncBusy) {
            _notices.tryEmit(DeviceConfigNotice.Busy)
            return
        }
        command(DeviceConfigCommand.REBOOT) { session ->
            val frame = session.reboot()
            _notices.tryEmit(DeviceConfigNotice.Rebooting)
            scope.launch {
                delay(frame.delayMs.toLong().coerceIn(REBOOT_MIN_MS, REBOOT_MAX_MS))
                // Only this sensor's link. The user may have opened another one meanwhile.
                withContext(Dispatchers.Main) { if (state.selectedId.equals(address, ignoreCase = true)) links.disconnect() }
                delay(REBOOT_RECONNECT_DELAY_MS)
                withContext(Dispatchers.Main) {
                    if (shouldReopenAfterRestart(address, state.selectedId, devices.collectAllRunning)) links.reopen(address)
                }
            }
        }
    }

    override fun scanWifi() {
        if (wifiScan.value is WifiScanState.Scanning) return
        scope.launch {
            wifiScan.value = WifiScanState.Scanning
            wifiScan.value = try {
                val r = borrow { it.wifiScan() }
                if (r == null) {
                    _notices.tryEmit(DeviceConfigNotice.Busy)
                    WifiScanState.Idle
                } else {
                    WifiScanState.Done(r.accessPoints.sortedByDescending { it.rssi }, now())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: WifiUnavailableException) {
                WifiScanState.Failed("WIFI_SCAN unavailable", unavailable = true)
            } catch (e: DeviceErrorException) {
                WifiScanState.Failed(e.message ?: "WIFI_SCAN rejected", unavailable = false)
            } catch (e: Exception) {
                WifiScanState.Failed("WIFI_SCAN failed (${e.message})", unavailable = false)
            }
        }
    }

    override fun clearWifiScan() {
        wifiScan.value = WifiScanState.Idle
    }

    override fun fetchToken() {
        scope.launch {
            val notice = when (readToken()) {
                null -> DeviceConfigNotice.Busy
                true -> DeviceConfigNotice.TokenStored
                false -> DeviceConfigNotice.Failed(DeviceConfigCommand.GET_TOKEN)
            }
            _notices.tryEmit(notice)
        }
    }

    override fun rotateToken() {
        // GET_TOKEN is granted only through bonded Bluetooth. Without that live link, rotating
        // here would invalidate this phone's only LAN credential before it could save a new one.
        if (!state.connected || !state.selectedId.equals(address, ignoreCase = true)) {
            _notices.tryEmit(DeviceConfigNotice.Busy)
            return
        }
        command(DeviceConfigCommand.ROTATE_TOKEN) { session ->
            config.value = session.setConfig(ConfigPatch.build { rotateToken() })
            // The old token is dead on the sensor. Drop it, then read the new one over Bluetooth.
            device()?.let { id -> withContext(Dispatchers.Main) { links.forgetWifiAccess(id) } }
            scope.launch { _notices.tryEmit(DeviceConfigNotice.TokenRotated(stored = readToken() == true)) }
        }
    }

    override suspend fun readLocation(): earth.walkthru.aqsync.data.protocol.LocationConfig? {
        if (state.syncBusy) throw IllegalStateException("a sync holds the link")
        val result = borrow { it.getLocation() ?: LOCATION_UNSUPPORTED } ?: throw IllegalStateException("link busy")
        return result.takeUnless { it === LOCATION_UNSUPPORTED }
    }

    override suspend fun writeLocation(patch: ConfigPatch): earth.walkthru.aqsync.data.protocol.LocationConfig? {
        if (state.syncBusy || working.value) throw IllegalStateException("link busy")
        working.value = true
        try {
            val result = borrow {
                config.value = it.setConfig(patch)
                it.getLocation() ?: LOCATION_UNSUPPORTED
            } ?: throw IllegalStateException("link busy")
            return result.takeUnless { it === LOCATION_UNSUPPORTED }
        } finally { working.value = false }
    }

    override suspend fun applySensorConfig(patch: ConfigPatch): ConfigSnapshot {
        if (state.syncBusy) throw IllegalStateException("a sync holds the link")
        return borrow { it.setConfig(patch) }?.also { config.value = it } ?: throw IllegalStateException("link busy")
    }

    /** iOS keeps no sensor history of its own yet. The board's echoed identity is the record. */
    override suspend fun recordSensor(serial: String, vendor: String, model: String, batch: String, source: String) = Unit

    override fun reconnect() = links.connect(address)

    override fun reconnectOverBluetooth() = links.connectBluetooth(address)

    /**
     * One device-changing request at a time over a lent link. A refused lend is [DeviceConfigNotice.Busy].
     * A firmware rejection is [DeviceConfigNotice.KeyRejected] for a named key, else
     * [DeviceConfigNotice.Rejected]. Anything else is [DeviceConfigNotice.Failed].
     */
    private fun command(cmd: DeviceConfigCommand, block: suspend (DeviceSession) -> Unit) {
        if (working.value) {
            _notices.tryEmit(DeviceConfigNotice.Busy)
            return
        }
        working.value = true
        scope.launch {
            try {
                if (borrow(block) == null) _notices.tryEmit(DeviceConfigNotice.Busy)
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidConfigException) {
                _notices.tryEmit(DeviceConfigNotice.KeyRejected(e.key))
            } catch (_: DeviceErrorException) {
                _notices.tryEmit(DeviceConfigNotice.Rejected(cmd))
            } catch (_: Exception) {
                _notices.tryEmit(DeviceConfigNotice.Failed(cmd))
            } finally {
                working.value = false
            }
        }
    }

    /**
     * Asks Swift to read the Wi-Fi access token into the Keychain and waits for the outcome. Null when
     * Swift could not start (the link is busy), else whether the token was stored.
     */
    private suspend fun readToken(): Boolean? {
        val started = lent.withLock {
            repeat(LEND_ATTEMPTS) { attempt ->
                withContext(Dispatchers.Main) { links.saveWifiAccess() }
                if (state.wifiAccessBusy) return@withLock true
                if (attempt < LEND_ATTEMPTS - 1) delay(LEND_RETRY_MS)
            }
            false
        }
        if (!started) return null
        val done = withTimeoutOrNull(TOKEN_TIMEOUT_MS) { snapshotFlow { state.wifiAccessBusy }.first { !it } }
        return done != null && state.wifiAccessNotice == CompanionNotice.TOKEN_STORED
    }

    /** Runs [block] over a lent link, closed afterwards so Swift settles. Null when no link was lent. */
    private suspend fun <T> borrow(block: suspend (DeviceSession) -> T): T? = lent.withLock {
        val link = lend() ?: return@withLock null
        try {
            block(link.session(debugFrames.recorderFor(link.transport.address)))
        } finally {
            link.linkClosed()
        }
    }

    /** Swift may still be taking back the previous request's link, so a refusal is retried for about a second. */
    private suspend fun lend(): SensorLink? {
        repeat(LEND_ATTEMPTS) { attempt ->
            val id = device() ?: return null
            withContext(Dispatchers.Main) { links.openConfigLink(id) }?.let { return it }
            if (attempt < LEND_ATTEMPTS - 1) delay(LEND_RETRY_MS)
        }
        return null
    }

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    private data class CompanionView(
        val ready: Boolean,
        val info: DeviceInfo?,
        val transport: TransportKind,
        val lanSeen: Boolean,
        val busy: Boolean,
        val syncRunning: Boolean,
    )

    private companion object {
        const val LEND_ATTEMPTS = 8
        const val LEND_RETRY_MS = 125L
        const val REBOOT_MIN_MS = 100L
        const val REBOOT_MAX_MS = 5_000L

        /** The same wait Android takes before reconnecting after a restart. */
        const val REBOOT_RECONNECT_DELAY_MS = 6_000L

        /** Swift gives up on the token after 15 s. A little more covers its notice. */
        const val TOKEN_TIMEOUT_MS = 20_000L
    }
}

/**
 * Whether the restart's reconnect may open [address] again. Only when the link still points at it,
 * or nothing holds the one Bluetooth link and Collect all is not running. An Auto-sync step holds a
 * selected link, so it counts as held. Another sensor the user opened meanwhile keeps its link.
 */
internal fun shouldReopenAfterRestart(address: String, selectedId: String?, collectAllRunning: Boolean): Boolean =
    selectedId.equals(address, ignoreCase = true) || (selectedId == null && !collectAllRunning)

private val LOCATION_UNSUPPORTED = earth.walkthru.aqsync.data.protocol.LocationConfig()
