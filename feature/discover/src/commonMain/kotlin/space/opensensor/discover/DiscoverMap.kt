package space.opensensor.discover

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.compose.expressions.dsl.all
import org.maplibre.compose.expressions.dsl.asBoolean
import org.maplibre.compose.expressions.dsl.asNumber
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.condition
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToColor
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.gt
import org.maplibre.compose.expressions.dsl.gte
import org.maplibre.compose.expressions.dsl.lt
import org.maplibre.compose.expressions.dsl.lte
import org.maplibre.compose.expressions.dsl.not
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.MapState
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import space.opensensor.map.NearMe
import space.opensensor.map.NearMeLayer
import space.opensensor.map.rememberBaseMapState
import space.opensensor.publicfeed.LatLng

/**
 * The map and its one GeoJSON source of H3 cells. Measured cells are filled with the scale colour
 * (faded when stale), unmeasured cells are an outline only, and the selected cell has a thick ink
 * outline. Clicks on a cell select it, clicks elsewhere clear the selection. [nearMe] draws its puck
 * on top once the user taps Near me and grants approximate location.
 *
 * [ownGeoJson] is the user's own fixed Things, a clustered point source of its own above the cells
 * (#226). A pin is a [paper] dot ringed in [ink], a cluster an [ink] badge with its count. Neither
 * takes the brand blue, which marks selection only. A tap on a cluster calls [onOwnCluster] with its
 * centre, a tap on a pin falls through to the cell under it.
 *
 * [ownTrackGeoJson] outlines the cells a moving own Thing's rows reported (#263), in [ink] under the
 * pins. It takes no tap, so a tap falls through to the cell under it.
 */
@Composable
internal fun rememberDiscoverMapState(
    start: StartView,
    styleJson: String,
    geoJson: String,
    selectedHex: String?,
    ink: Color,
    nearMe: NearMe,
    highlight: ValueBand?,
    ownGeoJson: String,
    paper: Color,
    onOwnCluster: (Position) -> Unit,
    ownTrackGeoJson: String = DiscoverUiState.EMPTY_GEOJSON,
    onCellClick: (String?) -> Unit,
): MapState {
    val (center, zoom) = when (start) {
        is StartView.Camera -> LatLng(start.camera.lat, start.camera.lng) to start.camera.zoom
        is StartView.Region -> LatLng((start.bounds.south + start.bounds.north) / 2, (start.bounds.west + start.bounds.east) / 2) to 3.0
    }
    return rememberBaseMapState(styleJson, center, zoom) {
        val cells = rememberGeoJsonSource(GeoJsonData.JsonString(geoJson))
        val stale = feature["stale"].asBoolean()
        // While a chart bar is touched or pinned, areas outside it fade so the bar's areas stand out.
        val v = feature["v"].asNumber()
        val inBand = when {
            highlight == null -> const(true)
            highlight.upperInclusive -> all(v gt const(highlight.low.toFloat()), v lte const(highlight.high.toFloat()))
            else -> all(v gte const(highlight.low.toFloat()), v lt const(highlight.high.toFloat()))
        }
        FillLayer(
            id = "discover-cells-fill",
            source = cells,
            filter = feature["measured"].asBoolean(),
            color = feature["color"].convertToColor(),
            opacity = switch(condition(!inBand, const(0.08f)), condition(stale, const(0.4f)), fallback = const(0.72f)),
            onClick = { features ->
                onCellClick(features.firstOrNull()?.properties?.get("h3")?.jsonPrimitive?.content)
                ClickResult.Consume
            },
        )
        // Cells that do not measure the chosen property: outline only, still tappable.
        FillLayer(
            id = "discover-cells-unmeasured",
            source = cells,
            filter = !feature["measured"].asBoolean(),
            color = const(Color.Transparent),
            onClick = { features ->
                onCellClick(features.firstOrNull()?.properties?.get("h3")?.jsonPrimitive?.content)
                ClickResult.Consume
            },
        )
        LineLayer(
            id = "discover-cells-outline",
            source = cells,
            color = const(ink),
            opacity = switch(condition(feature["measured"].asBoolean(), const(0.25f)), fallback = const(0.6f)),
            width = switch(condition(feature["measured"].asBoolean(), const(0.5.dp)), fallback = const(1.2.dp)),
        )
        LineLayer(
            id = "discover-cells-selected",
            source = cells,
            filter = feature["h3"].asString() eq const(selectedHex ?: "none"),
            color = const(ink),
            width = const(3.dp),
        )
        val track = rememberGeoJsonSource(GeoJsonData.JsonString(ownTrackGeoJson))
        FillLayer(
            id = "discover-own-track-fill",
            source = track,
            color = const(ink),
            opacity = const(0.12f),
        )
        LineLayer(
            id = "discover-own-track",
            source = track,
            color = const(ink),
            width = const(1.5.dp),
        )
        val own = rememberGeoJsonSource(GeoJsonData.JsonString(ownGeoJson)) {
            cluster = true
            clusterRadius = OWN_CLUSTER_RADIUS
            clusterMaxZoom = OWN_CLUSTER_MAX_ZOOM
        }
        val clustered = feature.has("point_count")
        CircleLayer(
            id = "discover-own-cluster",
            source = own,
            filter = clustered,
            color = const(ink),
            radius = const(14.dp),
            strokeColor = const(paper),
            strokeWidth = const(2.dp),
            onClick = { features ->
                val centre = (features.firstOrNull()?.geometry as? Point)?.coordinates
                if (centre != null) onOwnCluster(centre)
                ClickResult.Consume
            },
        )
        SymbolLayer(
            id = "discover-own-count",
            source = own,
            filter = clustered,
            textField = format(span(feature["point_count_abbreviated"].asString())),
            // A font the basemap glyphs already serve, so the badge asks for nothing new.
            textFont = const(listOf("Noto Sans Medium")),
            textSize = const(12.sp),
            textColor = const(paper),
            textAllowOverlap = const(true),
            textIgnorePlacement = const(true),
        )
        CircleLayer(
            id = "discover-own-pin",
            source = own,
            filter = !clustered,
            color = const(paper),
            radius = const(7.dp),
            strokeColor = const(ink),
            strokeWidth = const(3.dp),
        )
        // The library's standard blue puck and accuracy circle, drawn above the cells.
        NearMeLayer(nearMe)
    }
}

/** Own Things closer than this on screen merge into one badge. */
private const val OWN_CLUSTER_RADIUS = 44

/** Past this zoom every own Thing shows its own pin. */
private const val OWN_CLUSTER_MAX_ZOOM = 14
