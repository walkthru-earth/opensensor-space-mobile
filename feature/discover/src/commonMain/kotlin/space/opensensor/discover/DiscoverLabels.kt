package space.opensensor.discover

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import space.opensensor.designsystem.pm25LabelRes
import space.opensensor.designsystem.preferredLanguage
import space.opensensor.discover.resources.Res
import space.opensensor.discover.resources.discover_category_air
import space.opensensor.discover.resources.discover_category_weather
import space.opensensor.discover.resources.discover_property_no2
import space.opensensor.discover.resources.discover_property_pressure
import space.opensensor.family.air.Pm25Basis
import space.opensensor.model.BundledCatalog
import space.opensensor.model.PropertyRegistry
import space.opensensor.publicfeed.FeedProperty

/**
 * The label Discover shows for a feed property, in the app's language (#145). A property the
 * Collection ties to an `opensensor:property` the registry lists takes the registry's short label
 * when the registry has one in that language, else its label. A few feed ids the registry does not
 * list yet keep the app's own string. Any other property shows the feed's label, the Collection
 * column title when the feed has one, else the dictionary label. The feed carries pre-aggregated
 * PM2.5 means without humidity, so the EPA correction cannot run and PM2.5 is always labelled raw (#92).
 */
@Composable
internal fun FeedProperty.shownLabel(): String = shownLabel(this, preferredLanguage()) { stringResource(it) }

/** [property]'s label in [language], through [resolve] for an app string, else the registry, else the feed label. */
internal inline fun shownLabel(
    property: FeedProperty,
    language: String,
    registry: PropertyRegistry = BundledCatalog.properties,
    resolve: (StringResource) -> String,
): String = shownLabelRes(property)?.let(resolve) ?: registryLabel(property, language, registry) ?: property.label

/** The app's own label for [property], else null for an id the registry names or the app does not know. */
internal fun shownLabelRes(property: FeedProperty): StringResource? = propertyLabels[property.id]

/**
 * The registry's word for [property] in [language], its short label when the registry has one in
 * exactly that language, else its label. Null when the property names no registry entry.
 */
internal fun registryLabel(property: FeedProperty, language: String, registry: PropertyRegistry = BundledCatalog.properties): String? {
    val entry = property.property?.let { registry[it] } ?: return null
    return entry.shortLabel?.byLanguage?.get(language) ?: entry.label.get(language)
}

/**
 * The feed property ids the app names itself. PM2.5 is the raw label (#92), and pressure and NO2
 * have no registry entry yet (tools/discover-fixture).
 */
private val propertyLabels: Map<String, StringResource> = mapOf(
    "pm25" to pm25LabelRes(Pm25Basis.RAW),
    "pressure" to Res.string.discover_property_pressure,
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
