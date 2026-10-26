package space.opensensor.data.history

import space.opensensor.airquality.AirDerivedMetrics
import space.opensensor.airquality.EpaPm25Correction
import space.opensensor.airquality.HealthScale
import space.opensensor.airquality.Pm25Basis
import space.opensensor.airquality.WhoGuidelines
import space.opensensor.airquality.WhoStep
import space.opensensor.data.protocol.LiveReading
import space.opensensor.data.protocol.PmsStatus

/**
 * The friendly layer, as pure data: one mood per step of the WHO 2021 PM2.5 scale
 * ([WhoGuidelines.PM25], edges 15, 25, 37.5, 50 and 75 µg/m³), each with a one-line "what to do"
 * and a familiar comparison. The edges live only in `:core:airquality` and are inclusive from
 * below, as WHO states them ("at or below 15 µg/m³ meets the guideline").
 *
 * The WHO values are 24-hour means. A mood is **not** an AQI, and the live number is a 10-second
 * snapshot, not a 24-hour mean, so the Now card reads it against the scale and says so.
 *
 * Kept free of Android so the same table drives the iOS app and the unit tests.
 */
enum class AirMood(
    /** The WHO step this mood speaks for. Its colour is [WhoStep.color]. */
    val step: WhoStep,
    val advice: String,
    val comparison: String,
) {
    FRESH(WhoStep.GUIDELINE, "Within the WHO guideline. Enjoy it.", "like a clear day in the countryside"),
    FAIR(WhoStep.IT4, "A little above the WHO guideline. Fine for most people.", "like a quiet town street"),
    HAZY(WhoStep.IT3, "Sensitive people may notice it. Open windows when it drops.", "like a busy road at rush hour"),
    POOR(WhoStep.IT2, "Keep windows shut and take long outdoor effort easy.", "like a city on a smoggy day"),
    SMOKY(WhoStep.IT1, "Stay inside if you can. A filter helps.", "like standing downwind of a barbecue"),
    THICK(WhoStep.ABOVE_IT1, "Stay inside with windows shut and run a filter.", "like a room where someone is smoking"),
    ;

    companion object {
        /** The scale the friendly layer reads. */
        val scale: HealthScale get() = WhoGuidelines.PM25

        /** The mood of a WHO step, on any WHO scale (PM2.5 or PM10). */
        fun of(step: WhoStep): AirMood = entries.first { it.step == step }

        /** The mood of a PM2.5 value in µg/m³, or null for a non-finite value. */
        fun ofOrNull(pm25: Double): AirMood? = scale.stepOf(pm25)?.let(::of)

        /** The mood of a finite PM2.5 value in µg/m³. */
        fun of(pm25: Double): AirMood = requireNotNull(ofOrNull(pm25)) { "PM2.5 must be finite, was $pm25" }
    }
}

/** What the Now card should say about the live sample — the sensor's own state comes first. */
sealed interface NowReading {
    /** No sample yet, or the link is quiet. */
    data object Waiting : NowReading

    /** The sensor is present but its reading is not usable yet (warming up, stale, error). */
    data class SensorNotReady(val status: PmsStatus, val hint: String) : NowReading

    /**
     * A valid PM2.5 reading and its mood. [pm25] is the value shown, the EPA corrected PM2.5 when the
     * sample carries a valid RH, else the raw one, as [basis] says. [raw] is the sensor's own
     * atmospheric PM2.5, shown second in the Detailed level.
     */
    data class Reading(val pm25: Double, val mood: AirMood, val basis: Pm25Basis = Pm25Basis.RAW, val raw: Double? = pm25) : NowReading

    companion object {
        /**
         * The Now card for [live], read by column through the thing template. No reading, an
         * overflow sample (every value null) or an unknown dictionary is [Waiting].
         */
        fun of(live: LiveReading?): NowReading {
            if (live == null) return Waiting
            val status = live.pmsStatus ?: return Waiting
            if (status != PmsStatus.VALID) return SensorNotReady(status, hint(status))
            val raw = live.value(AirDerivedMetrics.PM25_ATMOSPHERIC)
            // The correction reads the CF=1 PM2.5 and the sensor's RH, the inputs it was fitted on.
            val corrected = EpaPm25Correction.correct(live.value(AirDerivedMetrics.PM25_CF1), live.value(AirDerivedMetrics.RELATIVE_HUMIDITY))
            val pm = corrected ?: raw ?: return Waiting
            val mood = AirMood.ofOrNull(pm) ?: return Waiting
            return Reading(pm, mood, if (corrected != null) Pm25Basis.CORRECTED else Pm25Basis.RAW, raw)
        }

        private fun hint(status: PmsStatus): String = when (status) {
            PmsStatus.ABSENT -> "No particle sensor is answering. Check its cable."
            PmsStatus.WARMING -> "The sensor is warming up. It takes about a minute after power on."
            PmsStatus.STALE -> "The last sensor reading is old, so it is not trusted."
            PmsStatus.ERROR -> "The sensor reports an error, so its numbers are not shown."
            PmsStatus.VALID -> ""
        }
    }
}

/**
 * "Today so far" from the hourly tier, a real average with its coverage, or nothing. [basis] says
 * whether the mean is of corrected rows or, when the day has no valid RH, of raw ones.
 */
data class TodaySoFar(val pm25Mean: Float, val validRows: Int, val coverage: Float, val basis: Pm25Basis = Pm25Basis.RAW) {
    /** Read against the WHO 24-hour scale. A part-day mean is not yet a 24-hour mean. */
    val mood: AirMood get() = AirMood.of(pm25Mean.toDouble())
}
