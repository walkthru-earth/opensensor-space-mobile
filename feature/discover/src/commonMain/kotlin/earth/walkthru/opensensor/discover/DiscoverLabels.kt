package earth.walkthru.opensensor.discover

import androidx.compose.runtime.Composable
import earth.walkthru.aqsync.airquality.Pm25Basis
import earth.walkthru.aqsync.publicfeed.FeedProperty
import earth.walkthru.opensensor.designsystem.pm25Label

/**
 * The label Discover shows for a feed property. The public feed carries pre-aggregated PM2.5 means
 * without humidity, so the EPA correction cannot run and PM2.5 is always labelled raw (#92).
 */
@Composable
internal fun FeedProperty.shownLabel(): String = if (isRawPm25(this)) pm25Label(Pm25Basis.RAW) else label

/** True for the feed's PM2.5, the one property whose label says raw. */
internal fun isRawPm25(property: FeedProperty): Boolean = property.id == "pm25"
