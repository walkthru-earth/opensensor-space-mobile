package space.opensensor.family.air

import space.opensensor.model.HealthScale
import space.opensensor.publicfeed.DiscoverCollection
import space.opensensor.publicfeed.StepScale

/**
 * The scales the air family ships for a feed that has no Collection yet (the bundled sample and the
 * OpenAQ manifest feed), installed as [DiscoverCollection.BUNDLED] by [AirFamily.install]. PM2.5 and
 * PM10 on the WHO 2021 guideline and interim targets, the same edges and colours as [WhoGuidelines]
 * and [WhoStep] and the same step names as the app's step strings.
 */
object AirDiscover {
    /** The `who-2021-pm25` and `who-2021-pm10` scales as a STAC Collection, for [DiscoverCollection.installBundled]. */
    val COLLECTION: String by lazy {
        """
            {"type":"Collection","id":"discover-cells-bundled",
             "opensensor:scales":{
               "who-2021-pm25":${scale(WhoGuidelines.PM25)},
               "who-2021-pm10":${scale(WhoGuidelines.PM10)}},
             "item_assets":{"data":{"table:columns":[
               {"name":"pm25__mean","opensensor:scale":"who-2021-pm25"},
               {"name":"pm10__mean","opensensor:scale":"who-2021-pm10"}]}}}
        """.trimIndent()
    }

    private fun scale(who: HealthScale): String {
        val uppers = who.edges.map { it.toString() } + "null"
        val steps = uppers.indices.joinToString(",") { i ->
            """{"upper":${uppers[i]},"label":{"en":"${WHO_EN[i]}","ar":"${WHO_AR[i]}"},"color_hint":"${hex(WhoStep.entries[i].color)}"}"""
        }
        return """{"source":"${WhoGuidelines.URL}","unit":"${who.unit}","guideline":${who.guideline},"steps":[$steps]}"""
    }

    private fun hex(argb: Int): String = (argb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()

    private val WHO_EN = listOf(
        "Within the WHO guideline",
        "Within WHO interim target 4",
        "Within WHO interim target 3",
        "Within WHO interim target 2",
        "Within WHO interim target 1",
        "Above every WHO target",
    )
    private val WHO_AR = listOf(
        "ضمن إرشاد منظمة الصحة العالمية",
        "ضمن الهدف المرحلي 4 لمنظمة الصحة العالمية",
        "ضمن الهدف المرحلي 3 لمنظمة الصحة العالمية",
        "ضمن الهدف المرحلي 2 لمنظمة الصحة العالمية",
        "ضمن الهدف المرحلي 1 لمنظمة الصحة العالمية",
        "فوق كل أهداف منظمة الصحة العالمية",
    )
}

/**
 * The scale as a WHO guideline scale, for the mood surfaces (Simple mode). Set only for a scale
 * cited to the WHO 2021 guidelines with six steps and an open last step.
 */
val StepScale.who: HealthScale?
    get() = if (scale.source == WhoGuidelines.URL && size == WhoStep.entries.size && scale.steps.last().upper == null) {
        HealthScale(propertyId, scale.unit.orEmpty(), edges, WhoGuidelines.SOURCE)
    } else {
        null
    }
