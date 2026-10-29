package space.opensensor.data.lan

import space.opensensor.data.protocol.DeviceConfig

/**
 * Wi-Fi sync is on for everyone. A sensor that reports `lan.on=0` is switched back on, quietly and
 * once per connection, unless the phone is at the Developer level, the only level that shows the
 * switch. Android and iOS both call [space.opensensor.data.ble.DeviceSession.enableLanOnce].
 */
object LanAutoEnable {
    /** True only for an explicit `lan.on=0`. A config without the field (old firmware) is left alone. */
    fun shouldEnable(config: DeviceConfig?, developer: Boolean): Boolean = !developer && config?.lan?.on == 0
}
