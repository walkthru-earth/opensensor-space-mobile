package space.opensensor.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import space.opensensor.data.ble.BleLinkDriver
import space.opensensor.data.ble.DeviceTransport
import space.opensensor.data.ble.TransportUnavailableException
import space.opensensor.data.coroutines.IoDispatcher
import space.opensensor.data.demo.DemoDevices
import space.opensensor.data.lan.TcpLinkDriver
import space.opensensor.link.UnavailableKind
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the repository needs to build links; [TransportFactory] in the app, a fake in tests. Every
 * call returns a new, unopened [AndroidLink] for one connection.
 */
interface TransportSource {
    fun ble(address: String): AndroidLink
    fun bleUnattended(address: String, connectTimeoutMs: Long): AndroidLink
    fun lan(address: String, name: String?, host: InetAddress, port: Int, token: ByteArray): AndroidLink

    /** A new link to the demo sensor at [address]. Only a debug build has one. */
    fun demo(address: String): AndroidLink

    /** True when [address] belongs to a demo sensor, always false in a release build. */
    fun isDemoAddress(address: String): Boolean

    /** True when [transport] is a demo sensor link. */
    fun isDemo(transport: DeviceTransport): Boolean
}

/**
 * Creates [AndroidLink]s. The repository decides *which* one (LAN when the device is discovered
 * on the network and a token is held, else BLE); this class only builds them. Demo sensors come from
 * [DemoDevices], which a release build binds empty.
 */
@Singleton
class TransportFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val demoDevices: DemoDevices,
) : TransportSource {

    /**
     * Interactive BLE connect. A missing permission, adapter or radio throws a typed
     * [TransportUnavailableException] before any GATT work, so the sync ends with a reason the
     * user can act on and the connect retry loop does not spin on a radio that is off.
     */
    @SuppressLint("MissingPermission") // checked by requireBluetooth() first
    override fun ble(address: String): AndroidLink = BleLinkDriver(context, requireBluetooth().getRemoteDevice(address))

    /**
     * Unattended BLE connect for the auto-sync worker: straight `connectGatt` to a **bonded** address
     * (no scan, no pairing dialog — [isBonded] is checked first), with a short timeout so an absent
     * logger costs seconds, not the 30 s the interactive path allows.
     */
    @SuppressLint("MissingPermission") // checked by requireBluetooth() first
    override fun bleUnattended(address: String, connectTimeoutMs: Long): AndroidLink {
        val device = requireBluetooth().getRemoteDevice(address)
        return BleLinkDriver(context, device, connectTimeoutMs = connectTimeoutMs, allowBonding = false)
    }

    private fun requireBluetooth(): BluetoothAdapter {
        if (!hasConnectPermission()) throw TransportUnavailableException(UnavailableKind.PERMISSION_MISSING, "BLUETOOTH_CONNECT is not granted")
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw TransportUnavailableException(UnavailableKind.NO_ADAPTER, "this phone has no Bluetooth adapter")
        if (!adapter.isEnabled) throw TransportUnavailableException(UnavailableKind.RADIO_OFF, "Bluetooth is off")
        return adapter
    }

    fun hasConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** True when the phone holds a bond for [address] (false without BLUETOOTH_CONNECT or an adapter). */
    @SuppressLint("MissingPermission") // checked one line above
    fun isBonded(address: String): Boolean {
        if (!hasConnectPermission()) return false
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        return runCatching { adapter.getRemoteDevice(address).bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
    }

    /**
     * LAN socket to a device we already know over BLE: [address] stays the Bluetooth address so the
     * session, the archive and the UI treat it as the same device. [host]/[port] come from mDNS
     * discovery of `_ossync._tcp` — the only place the app ever gets an IP address from.
     */
    override fun lan(address: String, name: String?, host: InetAddress, port: Int, token: ByteArray): AndroidLink =
        TcpLinkDriver(address = address, displayName = name, host = host, port = port, token = token, ioDispatcher = ioDispatcher)

    override fun demo(address: String): AndroidLink = checkNotNull(demoDevices.link(address)) { "no demo sensor in this build" }

    override fun isDemoAddress(address: String): Boolean = demoDevices.isDemo(address)

    override fun isDemo(transport: DeviceTransport): Boolean = demoDevices.owns(transport)
}
