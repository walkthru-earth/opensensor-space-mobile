package space.opensensor.today

import space.opensensor.data.protocol.LiveReading
import space.opensensor.model.Thing
import space.opensensor.model.ThingDatastream

/**
 * The power columns of a thing's template, found by observed property, never by column name. Each
 * is null when the template has no such column or the profile hides it. [state] is a classified
 * code column, the CoreS3 `charging_status` or the Waveshare `battery_health_state` (LIVE `bstate`).
 */
data class BoardPower(
    val battery: ThingDatastream? = null,
    val millivolts: ThingDatastream? = null,
    val usbMillivolts: ThingDatastream? = null,
    val state: ThingDatastream? = null,
) {
    /** The battery state LIVE shows now, null without a state column, a reading or a known class. */
    fun stateOf(reading: LiveReading?): BatteryState? {
        val column = state ?: return null
        val code = reading?.code(column.name) ?: return null
        val cls = column.datastream.classOf(code.toLong())?.takeUnless { it.nodata } ?: return null
        return BatteryState(cls.name)
    }

    companion object {
        const val STATE_OF_CHARGE = "urn:walkthru-earth:property:battery_state_of_charge"
        const val VOLTAGE = "urn:walkthru-earth:property:battery_voltage"
        const val USB_VOLTAGE = "urn:walkthru-earth:property:usb_bus_voltage"
        const val CHARGING_STATUS = "urn:walkthru-earth:property:battery_charging_status"
        const val HEALTH_STATE = "urn:walkthru-earth:property:battery_health_state"

        fun of(thing: Thing?): BoardPower {
            if (thing == null) return BoardPower()
            fun byProperty(id: String): ThingDatastream? = thing.datastreams.firstOrNull {
                it.datastream.property?.id == id && it.datastream.liveKey in thing.live.keys
            }
            return BoardPower(
                battery = byProperty(STATE_OF_CHARGE),
                millivolts = byProperty(VOLTAGE),
                usbMillivolts = byProperty(USB_VOLTAGE),
                state = (byProperty(CHARGING_STATUS) ?: byProperty(HEALTH_STATE))?.takeIf { it.datastream.classes.isNotEmpty() },
            )
        }
    }
}

/**
 * A battery state class from the template, by its class name. A name the app does not know keeps
 * the template's own word in [name].
 */
data class BatteryState(val name: String) {
    val kind: Kind = Kind.entries.firstOrNull { it.className == name } ?: Kind.OTHER

    /** True while power comes from outside, so the charging icon shows. */
    val charging: Boolean get() = kind == Kind.CHARGING || kind == Kind.EXTERNAL_POWER

    enum class Kind(val className: String?) {
        CHARGING("charging"),
        DISCHARGING("discharging"),
        EXTERNAL_POWER("external-power-likely"),
        OK("ok"),
        LOW("low"),
        CRITICAL("critical"),
        SETTLING("settling"),
        OTHER(null),
    }
}
