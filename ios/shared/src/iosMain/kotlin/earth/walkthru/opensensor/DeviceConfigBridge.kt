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
    val connect: (String) -> Unit,
    val disconnect: () -> Unit,
    /** Browses for sensors on Wi-Fi, which shows the local network prompt the first time. */
    val scanLan: () -> Unit,
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

    /** This sensor's hardware id while the one Bluetooth link holds it. */
    private fun device(): String? = state.info?.dev?.takeIf { state.connected && state.selectedId.equals(address, ignoreCase = true) }

    private val companion = snapshotFlow {
        val ready = state.connected && state.selectedId.equals(address, ignoreCase = true)
        val info = state.info.takeIf { ready }
        CompanionView(
            ready = ready,
            info = info,
            lanSeen = info != null && info.station in state.lanStations.values,
            busy = state.wifiAccessBusy || state.today.busy,
            syncRunning = state.syncBusy,
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
            transport = TransportKind.BLE,
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
                withContext(Dispatchers.Main) { links.connect(address) }
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
        command(DeviceConfigCommand.ROTATE_TOKEN) { session ->
            config.value = session.setConfig(ConfigPatch.build { rotateToken() })
            // The old token is dead on the sensor. Drop it, then read the new one over Bluetooth.
            device()?.let { id -> withContext(Dispatchers.Main) { links.forgetWifiAccess(id) } }
            scope.launch { _notices.tryEmit(DeviceConfigNotice.TokenRotated(stored = readToken() == true)) }
        }
    }

    override suspend fun applySensorConfig(patch: ConfigPatch): ConfigSnapshot {
        if (state.syncBusy) throw IllegalStateException("a sync holds the link")
        return borrow { it.setConfig(patch) }?.also { config.value = it } ?: throw IllegalStateException("link busy")
    }

    /** iOS keeps no sensor history of its own yet. The board's echoed identity is the record. */
    override suspend fun recordSensor(serial: String, vendor: String, model: String, batch: String, source: String) = Unit

    override fun reconnect() = links.connect(address)

    override fun reconnectOverBluetooth() = links.connect(address)

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
            block(DeviceSession(link.transport, debugFrames.recorderFor(link.transport.address)))
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
