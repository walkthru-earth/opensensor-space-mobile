package space.opensensor.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.maplibre.compose.expressions.dsl.asBoolean
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToColor
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import space.opensensor.data.history.LocationSource
import space.opensensor.designsystem.FitText
import space.opensensor.designsystem.InfoButton
import space.opensensor.designsystem.InfoContent
import space.opensensor.designsystem.SectionCard
import space.opensensor.history.resources.Res
import space.opensensor.history.resources.history_track
import space.opensensor.history.resources.history_track_areas
import space.opensensor.history.resources.history_track_gps
import space.opensensor.history.resources.history_track_map
import space.opensensor.history.resources.history_track_no_fix
import space.opensensor.history.resources.history_track_phone
import space.opensensor.history.resources.history_track_picked
import space.opensensor.history.resources.info_history_track1
import space.opensensor.history.resources.info_history_track2
import space.opensensor.history.resources.info_history_track3
import space.opensensor.history.resources.info_history_track4
import space.opensensor.map.BaseMap
import space.opensensor.map.LocalMapNetwork
import space.opensensor.map.MapCredits
import space.opensensor.map.basemapInfo
import space.opensensor.map.rememberBaseMapState
import space.opensensor.map.rememberMapStyle
import space.opensensor.publicfeed.platformH3Grid

/**
 * The colours of the track by `location_source` (#263). A picked area takes the neutral muted ink,
 * the two reported sources a teal and an ochre. Neither is the brand blue, which marks selection
 * only, nor purple. [ground] is what the strip and the legend draw them on, the card's own colour,
 * and each reaches 3:1 on it in light and dark (`HistoryTrackTest`). [frame] outlines the strip.
 */
@Immutable
internal data class TrackColours(val picked: Color, val gps: Color, val phone: Color, val ground: Color, val frame: Color) {
    fun of(source: LocationSource): Color = when (source) {
        LocationSource.CONFIGURED -> picked
        LocationSource.GNSS -> gps
        LocationSource.PHONE -> phone
    }

    companion object {
        val GPS = Color(0xFF00806F)
        val PHONE = Color(0xFFB8803A)

        /** [SectionCard] draws on `surfaceContainerLow`, so the strip does too. */
        fun of(scheme: ColorScheme) = TrackColours(
            picked = scheme.onSurfaceVariant,
            gps = GPS,
            phone = PHONE,
            ground = scheme.surfaceContainerLow,
            frame = scheme.outlineVariant,
        )
    }
}

/**
 * Where the rows of the visible Day or Week put the Thing (#263). A map of the distinct H3 cells
 * coloured by source, the newest with a thick outline, a strip of the span in time order with the
 * rows that had no location outlined, and a legend of the sources present. The map stays still
 * inside the list and fits the cells once per span.
 */
@Composable
internal fun HistoryTrackCard(track: HistoryTrack) {
    val title = stringResource(Res.string.history_track)
    val colours = TrackColours.of(MaterialTheme.colorScheme)
    SectionCard(
        title,
        trailing = {
            InfoButton(
                InfoContent(
                    title,
                    stringResource(Res.string.info_history_track1),
                    stringResource(Res.string.info_history_track2),
                    stringResource(Res.string.info_history_track3),
                    stringResource(Res.string.info_history_track4),
                ),
            )
        },
    ) {
        val areas = pluralStringResource(Res.plurals.history_track_areas, track.cells.size, track.cells.size)
        TrackMap(track, colours, "${stringResource(Res.string.history_track_map)}, $areas")
        TrackStrip(track, colours, Modifier.fillMaxWidth().padding(top = 12.dp).height(14.dp))
        TrackLegend(track, colours, Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun TrackMap(track: HistoryTrack, colours: TrackColours, description: String) {
    val h3 = remember { platformH3Grid() }
    if (!h3.available) return
    val networkAllowed = LocalMapNetwork.current
    val style = rememberMapStyle(networkAllowed)
    val shape = remember(track, colours) { HistoryTrackMap.shape(h3, track) { colours.of(it).toArgb() } } ?: return
    val ink = MaterialTheme.colorScheme.onSurface
    Box(Modifier.fillMaxWidth().height(MAP_HEIGHT).clip(RoundedCornerShape(16.dp)).semantics { contentDescription = description }) {
        if (style == null) return@Box
        // A new span is a new map. Within one, the still map fits its cells again as a sync adds more
        // (BaseMap). A track across the antimeridian has no bounds to fit, so its map opens again on
        // the newest cell whenever that cell moves.
        key(track.thing, track.window, if (shape.bounds == null) shape.centre else null) {
            val state = rememberBaseMapState(style, shape.centre, OPEN_ZOOM) {
                val cells = rememberGeoJsonSource(GeoJsonData.JsonString(shape.geoJson))
                FillLayer(id = "history-track-fill", source = cells, color = feature["color"].convertToColor(), opacity = const(0.55f))
                LineLayer(id = "history-track-outline", source = cells, color = feature["color"].convertToColor(), width = const(1.5.dp))
                LineLayer(id = "history-track-last", source = cells, filter = feature["last"].asBoolean(), color = const(ink), width = const(3.dp))
            }
            BaseMap(state, Modifier.fillMaxSize(), fitOnce = shape.bounds, movable = false)
        }
        if (networkAllowed) Box(Modifier.align(Alignment.BottomStart).padding(8.dp)) { MapCredits(networkAllowed, basemapInfo()) }
    }
}

/**
 * The span from left to right on the card's own colour inside a thin frame. A stretch with a fix
 * takes its source colour, one without is outlined.
 */
@Composable
private fun TrackStrip(track: HistoryTrack, colours: TrackColours, modifier: Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        val edge = 1.dp.toPx()
        drawRoundRect(colours.ground, cornerRadius = CornerRadius(size.height / 2))
        drawRoundRect(
            colours.frame,
            Offset(edge / 2, edge / 2),
            Size(size.width - edge, size.height - edge),
            CornerRadius(size.height / 2),
            style = Stroke(edge),
        )
        val minWidth = 2.dp.toPx()
        for (bar in track.bars) {
            val width = maxOf(minWidth, (bar.end - bar.start) * size.width)
            val left = minOf(bar.start * size.width, size.width - width)
            val source = bar.source
            if (source == null) {
                val stroke = 1.dp.toPx()
                drawRect(outline, Offset(left + stroke / 2, stroke / 2), Size(width - stroke, size.height - stroke), style = Stroke(stroke))
            } else {
                drawRect(colours.of(source), Offset(left, 0f), Size(width, size.height))
            }
        }
    }
}

@Composable
private fun TrackLegend(track: HistoryTrack, colours: TrackColours, modifier: Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (source in track.sources) {
            LegendItem(sourceLabel(source)) { Modifier.background(colours.of(source), SWATCH_SHAPE) }
        }
        if (track.noFix) LegendItem(stringResource(Res.string.history_track_no_fix)) { Modifier.border(1.dp, outline, SWATCH_SHAPE) }
    }
}

@Composable
private fun LegendItem(label: String, swatch: () -> Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).then(swatch()))
        Spacer(Modifier.size(6.dp))
        FitText(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun sourceLabel(source: LocationSource): String = stringResource(
    when (source) {
        LocationSource.CONFIGURED -> Res.string.history_track_picked
        LocationSource.GNSS -> Res.string.history_track_gps
        LocationSource.PHONE -> Res.string.history_track_phone
    },
)

private val MAP_HEIGHT = 220.dp
private val SWATCH_SHAPE = RoundedCornerShape(3.dp)

/** The zoom the map opens on before it fits the cells, about a town. */
private const val OPEN_ZOOM = 12.0
