package space.opensensor.data.background

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import space.opensensor.collection.KeepAlive
import space.opensensor.collection.StopSource
import space.opensensor.data.BackgroundSyncResult
import space.opensensor.data.DeviceConnectionManager
import space.opensensor.data.TransportFactory
import space.opensensor.data.lan.LanDevice
import space.opensensor.data.lan.LanDiscovery
import space.opensensor.data.local.database.KnownDevice
import space.opensensor.data.local.database.KnownDeviceDao
import space.opensensor.ui.permissions.LanPermission
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AutoSyncGateway] backed by the real app: Room for the device list, [LanDiscovery] for the mDNS
 * browse, the Bluetooth adapter for the bond check and the per-device collection facade for the
 * transfer itself. A run must supply its WorkManager PROCESS protection; this adapter never starts
 * an unmanaged foreground service for background work.
 */
@Singleton
class AppAutoSyncGateway @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: DeviceConnectionManager,
    private val knownDeviceDao: KnownDeviceDao,
    private val lanDiscovery: LanDiscovery,
    private val transportFactory: TransportFactory,
) : AutoSyncGateway {

    override suspend fun autoSyncDevices(): List<KnownDevice> = knownDeviceDao.autoSyncDevices()

    override suspend fun knownDevice(address: String): KnownDevice? = knownDeviceDao.get(address)

    override suspend fun allKnownDevices(): List<KnownDevice> = knownDeviceDao.allByLastConnected()

    override fun interactiveSessionAddress(): String? = manager.snapshots.value.firstOrNull { manager.isOwned(it.address) }?.address

    override fun isDeviceOwned(address: String): Boolean = manager.hasInteractiveCollection() || manager.isOwned(address)

    fun forRun(owner: String, protection: KeepAlive, stopSource: () -> StopSource = { StopSource.SYSTEM }): AutoSyncGateway = object : AutoSyncGateway by this {
        override suspend fun sync(device: KnownDevice, link: AutoSyncLink): BackgroundSyncResult = syncOwned(device, link, owner, protection, stopSource)
    }

    override suspend fun browseLan(things: Set<String>, timeoutMs: Long): Map<String, LanDevice> {
        if (!LanPermission.isGranted(context)) {
            Log.i(TAG, "LAN permission not granted; Bluetooth only")
            return emptyMap()
        }
        if (!hasWifiNetwork()) {
            // The periodic job carries no network constraint (the Bluetooth path needs none), so this
            // is where the LAN attempt is skipped quickly: mDNS over anything but Wi-Fi cannot find
            // the logger, and a 10 s browse for nothing would only delay the Bluetooth fallback.
            Log.i(TAG, "no Wi-Fi network; skipping the LAN browse, Bluetooth only")
            return emptyMap()
        }
        var latest: Map<String, LanDevice> = emptyMap()
        try {
            withTimeoutOrNull(timeoutMs) {
                lanDiscovery.discover().collect { ev ->
                    when (ev) {
                        is LanDiscovery.Event.Devices -> {
                            latest = ev.devices.values.filter { it.isConnectable }.associateBy { it.thing!! }
                            // Everything we came for is resolved: stop browsing early.
                            if (things.all { it in latest }) throw BrowseDone()
                        }
                        is LanDiscovery.Event.Failed -> {
                            Log.i(TAG, "mDNS browse failed: ${ev.message}")
                            throw BrowseDone()
                        }
                    }
                }
            }
        } catch (e: BrowseDone) {
            // Early exit; `latest` holds what was resolved.
        }
        return latest
    }

    private class BrowseDone : RuntimeException()

    /**
     * True when a network with the Wi-Fi transport is up — internet not required, an offline LAN
     * counts. The active network is checked first; a Wi-Fi network that the system does not rank as
     * active (no internet, mobile data preferred) is found through a short `NetworkRequest` probe
     * (`allNetworks` is deprecated since API 31). Bounded to [WIFI_PROBE_MS]; absent → false.
     */
    private suspend fun hasWifiNetwork(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        if (cm.activeNetwork?.let { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } == true) return true
        val found = CompletableDeferred<Boolean>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                found.complete(true)
            }
        }
        return try {
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback)
            withTimeoutOrNull(WIFI_PROBE_MS) { found.await() } ?: false
        } catch (e: RuntimeException) {
            // Too many callbacks registered, or an OEM quirk: do not let the check block the worker;
            // the browse itself fails harmlessly when there is no Wi-Fi.
            Log.w(TAG, "Wi-Fi probe failed (${e::class.simpleName}); trying the LAN browse anyway")
            true
        } finally {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }

    override fun isBonded(address: String): Boolean = transportFactory.isBonded(address)

    override suspend fun sync(device: KnownDevice, link: AutoSyncLink): BackgroundSyncResult =
        BackgroundSyncResult(device.address, device.name, null, 0, 0, 0, skipped = "worker protection unavailable")

    private suspend fun syncOwned(device: KnownDevice, link: AutoSyncLink, owner: String, protection: KeepAlive, stopSource: () -> StopSource): BackgroundSyncResult {
        if (isDeviceOwned(device.address)) {
            return BackgroundSyncResult(device.address, device.name, null, 0, 0, 0, skipped = "interactive collection active")
        }
        // The facade repeats admission atomically. The chosen LAN target or bonded BLE policy is
        // attached to this request, so neither its first attempt nor a retry can scan or pair.
        return when (link) {
            is BackgroundSyncLink.Lan -> manager.controller(device.address).syncInBackground(device.address, link.target, owner = owner, protection = protection, stopSource = stopSource)
            BackgroundSyncLink.Ble -> manager.controller(device.address).syncInBackground(device.address, null, owner = owner, protection = protection, stopSource = stopSource)
        }
    }

    private companion object {
        const val TAG = "AutoSync"

        /** A Wi-Fi network that exists answers a NetworkRequest at once; this only bounds the "none" case. */
        const val WIFI_PROBE_MS = 1_000L
    }
}
