package space.opensensor.family.air

import space.opensensor.model.BoardTemplate
import space.opensensor.model.Datastream
import space.opensensor.model.Thing

/**
 * The `opensensor:property` URIs the air family reads, the same on every board. A column is found by
 * its property, never by its name, so a new board with these properties needs no app change.
 */
object AirProperty {
    const val PM1 = "http://vocab.nerc.ac.uk/standard_name/mass_concentration_of_pm1_ambient_aerosol_particles_in_air/"
    const val PM25 = "http://vocab.nerc.ac.uk/collection/P07/current/YUDG9DXK/"
    const val PM10 = "http://vocab.nerc.ac.uk/standard_name/mass_concentration_of_pm10_ambient_aerosol_particles_in_air/"
    const val PM1_CF1 = "urn:walkthru-earth:property:pm1_cf1_mass_concentration"
    const val PM25_CF1 = "urn:walkthru-earth:property:pm25_cf1_mass_concentration"
    const val PM10_CF1 = "urn:walkthru-earth:property:pm10_cf1_mass_concentration"
    const val RELATIVE_HUMIDITY = "http://vocab.nerc.ac.uk/collection/P07/current/CFSN0413/"
    const val AIR_TEMPERATURE = "http://vocab.nerc.ac.uk/collection/P07/current/CFSN0023/"
    const val CARBON_DIOXIDE = "http://vocab.nerc.ac.uk/standard_name/mole_fraction_of_carbon_dioxide_in_air/"
    const val BATCH_ESTIMATE = "urn:walkthru-earth:property:pm25_batch_candidate_mass_concentration"

    /** The prefix of a particle count bin, `particle_count_gt_<size>`, per 0.1 L. */
    const val PARTICLE_COUNT_PREFIX = "urn:walkthru-earth:property:particle_count_gt_"

    /** The count bins from the smallest size up, `0p3um` is particles larger than 0.3 µm. */
    val PARTICLE_COUNTS: List<String> = listOf("0p3um", "0p5um", "1um", "2p5um", "5um", "10um").map { PARTICLE_COUNT_PREFIX + it }

    /** The Plantower `opensensor:procedure` ids, the sensors the US EPA correction was fitted on. */
    val PLANTOWER: Set<String> = setOf("pmsa003", "pms5003t")

    /** The size label of a count bin property, `>0.3` for `particle_count_gt_0p3um`. */
    fun binLabel(property: String): String = ">" + property.removePrefix(PARTICLE_COUNT_PREFIX).removeSuffix("um").replace('p', '.')
}

/**
 * The air columns of one template, each found by its `opensensor:property` ([AirProperty]). Null
 * from [of] for a template without a stored PM2.5 measurement, so such a board has no air surface.
 */
class AirColumns private constructor(
    /** The PM2.5 the sensor reports, the column the status gate, NowCast and hero read. */
    val pm25: Datastream,
    val pm1: Datastream?,
    val pm10: Datastream?,
    val pm25Cf1: Datastream?,
    /** The CF=1 PM1, PM2.5 and PM10, in that order, each null where the template has none. */
    val cf1: List<Datastream?>,
    val humidity: Datastream?,
    /** The particle count bins the template declares, from the smallest size up. */
    val bins: List<Datastream>,
    val batchEstimate: Datastream?,
) {
    /** The count of particles larger than 0.3 µm, the input of the batch estimate. */
    val gt03: Datastream? get() = bins.firstOrNull { it.property?.id == AirProperty.PARTICLE_COUNTS.first() }

    /** True when the US EPA correction applies, a Plantower CF=1 PM2.5 with the sensor's RH. */
    val epa: Boolean get() = pm25Cf1 != null && humidity != null && pm25Cf1.sensor?.procedure in AirProperty.PLANTOWER

    companion object {
        /** The air columns of [template], or null when it stores no PM2.5 measurement. A scan of its columns. */
        fun of(template: BoardTemplate): AirColumns? {
            fun column(property: String): Datastream? = template.datastreams.firstOrNull { !it.isDerived && it.property?.id == property }
            val pm25 = column(AirProperty.PM25) ?: return null
            return AirColumns(
                pm25 = pm25,
                pm1 = column(AirProperty.PM1),
                pm10 = column(AirProperty.PM10),
                pm25Cf1 = column(AirProperty.PM25_CF1),
                cf1 = listOf(AirProperty.PM1_CF1, AirProperty.PM25_CF1, AirProperty.PM10_CF1).map(::column),
                humidity = column(AirProperty.RELATIVE_HUMIDITY),
                bins = AirProperty.PARTICLE_COUNTS.mapNotNull(::column),
                // The board computes the batch estimate itself, so it is a stored column marked derived.
                batchEstimate = template.datastreams.firstOrNull { it.property?.id == AirProperty.BATCH_ESTIMATE },
            )
        }
    }
}

/** The air columns of this thing's template, or null for a thing without a PM2.5 measurement. */
val Thing.air: AirColumns? get() = AirColumns.of(template)
