package earth.walkthru.aqsync.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import earth.walkthru.aqsync.BuildConfig
import earth.walkthru.aqsync.data.ble.AqTransport
import earth.walkthru.aqsync.data.ble.BleTransport
import earth.walkthru.aqsync.data.ble.TransportException
import earth.walkthru.aqsync.data.fake.FakeTransport
import earth.walkthru.aqsync.data.lan.TcpTransport
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/** What the repository needs to build transports; [TransportFactory] in the app, a fake in tests. */
interface TransportSource {
    fun ble(address: String): AqTransport
    fun bleUnattended(address: String, connectTimeoutMs: Long): AqTransport
    fun lan(address: String, name: String?, host: InetAddress, port: Int, token: ByteArray): AqTransport
    fun fake(address: String): AqTransport
    fun isFake(transport: AqTransport): Boolean
}

/**
 * Creates [AqTransport]s. The repository decides *which* one (LAN when the device is discovered
 * on the network and a token is held, else BLE); this class only builds them. The fake is
 * debug-only.
 */
@Singleton
class TransportFactory @Inject constructor(@ApplicationContext private val context: Context) : TransportSource {

    @SuppressLint("MissingPermission") // gated by the permission screen before any connect
    override fun ble(address: String): AqTransport {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter ?: throw TransportException("this phone has no Bluetooth adapter")
        val device = adapter.getRemoteDevice(address)
        return BleTransport(context, device)
    }

    /**
     * Unattended BLE connect for the auto-sync worker: straight `connectGatt` to a **bonded** address
     * (no scan, no pairing dialog — [isBonded] is checked first), with a short timeout so an absent
     * logger costs seconds, not the 30 s the interactive path allows.
     */
    @SuppressLint("MissingPermission") // the worker checks hasConnectPermission() first
    override fun bleUnattended(address: String, connectTimeoutMs: Long): AqTransport {
        if (!hasConnectPermission()) throw TransportException("BLUETOOTH_CONNECT is not granted")
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter ?: throw TransportException("this phone has no Bluetooth adapter")
        if (!adapter.isEnabled) throw TransportException("Bluetooth is off")
        val device = adapter.getRemoteDevice(address)
        return BleTransport(context, device, connectTimeoutMs = connectTimeoutMs, allowBonding = false)
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
     * discovery of `_aqsync._tcp` — the only place the app ever gets an IP address from.
     */
    override fun lan(address: String, name: String?, host: InetAddress, port: Int, token: ByteArray): AqTransport =
        TcpTransport(address = address, displayName = name, host = host, port = port, token = token)

    private val fakes = HashMap<String, FakeTransport>()

    /** One fake per address, kept for the process lifetime so its "NVS" survives disconnects and fake reboots. */
    override fun fake(address: String): AqTransport {
        check(BuildConfig.DEBUG) { "fake device is debug-only" }
        return synchronized(fakes) {
            fakes.getOrPut(address) { FakeTransport(address = address, simulateLan = address == FakeTransport.FAKE_LAN_ADDRESS) }
        }
    }

    override fun isFake(transport: AqTransport): Boolean = transport is FakeTransport
}
