package earth.walkthru.opensensor.discover

import earth.walkthru.aqsync.publicfeed.BundledSampleFeed
import earth.walkthru.aqsync.publicfeed.FeedProperty
import earth.walkthru.aqsync.publicfeed.PublicFeed
import earth.walkthru.aqsync.publicfeed.platformFeedReader
import earth.walkthru.opensensor.discover.resources.Res
import kotlin.math.abs
import kotlin.math.roundToLong

/** The phone's IANA time zone, used only to pick the region the map first opens on. */
internal expect fun currentTimeZoneId(): String

internal expect fun nowEpochMillis(): Long

/** The bundled sample feed, read from this module's Compose resources. */
fun bundledSampleFeed(): PublicFeed = BundledSampleFeed(platformFeedReader()) { path -> Res.readBytes(path) }

/** The basemap style, light or dark, bundled as a Compose resource (tools/discover-fixture). */
internal suspend fun basemapStyle(dark: Boolean): String = Res.readBytes(if (dark) "files/basemap/dark.json" else "files/basemap/light.json").decodeToString()

/** A style with no network sources, for when public data is off or before the style loads. */
internal fun paperStyle(color: String): String =
    "{\"version\":8,\"sources\":{},\"layers\":[{\"id\":\"paper\",\"type\":\"background\",\"paint\":{\"background-color\":\"$color\"}}]}"

/**
 * A value at the property's precision from the dictionary (1 → "14", 0.5 → "21.5"). Without a
 * precision, one decimal. Common code has no `String.format`.
 */
fun formatValue(value: Double, property: FeedProperty?): String {
    val step = property?.precision
    val decimals = when {
        step == null -> 1
        step >= 1.0 -> 0
        step >= 0.1 -> 1
        else -> 2
    }
    return formatDecimals(value, decimals)
}

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
