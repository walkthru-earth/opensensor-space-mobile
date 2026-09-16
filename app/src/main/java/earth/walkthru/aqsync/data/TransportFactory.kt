package earth.walkthru.aqsync.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
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

/**
 * Creates [AqTransport]s. The repository decides *which* one (LAN when the device is discovered
 * on the network and a token is held, else BLE); this class only builds them. The fake is
 * debug-only.
 */
@Singleton
class TransportFactory @Inject constructor(@ApplicationContext private val context: Context) {

    @SuppressLint("MissingPermission") // gated by the permission screen before any connect
    fun ble(address: String): AqTransport {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter ?: throw TransportException("this phone has no Bluetooth adapter")
        val device = adapter.getRemoteDevice(address)
        return BleTransport(context, device)
    }

    /**
     * LAN socket to a device we already know over BLE: [address] stays the Bluetooth address so the
     * session, the archive and the UI treat it as the same device. [host]/[port] come from mDNS
     * discovery of `_aqsync._tcp` — the only place the app ever gets an IP address from.
     */
    fun lan(address: String, name: String?, host: InetAddress, port: Int, token: ByteArray): AqTransport =
        TcpTransport(address = address, displayName = name, host = host, port = port, token = token)

    fun fake(address: String): AqTransport {
        check(BuildConfig.DEBUG) { "fake device is debug-only" }
        return FakeTransport(address = address, simulateLan = address == FakeTransport.FAKE_LAN_ADDRESS)
    }

    fun isFake(transport: AqTransport): Boolean = transport is FakeTransport
}
