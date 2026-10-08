package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.data.protocol.PmsStatus

/**
 * The friendly layer, as pure data: one mood per PM2.5 display band, each with a plain-language
 * label, a one-line "what to do" and a familiar comparison. The bands are the ones the chart shades
 * (`PM25_BANDS`) — the WHO 2021 24-hour guideline is 15 µg/m³ — and they are **not** an AQI: a named
 * index needs validated averaging rules, and the live number is a 10-second snapshot.
 *
 * Kept free of Android so the same table drives the iOS app and the unit tests.
 */
enum class AirMood(
    /** Exclusive upper bound of the band, µg/m³ PM2.5. */
    val upTo: Float,
    val title: String,
    val advice: String,
    val comparison: String,
) {
    FRESH(15f, "Fresh air", "Nothing to do. Enjoy it.", "like a clear day in the countryside"),
    FINE(35f, "Fine", "Fine for everyone. Sensitive people may notice on a long day.", "like a quiet town street"),
    HAZY(55f, "Getting hazy", "Long outdoor effort is less pleasant. Open windows only when it drops.", "like a busy road at rush hour"),
    SMOKY(150f, "Smoky", "Keep windows shut and take it easy outdoors.", "like standing downwind of a barbecue"),
    THICK(Float.POSITIVE_INFINITY, "Thick smoke", "Stay inside if you can. A filter helps.", "like a room where someone is smoking"),
    ;

    companion object {
        fun of(pm25: Double): AirMood = entries.first { pm25 < it.upTo }
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
            return Reading(pm, AirMood.of(pm))
        }

        private fun hint(status: PmsStatus): String = when (status) {
            PmsStatus.ABSENT -> "No particle sensor is answering. Check its cable."
            PmsStatus.WARMING -> "The sensor is warming up. It takes about a minute after power-on."
            PmsStatus.STALE -> "The last sensor reading is old, so it is not trusted."
            PmsStatus.ERROR -> "The sensor reports an error, so its numbers are not shown."
            PmsStatus.VALID -> ""
        }
    }
}

/** "Today so far" from the UTC-day tier: a real average with its coverage, or nothing. */
data class TodaySoFar(val pm25Mean: Float, val validRows: Int, val coverage: Float) {
    val mood: AirMood get() = AirMood.of(pm25Mean.toDouble())
}
