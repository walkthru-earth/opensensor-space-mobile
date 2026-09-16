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
import javax.inject.Inject
import javax.inject.Singleton

/** Creates [AqTransport]s. The fake is only reachable in debug builds. */
@Singleton
class TransportFactory @Inject constructor(@ApplicationContext private val context: Context) {

    @SuppressLint("MissingPermission") // gated by the permission screen before any connect
    fun ble(address: String): AqTransport {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter ?: throw TransportException("this phone has no Bluetooth adapter")
        val device = adapter.getRemoteDevice(address)
        return BleTransport(context, device)
    }

    fun fake(address: String): AqTransport {
        check(BuildConfig.DEBUG) { "fake device is debug-only" }
        return FakeTransport(address = address)
    }

    fun isFake(transport: AqTransport): Boolean = transport is FakeTransport
}
