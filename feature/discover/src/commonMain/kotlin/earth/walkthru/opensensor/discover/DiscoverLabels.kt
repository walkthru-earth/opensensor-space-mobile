package earth.walkthru.opensensor.discover

import androidx.compose.runtime.Composable
import earth.walkthru.aqsync.airquality.Pm25Basis
import earth.walkthru.aqsync.publicfeed.FeedProperty
import earth.walkthru.opensensor.designsystem.pm25LabelRes
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The label Discover shows for a feed property. The public feed carries pre-aggregated PM2.5 means
 * without humidity, so the EPA correction cannot run and PM2.5 is always labelled raw (#92).
 */
@Composable
internal fun FeedProperty.shownLabel(): String = shownLabelRes(this)?.let { stringResource(it) } ?: label

/** The app's own label for [property] when it replaces the feed's, else null for the feed label. */
internal fun shownLabelRes(property: FeedProperty): StringResource? = if (isRawPm25(property)) pm25LabelRes(Pm25Basis.RAW) else null

/** True for the feed's PM2.5, the one property whose label says raw. */
internal fun isRawPm25(property: FeedProperty): Boolean = property.id == "pm25"
