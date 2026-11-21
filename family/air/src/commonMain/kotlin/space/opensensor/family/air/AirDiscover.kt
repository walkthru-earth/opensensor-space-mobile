package space.opensensor.family.air

import space.opensensor.model.HealthScale
import space.opensensor.publicfeed.DiscoverCollection
import space.opensensor.publicfeed.StepScale

/**
 * The Collection the air family ships for a feed that has none yet (the bundled sample and the
 * OpenAQ manifest feed), installed as [DiscoverCollection.BUNDLED] by [AirFamily.install]. It ties
 * each feed property id the air family knows to its `opensensor:property`, so Discover labels it
 * from the property registry, and names the registry's WHO 2021 scales for PM2.5 and PM10. Their
 * edges, colours and step names come from the bundled scale registry, never from a copy here.
 */
object AirDiscover {
    /** The feed property ids the air family knows, each to its `opensensor:property` URI. */
    val PROPERTIES: Map<String, String> = mapOf(
        "pm25" to AirProperty.PM25,
        "pm10" to AirProperty.PM10,
        "pm1" to AirProperty.PM1,
        "um003" to AirProperty.PARTICLE_COUNTS.first(),
        "temperature" to AirProperty.AIR_TEMPERATURE,
        "humidity" to AirProperty.RELATIVE_HUMIDITY,
        "co2" to AirProperty.CARBON_DIOXIDE,
    )

    /** The registry scale of each feed property that has one. */
    private val SCALES: Map<String, String> = mapOf(
        "pm25" to WhoGuidelines.PM25_ID,
        "pm10" to WhoGuidelines.PM10_ID,
    )

    /** The Collection, for [DiscoverCollection.installBundled]. */
    val COLLECTION: String by lazy {
        val columns = PROPERTIES.entries.joinToString(",") { (id, uri) ->
            val scale = SCALES[id]?.let { ""","opensensor:scale":"$it"""" }.orEmpty()
            """{"name":"${id}__mean","opensensor:property":"$uri"$scale}"""
        }
        """{"type":"Collection","id":"discover-cells-bundled","item_assets":{"data":{"table:columns":[$columns]}}}"""
    }
}

/**
 * The WHO guideline scale this scale is, for the mood surfaces (Simple mode). Set only for a scale
 * cited to the WHO 2021 guidelines whose edges are those of a [WhoGuidelines] registry scale.
 */
val StepScale.who: HealthScale?
    get() = if (scale.source == WhoGuidelines.URL && scale.steps.last().upper == null) {
        WhoGuidelines.all.firstOrNull { it.edges == edges }
    } else {
        null
    }
