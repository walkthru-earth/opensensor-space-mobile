package space.opensensor.family.air

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.opensensor.model.HealthScale
import space.opensensor.publicfeed.DiscoverCollection
import space.opensensor.publicfeed.StepScale

/**
 * The scales the air family ships for a feed that has no Collection yet (the bundled sample and the
 * OpenAQ manifest feed), installed as [DiscoverCollection.BUNDLED] by [AirFamily.install]. PM2.5 and
 * PM10 on the WHO 2021 guideline and interim targets, the same edges and colours as [WhoGuidelines]
 * and [WhoStep], with the registry step names [WhoGuidelines] carries.
 */
object AirDiscover {
    /** The `who-2021-pm25` and `who-2021-pm10` scales as a STAC Collection, for [DiscoverCollection.installBundled]. */
    val COLLECTION: String by lazy {
        """
            {"type":"Collection","id":"discover-cells-bundled",
             "opensensor:scales":{
               "${WhoGuidelines.PM25_ID}":${scale(WhoGuidelines.PM25)},
               "${WhoGuidelines.PM10_ID}":${scale(WhoGuidelines.PM10)}},
             "item_assets":{"data":{"table:columns":[
               {"name":"pm25__mean","opensensor:scale":"${WhoGuidelines.PM25_ID}"},
               {"name":"pm10__mean","opensensor:scale":"${WhoGuidelines.PM10_ID}"}]}}}
        """.trimIndent()
    }

    private fun scale(who: HealthScale): String {
        val uppers = who.edges.map { it.toString() } + "null"
        val steps = uppers.indices.joinToString(",") { i ->
            val label = JsonObject(who.names[i].byLanguage.mapValues { JsonPrimitive(it.value) })
            """{"upper":${uppers[i]},"label":$label,"color_hint":"${hex(WhoStep.entries[i].color)}"}"""
        }
        return """{"source":"${WhoGuidelines.URL}","unit":"${who.unit}","guideline":${who.guideline},"steps":[$steps]}"""
    }

    private fun hex(argb: Int): String = (argb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
}

/**
 * The scale as a WHO guideline scale, for the mood surfaces (Simple mode). Set only for a scale
 * cited to the WHO 2021 guidelines with six steps and an open last step.
 */
val StepScale.who: HealthScale?
    get() = if (scale.source == WhoGuidelines.URL && size == WhoStep.entries.size && scale.steps.last().upper == null) {
        HealthScale(propertyId, scale.unit.orEmpty(), edges, WhoGuidelines.SOURCE, scale.steps.map { it.label })
    } else {
        null
    }
