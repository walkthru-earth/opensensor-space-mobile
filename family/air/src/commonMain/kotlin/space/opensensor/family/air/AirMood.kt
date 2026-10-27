package space.opensensor.family.air

import space.opensensor.data.protocol.LiveReading
import space.opensensor.model.HealthScale

/**
 * The friendly layer, as pure data: one mood per step of the WHO 2021 PM2.5 scale
 * ([WhoGuidelines.PM25], edges 15, 25, 37.5, 50 and 75 µg/m³), each with a one-line "what to do"
 * and a familiar comparison. The edges live only in `:family:air` and are inclusive from
 * below, as WHO states them ("at or below 15 µg/m³ meets the guideline").
 *
 * The WHO values are 24-hour means. A mood is **not** an AQI. The Now card reads the EPA NowCast of
 * the last 12 hours against the scale when the phone archive has it ([PmNowCast], #93), else the
 * live 10-second snapshot, and says which one in a short line.
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

/** What the Now card's number averages, stated on the card in one short line (#93). */
enum class Averaging {
    /** The live 10-second sample. */
    LIVE,

    /** The EPA NowCast of the last 12 hourly means ([PmNowCast]). */
    NOWCAST,
}

/** A PM2.5 NowCast of one thing, from the hourly tier of [basis]. */
data class Pm25NowCast(val value: Double, val basis: Pm25Basis)

/** What the Now card should say, the sensor's own state comes first. */
sealed interface NowReading {
    /** No sample yet, or the link is quiet. */
    data object Waiting : NowReading

    /** The sensor is present but its reading is not usable yet (warming up, stale, error). */
    data class SensorNotReady(val status: PmsStatus, val hint: String) : NowReading

    /**
     * A valid PM2.5 reading and its mood. [primary] is the value shown and judged, the NowCast when
     * [averaging] is [Averaging.NOWCAST], else the live value. [basis] says which PM2.5 [primary] is.
     * [live] is the live sample's value, the EPA corrected PM2.5 when the sample carries a valid RH,
     * else the raw one, as [liveBasis] says. [raw] is the sensor's own atmospheric PM2.5 now. Both
     * show second in the Detailed level. [flags] are the [QcFlags] of the live sample ([LiveQc]),
     * and never change a value.
     */
    data class Reading(
        val primary: Double,
        val mood: AirMood,
        val basis: Pm25Basis = Pm25Basis.RAW,
        val raw: Double? = primary,
        val averaging: Averaging = Averaging.LIVE,
        val live: Double = primary,
        val liveBasis: Pm25Basis = basis,
        val flags: Int = QcFlags.NONE,
    ) : NowReading {
        /**
         * Whether the mood rates [primary]. A NowCast always is. A live value is not when a quality
         * check flagged it, so Simple leaves its WHO step out (#91).
         */
        val rated: Boolean get() = averaging == Averaging.NOWCAST || flags == QcFlags.NONE
    }

    companion object {
        /**
         * The Now card for [live], read by column through the thing template. No reading, an
         * overflow sample (every value null) or an unknown dictionary is [Waiting]. A [nowCast]
         * becomes the number and the mood once the sensor reports valid, the live value then shows
         * second. [flags] are the quality flags of the live sample.
         */
        fun of(live: LiveReading?, nowCast: Pm25NowCast? = null, flags: Int = QcFlags.NONE): NowReading {
            if (live == null) return Waiting
            val status = live.pmsStatus ?: return Waiting
            if (status != PmsStatus.VALID) return SensorNotReady(status, hint(status))
            val raw = live.value(AirFamily.PM25_ATMOSPHERIC)
            // The correction reads the CF=1 PM2.5 and the sensor's RH, the inputs it was fitted on.
            val corrected = EpaPm25Correction.correct(live.value(AirFamily.PM25_CF1), live.value(AirFamily.RELATIVE_HUMIDITY))
            val pm = corrected ?: raw ?: return Waiting
            val liveBasis = if (corrected != null) Pm25Basis.CORRECTED else Pm25Basis.RAW
            nowCast?.let { cast ->
                val mood = AirMood.ofOrNull(cast.value) ?: return@let
                return Reading(cast.value, mood, cast.basis, raw, Averaging.NOWCAST, pm, liveBasis, flags)
            }
            val mood = AirMood.ofOrNull(pm) ?: return Waiting
            return Reading(pm, mood, liveBasis, raw, Averaging.LIVE, pm, liveBasis, flags)
        }

        private fun hint(status: PmsStatus): String = when (status) {
            PmsStatus.ABSENT -> "No particle sensor is answering. Check its cable."
            PmsStatus.WARMING -> "The sensor is warming up. It takes about a minute after power on."
            PmsStatus.STALE -> "The last sensor reading is old, so it is not trusted."
            PmsStatus.ERROR -> "The sensor reports an error, so its numbers are not shown."
            PmsStatus.MODEL_MISMATCH -> "The fitted sensor is not the model this board expects, so its numbers are not shown."
            PmsStatus.SLEEPING -> "The sensor is resting between readings."
            PmsStatus.VALID -> ""
        }
    }
}
