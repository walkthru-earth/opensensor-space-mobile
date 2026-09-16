package earth.walkthru.aqsync.data.background

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import earth.walkthru.aqsync.data.BackgroundSyncResult
import earth.walkthru.aqsync.data.DeviceConnectionRepository
import earth.walkthru.aqsync.data.TransportFactory
import earth.walkthru.aqsync.data.lan.LanDevice
import earth.walkthru.aqsync.data.lan.LanDiscovery
import earth.walkthru.aqsync.data.local.database.KnownDevice
import earth.walkthru.aqsync.data.local.database.KnownDeviceDao
import earth.walkthru.aqsync.ui.permissions.LanPermission
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AutoSyncGateway] backed by the real app: Room for the device list, [LanDiscovery] for the mDNS
 * browse, the Bluetooth adapter for the bond check and [DeviceConnectionRepository.syncInBackground]
 * for the transfer itself — so the worker shares every rule of an interactive sync (LAN-then-BLE,
 * verification, never delete, token never logged).
 */
@Singleton
class AppAutoSyncGateway @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DeviceConnectionRepository,
    private val knownDeviceDao: KnownDeviceDao,
    private val lanDiscovery: LanDiscovery,
    private val transportFactory: TransportFactory,
) : AutoSyncGateway {

    override suspend fun autoSyncDevices(): List<KnownDevice> = knownDeviceDao.autoSyncDevices()

    override suspend fun knownDevice(address: String): KnownDevice? = knownDeviceDao.get(address)

    override fun interactiveSessionAddress(): String? = repository.interactiveSessionAddress

    override suspend fun browseLan(stations: Set<String>, timeoutMs: Long): Map<String, LanDevice> {
        if (!LanPermission.isGranted(context)) {
            Log.i(TAG, "LAN permission not granted; Bluetooth only")
            return emptyMap()
        }
        var latest: Map<String, LanDevice> = emptyMap()
        try {
            withTimeoutOrNull(timeoutMs) {
                lanDiscovery.discover().collect { ev ->
                    when (ev) {
                        is LanDiscovery.Event.Devices -> {
                            latest = ev.devices.values.filter { it.isConnectable }.associateBy { it.station!! }
                            // Everything we came for is resolved: stop browsing early.
                            if (stations.all { it in latest }) throw BrowseDone()
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

    override fun isBonded(address: String): Boolean = transportFactory.isBonded(address)

    override suspend fun sync(device: KnownDevice, link: AutoSyncLink): BackgroundSyncResult = when (link) {
        is AutoSyncLink.Lan -> repository.syncInBackground(device.address, link.target)
        AutoSyncLink.Ble -> repository.syncInBackground(device.address, null)
    }

    private companion object {
        const val TAG = "AutoSync"
    }
}
