package space.opensensor.airquality

import space.opensensor.model.BoardTemplate
import space.opensensor.model.Derivation
import space.opensensor.model.DerivedMetric
import space.opensensor.model.DerivedMetricProvider
import space.opensensor.model.RowFormula

/**
 * The air family's phone derived metrics. A template that stores CF=1 PM2.5 and RH gets the US EPA
 * corrected PM2.5 ([EpaPm25Correction], #92), a corrected view of the atmospheric PM2.5 that shares
 * its scale and primary flag. Nothing stored changes.
 */
object AirDerivedMetrics : DerivedMetricProvider {
    /** The corrected PM2.5 id, a stable tier key. */
    const val PM25_CORRECTED_ID = "pm25_epa"

    /** The `derived.method` of the corrected PM2.5. */
    const val EPA_METHOD = "us-epa-pm25-2021"

    const val PM25_CF1 = "pm25_cf1_ug_m3"
    const val PM25_ATMOSPHERIC = "pm25_atmospheric_ug_m3"
    const val RELATIVE_HUMIDITY = "relative_humidity_percent"

    override fun derive(template: BoardTemplate): List<DerivedMetric> {
        val cf1 = template.datastream(PM25_CF1) ?: return emptyList()
        if (template.datastream(RELATIVE_HUMIDITY) == null) return emptyList()
        val corrected = cf1.copy(
            name = PM25_CORRECTED_ID,
            fieldId = null,
            liveKey = null,
            unitLabel = null,
            derived = Derivation(listOf(PM25_CF1, RELATIVE_HUMIDITY), EPA_METHOD),
        )
        return listOf(DerivedMetric(corrected, label = "PM2.5 corrected", formula = EpaFormula, viewOf = PM25_ATMOSPHERIC))
    }

    private object EpaFormula : RowFormula {
        override fun valueOf(primary: Double, second: Double?): Double? = EpaPm25Correction.correct(primary, second)
    }
}
