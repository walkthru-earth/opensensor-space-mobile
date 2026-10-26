package earth.walkthru.aqsync.data.protocol

import space.opensensor.model.Station

/**
 * One [LiveSample] read through its [station]. A column's value comes from the LIVE key the template's
 * `live_keys` give it, and only for a column LIVE shows ([Station.live]). A key the template does not
 * declare is ignored, so a newer board adding a key breaks nothing. The unit comes from the column and
 * the label from the app profile, never from a key name.
 */
class LiveReading(val sample: LiveSample, val station: Station) {
    private fun keyOf(column: String): String? =
        station.datastream(column)?.datastream?.liveKey?.takeIf { it in station.live.keys }

    /** True when LIVE shows [column] for this station, whatever this sample carries. */
    fun declares(column: String): Boolean = keyOf(column) != null

    /** True when this sample carries [column]'s key, even as `null`. An absent key means the board has no such field. */
    fun carries(column: String): Boolean = keyOf(column)?.let(sample::has) == true

    /** The value of [column] now, null when LIVE does not show it or the board has no reading now. */
    fun value(column: String): Double? = keyOf(column)?.let(sample::get)?.takeIf { it.isFinite() }

    /** [value] as an integer code, for a code column such as `pms_status`. */
    fun code(column: String): Int? = value(column)?.takeIf { it == kotlin.math.floor(it) }?.toInt()

    /** The PM sensor state, null when the sample has no reading of [PMS_STATUS]. */
    val pmsStatus: PmsStatus? get() = PmsStatus.fromCode(code(PMS_STATUS))

    val pmValid: Boolean get() = pmsStatus == PmsStatus.VALID

    companion object {
        /** The PM sensor state column, `0` absent to `4` valid. */
        const val PMS_STATUS = "pms_status"

        /** [sample] read through [station], or null when either is missing, as for an unknown dictionary. */
        fun of(sample: LiveSample?, station: Station?): LiveReading? =
            if (sample == null || station == null) null else LiveReading(sample, station)
    }
}
