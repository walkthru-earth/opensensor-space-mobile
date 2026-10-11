package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.airquality.HealthScale
import earth.walkthru.aqsync.airquality.WhoGuidelines
import earth.walkthru.aqsync.airquality.WhoStep
import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.data.protocol.PmsStatus

/**
 * The friendly layer, as pure data: one mood per step of the WHO 2021 PM2.5 scale
 * ([WhoGuidelines.PM25], edges 15, 25, 37.5, 50 and 75 µg/m³), each with a
 * one-line "what to do" and a familiar comparison. The edges live only in `:core:airquality` and are
 * inclusive from below, as WHO states them ("at or below 15 µg/m³ meets the guideline").
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

    /** A valid PM2.5 reading and its mood. */
    data class Reading(val pm25: Double, val mood: AirMood) : NowReading

    companion object {
        fun of(live: LiveSample?): NowReading {
            if (live == null) return Waiting
            val status = live.pmsStatus ?: return Waiting
            if (status != PmsStatus.VALID) return SensorNotReady(status, hint(status))
            val pm = live.pm25 ?: return Waiting
            val mood = AirMood.ofOrNull(pm) ?: return Waiting
            return Reading(pm, mood)
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

/** "Today so far" from the UTC-day tier: a real average with its coverage, or nothing. */
data class TodaySoFar(val pm25Mean: Float, val validRows: Int, val coverage: Float) {
    /** Read against the WHO 24-hour scale. A part-day mean is not yet a 24-hour mean. */
    val mood: AirMood get() = AirMood.of(pm25Mean.toDouble())
}
