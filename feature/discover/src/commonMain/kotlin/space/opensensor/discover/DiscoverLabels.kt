package space.opensensor.discover

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import space.opensensor.airquality.Pm25Basis
import space.opensensor.designsystem.pm25LabelRes
import space.opensensor.discover.resources.Res
import space.opensensor.discover.resources.discover_category_air
import space.opensensor.discover.resources.discover_category_weather
import space.opensensor.discover.resources.discover_property_co2
import space.opensensor.discover.resources.discover_property_humidity
import space.opensensor.discover.resources.discover_property_no2
import space.opensensor.discover.resources.discover_property_pm1
import space.opensensor.discover.resources.discover_property_pm10
import space.opensensor.discover.resources.discover_property_pressure
import space.opensensor.discover.resources.discover_property_temperature
import space.opensensor.discover.resources.discover_property_um003
import space.opensensor.publicfeed.FeedProperty

/**
 * The label Discover shows for a feed property, in the app's language (#145). Every property id the
 * app knows has its own string in English and Arabic. Any other id shows the feed's label, the
 * Collection column title when the feed has one, else the dictionary label. The feed carries
 * pre-aggregated PM2.5 means without humidity, so the EPA correction cannot run and PM2.5 is always
 * labelled raw (#92).
 */
@Composable
internal fun FeedProperty.shownLabel(): String = shownLabel(this) { stringResource(it) }

/** [property]'s label through [resolve], or the feed label when the app does not know its id. */
internal inline fun shownLabel(property: FeedProperty, resolve: (StringResource) -> String): String =
    shownLabelRes(property)?.let(resolve) ?: property.label

/** The app's own label for [property], else null for an id the app does not know. */
internal fun shownLabelRes(property: FeedProperty): StringResource? =
    propertyLabels[property.id]

/** The feed property ids the app names itself (tools/discover-fixture). */
private val propertyLabels: Map<String, StringResource> = mapOf(
    "pm25" to pm25LabelRes(Pm25Basis.RAW),
    "pm10" to Res.string.discover_property_pm10,
    "pm1" to Res.string.discover_property_pm1,
    "um003" to Res.string.discover_property_um003,
    "temperature" to Res.string.discover_property_temperature,
    "humidity" to Res.string.discover_property_humidity,
    "pressure" to Res.string.discover_property_pressure,
    "co2" to Res.string.discover_property_co2,
    "no2" to Res.string.discover_property_no2,
)

/** The heading Discover shows for a dictionary category, the feed's own word when the app does not know it. */
@Composable
internal fun shownCategory(category: String): String = shownCategory(category) { stringResource(it) }

/** [category]'s heading through [resolve], or the feed's word when the app does not know it. */
internal inline fun shownCategory(category: String, resolve: (StringResource) -> String): String =
    categoryLabelRes(category)?.let(resolve) ?: category

/** The app's own heading for a feed category, else null. */
internal fun categoryLabelRes(category: String): StringResource? = categoryLabels[category]

private val categoryLabels: Map<String, StringResource> = mapOf(
    "Air" to Res.string.discover_category_air,
    "Weather" to Res.string.discover_category_weather,
)
