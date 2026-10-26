package space.opensensor.discover

import space.opensensor.discover.resources.Res
import space.opensensor.publicfeed.BundledSampleFeed
import space.opensensor.publicfeed.FeedProperty
import space.opensensor.publicfeed.PublicFeed
import space.opensensor.units.UnitFormatter
import kotlin.math.abs
import kotlin.math.roundToLong

/** The phone's IANA time zone, used only to pick the region the map first opens on. */
internal expect fun currentTimeZoneId(): String

internal expect fun nowEpochMillis(): Long

/** The bundled sample feed, read from this module's Compose resources. */
fun bundledSampleFeed(): PublicFeed = BundledSampleFeed { path -> Res.readBytes(path) }

/** The basemap style, light or dark, bundled as a Compose resource (tools/discover-fixture). */
internal suspend fun basemapStyle(dark: Boolean): String {
    val flavour = if (dark) "dark" else "light"
    return Res.readBytes("files/basemap/$flavour.json").decodeToString()
}

/** A style with no network sources, for when public data is off or before the style loads. */
internal fun paperStyle(color: String): String =
    "{\"version\":8,\"sources\":{},\"layers\":[{\"id\":\"paper\",\"type\":\"background\",\"paint\":{\"background-color\":\"$color\"}}]}"

/**
 * A value at the property's precision from the dictionary (1 → "14", 0.5 → "21.5"). Without a
 * precision, one decimal. Common code has no `String.format`. Under [units] a convertible unit is
 * converted for display and shown at that unit's own precision (21.5 °C → "70.7" °F). Scales,
 * colours and statistics stay on the feed's SI values.
 */
fun formatValue(value: Double, property: FeedProperty?, units: UnitFormatter = UnitFormatter.Metric): String {
    val unit = property?.unit
    if (unit != null) {
        units.decimals(unit)?.let { return formatDecimals(units.value(value, unit), it) }
    }
    val step = property?.precision
    val decimals = when {
        step == null -> 1
        step >= 1.0 -> 0
        step >= 0.1 -> 1
        else -> 2
    }
    return formatDecimals(value, decimals)
}

/** The property's unit as shown under [units] ("°C" → "°F" for imperial). */
fun unitLabel(property: FeedProperty, units: UnitFormatter = UnitFormatter.Metric): String = units.unit(property.unit)

internal fun formatDecimals(value: Double, decimals: Int): String {
    if (!value.isFinite()) return value.toString()
    var scale = 1L
    repeat(decimals) { scale *= 10 }
    val scaled = (abs(value) * scale).roundToLong()
    val whole = scaled / scale
    val frac = scaled % scale
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    return if (decimals == 0) "$sign$whole" else "$sign$whole." + frac.toString().padStart(decimals, '0')
}
