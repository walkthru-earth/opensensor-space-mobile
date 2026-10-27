package space.opensensor.family.air

import space.opensensor.model.Basis

/**
 * Which PM2.5 a value is, so every chart and card can say it in a short label. RAW is the sensor's own
 * atmospheric PM2.5 as stored, CORRECTED the US EPA humidity correction of [EpaPm25Correction].
 */
typealias Pm25Basis = Basis

/**
 * The US EPA humidity correction for Plantower PM2.5, the US-wide piecewise equation the EPA applies
 * on the AirNow Fire and Smoke Map.
 *
 * Sources. K. K. Barkjohn, B. Gantt, A. L. Clements, "Development and application of a United
 * States-wide correction for PM2.5 data collected with the PurpleAir sensor", Atmospheric Measurement
 * Techniques 14, 4617 to 4637, 2021, https://doi.org/10.5194/amt-14-4617-2021 (the segment below
 * 30 µg/m³). K. K. Barkjohn et al., "Correction and accuracy of PurpleAir PM2.5 measurements for
 * extreme wildfire smoke", Sensors 22, 9669, 2022, https://doi.org/10.3390/s22249669, and the EPA
 * report "Sensor data cleaning and correction: application on the AirNow Fire and Smoke Map", 2021,
 * extend it to high concentrations with blended transitions.
 *
 * With `x` the CF=1 PM2.5 in µg/m³ and `rh` the relative humidity in percent, the result is
 *
 * - `0.524·x − 0.0862·rh + 5.75` for `x < 30`
 * - `[0.786·(x/20 − 3/2) + 0.524·(1 − (x/20 − 3/2))]·x − 0.0862·rh + 5.75` for `30 ≤ x < 50`
 * - `0.786·x − 0.0862·rh + 5.75` for `50 ≤ x < 210`
 * - `[0.69·t + 0.786·(1 − t)]·x − 0.0862·rh·(1 − t) + 2.966·t + 5.75·(1 − t) + 8.84·10⁻⁴·x²·t`
 *   with `t = x/50 − 21/5`, for `210 ≤ x < 260`
 * - `2.966 + 0.69·x + 8.84·10⁻⁴·x²` for `x ≥ 260`
 *
 * The segments meet at every breakpoint, so the curve has no jumps.
 *
 * The equation was fitted on the CF=1 PM2.5 output of the same Plantower module family, with the RH
 * the sensor housing measures, so it reads `pm25_cf1_ug_m3` and the PMS5003T's own
 * `relative_humidity_percent`, never the atmospheric column. Its stated domain is a non-negative
 * PM2.5 and an RH between 0 and 100 percent, with no upper PM2.5 limit (the top segment was fitted
 * on wildfire smoke). Outside that domain it gives no value, it never extrapolates, and the caller
 * shows raw PM2.5 labelled raw. At low PM2.5 and high RH the equation can go below zero, and the
 * result is then clamped to 0, as the EPA does.
 *
 * Correct each row, then average. The equation is not linear, so it is never applied to a mean.
 */
object EpaPm25Correction {
    const val SOURCE = "US EPA PurpleAir correction (Barkjohn et al. 2021, extended 2022)"
    const val URL = "https://doi.org/10.5194/amt-14-4617-2021"

    /** An RH the equation accepts, finite and from 0 to 100 percent. */
    fun isValidRh(rh: Double?): Boolean = rh != null && rh.isFinite() && rh in 0.0..100.0

    /** A CF=1 PM2.5 the equation accepts, finite and not negative. */
    fun isValidPm(cf1: Double?): Boolean = cf1 != null && cf1.isFinite() && cf1 >= 0.0

    /** The corrected PM2.5 in µg/m³, or null when [cf1] or [rh] is outside the equation's domain. */
    fun correct(cf1: Double?, rh: Double?): Double? {
        if (!isValidPm(cf1) || !isValidRh(rh)) return null
        val x = cf1!!
        val h = rh!!
        val y = when {
            x < 30.0 -> 0.524 * x - 0.0862 * h + 5.75
            x < 50.0 -> {
                val t = x / 20.0 - 1.5
                (0.786 * t + 0.524 * (1 - t)) * x - 0.0862 * h + 5.75
            }
            x < 210.0 -> 0.786 * x - 0.0862 * h + 5.75
            x < 260.0 -> {
                val t = x / 50.0 - 4.2
                (0.69 * t + 0.786 * (1 - t)) * x - 0.0862 * h * (1 - t) + 2.966 * t + 5.75 * (1 - t) + 8.84e-4 * x * x * t
            }
            else -> 2.966 + 0.69 * x + 8.84e-4 * x * x
        }
        return if (y < 0.0) 0.0 else y
    }
}
