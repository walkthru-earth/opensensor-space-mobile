package space.opensensor.data.protocol

import space.opensensor.model.Thing
import kotlin.time.Instant

/**
 * One [LiveSample] read through its [thing]. A column's value comes from the LIVE key the template's
 * `live_keys` give it, and only for a column LIVE shows ([Thing.live]). A key the template does not
 * declare is ignored, so a newer board adding a key breaks nothing. The unit comes from the column and
 * the label from the app profile, never from a key name.
 */
class LiveReading(val sample: LiveSample, val thing: Thing) {
    private fun keyOf(column: String): String? =
        thing.datastream(column)?.datastream?.liveKey?.takeIf { it in thing.live.keys }

    /** True when LIVE shows [column] for this thing, whatever this sample carries. */
    fun declares(column: String): Boolean = keyOf(column) != null

    /** True when this sample carries [column]'s key, even as `null`. An absent key means the board has no such field. */
    fun carries(column: String): Boolean = keyOf(column)?.let(sample::has) == true

    /** The value of [column] now, null when LIVE does not show it or the board has no reading now. */
    fun value(column: String): Double? = keyOf(column)?.let(sample::get)?.takeIf { it.isFinite() }

    /** [value] as an integer code, for a code column such as a sensor state. */
    fun code(column: String): Int? = value(column)?.takeIf { it == kotlin.math.floor(it) }?.toInt()

    /**
     * The sample's UTC instant, from the key the template gives [EVENT_TIME_UTC_US] (`utc`). LIVE sends
     * the stored microseconds since the epoch, so the value equals the file column. Null when the board
     * has no synchronized clock or the value is not a whole number.
     */
    val utc: Instant? get() = value(EVENT_TIME_UTC_US)?.takeIf { it == kotlin.math.floor(it) }?.toLong()?.let { us ->
        Instant.fromEpochSeconds(us.floorDiv(MICROS_PER_SECOND), us.mod(MICROS_PER_SECOND) * NANOS_PER_MICRO)
    }

    companion object {
        /** The file column of the sample's UTC time, in microseconds since the epoch. */
        const val EVENT_TIME_UTC_US = "event_time_utc_us"
        private const val MICROS_PER_SECOND = 1_000_000L
        private const val NANOS_PER_MICRO = 1_000L

        /** [sample] read through [thing], or null when either is missing, as for an unknown dictionary. */
        fun of(sample: LiveSample?, thing: Thing?): LiveReading? =
            if (sample == null || thing == null) null else LiveReading(sample, thing)
    }
}
