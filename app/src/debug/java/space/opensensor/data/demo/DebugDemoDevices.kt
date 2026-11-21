package space.opensensor.data.demo

import kotlinx.coroutines.CoroutineScope
import space.opensensor.app.R
import space.opensensor.data.AndroidLink
import space.opensensor.data.TransportLink
import space.opensensor.data.ble.DeviceTransport
import space.opensensor.data.coroutines.ApplicationScope
import space.opensensor.demo.DemoSensor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The shared `:core:demo` sensor, debug builds only. One sensor per address lives for the process,
 * so its settings, token, log and files survive a disconnect and a demo reboot, as NVS would.
 */
@Singleton
class DebugDemoDevices @Inject constructor(@ApplicationScope private val appScope: CoroutineScope) : DemoDevices {
    private val sensors = HashMap<String, DemoSensor>()

    override val entries: List<DemoEntry> = listOf(
        DemoEntry(DemoSensor.ADDRESS, "OS-6b40 (demo)", R.string.scan_demo),
        DemoEntry(DemoSensor.LAN_ADDRESS, "OS-6b40 (demo LAN)", R.string.scan_demo_lan),
    )

    override fun isDemo(address: String): Boolean = DemoSensor.isDemo(address)

    override fun link(address: String): AndroidLink? {
        if (!isDemo(address)) return null
        val sensor = synchronized(sensors) { sensors.getOrPut(address) { DemoSensor.forAddress(address) } }
        return TransportLink(sensor, appScope)
    }

    override fun owns(transport: DeviceTransport): Boolean = (transport as? TransportLink)?.wrapped is DemoSensor
}
