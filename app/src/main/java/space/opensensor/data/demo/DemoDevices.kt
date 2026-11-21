package space.opensensor.data.demo

import androidx.annotation.StringRes
import space.opensensor.data.AndroidLink
import space.opensensor.data.ble.DeviceTransport

/**
 * The demo sensors this build can open (#310). The debug source set binds the shared `:core:demo`
 * sensor, the release source set binds [None], so a release build has no demo address, link or
 * scan entry.
 */
interface DemoDevices {
    /** One My devices menu entry per demo sensor, empty in a release build. */
    val entries: List<DemoEntry>

    /** True when [address] belongs to a demo sensor. */
    fun isDemo(address: String): Boolean

    /** A new, unopened link to the demo sensor at [address], or null for any other address. */
    fun link(address: String): AndroidLink?

    /** True when [transport] is a link this class built. */
    fun owns(transport: DeviceTransport): Boolean

    /** No demo sensors, the release binding. */
    object None : DemoDevices {
        override val entries: List<DemoEntry> = emptyList()
        override fun isDemo(address: String): Boolean = false
        override fun link(address: String): AndroidLink? = null
        override fun owns(transport: DeviceTransport): Boolean = false
    }
}

/** A demo sensor in the My devices menu. [name] is its display name, [label] the menu text. */
data class DemoEntry(val address: String, val name: String, @param:StringRes val label: Int)
