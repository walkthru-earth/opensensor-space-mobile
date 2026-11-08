package space.opensensor.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.stringResource
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.spatialk.geojson.Position
import space.opensensor.designsystem.FloatingControl
import space.opensensor.designsystem.FloatingIconButton
import space.opensensor.designsystem.FloatingSurface
import space.opensensor.designsystem.InfoButton
import space.opensensor.designsystem.InfoContent
import space.opensensor.designsystem.LocalReduceMotion
import space.opensensor.designsystem.MinTouchSpacing
import space.opensensor.map.resources.Res
import space.opensensor.map.resources.map_info_geonames
import space.opensensor.map.resources.map_info_grid
import space.opensensor.map.resources.map_info_osm
import space.opensensor.map.resources.map_info_protomaps
import space.opensensor.map.resources.map_info_title
import space.opensensor.map.resources.map_osm
import space.opensensor.map.resources.map_pick_middle
import space.opensensor.map.resources.map_pick_zoom_in
import space.opensensor.map.resources.map_picker_label
import space.opensensor.map.resources.map_picker_selected
import space.opensensor.map.resources.map_picker_unavailable
import space.opensensor.model.H3Cell
import space.opensensor.publicfeed.GeoBounds
import space.opensensor.publicfeed.H3Grid
import space.opensensor.publicfeed.H3Index
import space.opensensor.publicfeed.LatLng
import space.opensensor.publicfeed.TimeZoneRegions
import space.opensensor.publicfeed.platformH3Grid
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * The grid maths of [CellPicker], kept apart from Compose so a test drives it with a fake [H3Grid].
 * Every cell is at [RESOLUTION], the one resolution a Thing publishes. Coordinates come in from
 * the camera and leave only as cells. Nothing here stores them.
 */
object CellPickerGrid {
    const val RESOLUTION = H3Cell.RESOLUTION

    /** Below this zoom the grid is hidden, since the cells are too small to tap. */
    const val MIN_ZOOM = 12.0

    /** The zoom the picker opens on around a chosen cell, and moves to for a place or a phone fix. */
    const val PICK_ZOOM = 14.0

    /** The most rings drawn around the centre cell, about 800 cells. A wider view shows the middle. */
    const val MAX_RINGS = 16

    // A ring of resolution 8 cells adds at least this distance, the apothem of an average cell twice.
    private const val RING_KM = 0.69
    private const val KM_PER_DEGREE = 111.32

    /** Rings of cells around the centre that cover [bounds], from 1 to [MAX_RINGS]. */
    fun rings(bounds: GeoBounds): Int {
        val midLat = (bounds.south + bounds.north) / 2 * PI / 180
        val span = bounds.east - bounds.west
        val lngSpan = if (span < 0) span + 360 else span
        val h = (bounds.north - bounds.south) * KM_PER_DEGREE
        val w = lngSpan * KM_PER_DEGREE * cos(midLat)
        val half = sqrt(h * h + w * w) / 2
        return (ceil(half / RING_KM).toInt() + 1).coerceIn(1, MAX_RINGS)
    }

    /** The cell under the camera centre at [lat], [lng], null below [MIN_ZOOM] or without H3. */
    fun centreCell(h3: H3Grid, zoom: Double, lat: Double, lng: Double): Long? {
        if (!h3.available || zoom < MIN_ZOOM) return null
        return runCatching { h3.latLngToCell(lat, lng, RESOLUTION) }.getOrNull()?.takeIf { H3Cell.isCell(it) && H3Index.resolution(it) == RESOLUTION }
    }

    /** The cells to outline for a camera at rest, empty below [MIN_ZOOM]. */
    fun cellsAround(h3: H3Grid, rest: CameraRest): List<Long> {
        val centre = centreCell(h3, rest.zoom, rest.lat, rest.lng) ?: return emptyList()
        return runCatching { h3.gridDisk(centre, rings(rest.bounds)) }.getOrElse { listOf(centre) }
    }

    /** [hex] when it names a cell at [RESOLUTION], otherwise null. The only value a pick emits. */
    fun pickable(hex: String?): String? = hex?.takeIf { H3Cell.isCellHex(it, RESOLUTION) }

    /** The cell a tapped feature names, through [pickable]. Every other property is ignored. */
    fun tapped(properties: JsonObject?): String? = pickable(properties?.get("h3")?.jsonPrimitive?.contentOrNull)

    /** Where the camera goes to show [hex], its centre. Null for no cell or no H3. */
    fun centre(h3: H3Grid, hex: String?): LatLng? {
        val cell = hex?.let { H3Cell.parseHex(it) } ?: return null
        if (!h3.available) return null
        return runCatching { h3.cellToLatLng(cell) }.getOrNull()
    }
}

/**
 * A request to show [cell] on the map, such as the cell of a phone fix. It only moves the camera
 * and never chooses the cell. A new [serial] moves it again to the same cell.
 */
@Immutable
data class CellFocus(val cell: String, val serial: Int)

/**
 * The map that chooses a sensor's area (docs/device-location.md). It draws the resolution 8 cells
 * around the camera, and a tap on one calls [onSelect] with its hex id, nothing else. [selected] is
 * filled in the brand blue. [focus] moves the camera to a cell without choosing it.
 *
 * The camera centre is turned into a cell as soon as it rests and is never kept, so no coordinate
 * reaches UI state, a log or storage. With [networkAllowed] off, or when the basemap cannot load,
 * the map is a plain paper grid. [controls] draws above the zoom buttons, such as a phone location
 * button. [enabled] false ignores taps.
 */
@Composable
fun CellPicker(
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    focus: CellFocus? = null,
    enabled: Boolean = true,
    networkAllowed: Boolean = LocalMapNetwork.current,
    controls: @Composable ColumnScope.() -> Unit = {},
) {
    val h3 = remember { platformH3Grid() }
    val style = rememberMapStyle(networkAllowed)
    Box(modifier.fillMaxSize()) {
        if (!h3.available) {
            FloatingSurface(Modifier.align(Alignment.Center).padding(FloatingControl.Margin)) {
                Text(stringResource(Res.string.map_picker_unavailable), Modifier.padding(16.dp))
            }
            return@Box
        }
        if (style == null) return@Box
        PickerMap(h3, style, selected, onSelect, focus, enabled, networkAllowed, controls)
    }
}

@Composable
private fun PickerMap(
    h3: H3Grid,
    style: String,
    selected: String?,
    onSelect: (String) -> Unit,
    focus: CellFocus?,
    enabled: Boolean,
    networkAllowed: Boolean,
    controls: @Composable ColumnScope.() -> Unit,
) {
    val pick by rememberUpdatedState<(String?) -> Unit> { hex -> if (enabled) CellPickerGrid.pickable(hex)?.let(onSelect) }
    // The cells in view and the one under the centre. Cells only, never the camera position.
    var cells by remember { mutableStateOf<List<Long>>(emptyList()) }
    var centre by remember { mutableStateOf<Long?>(null) }
    var zoomedIn by remember { mutableStateOf(false) }
    // The picker opens on the chosen cell, or fits the phone's time zone region.
    val start = remember { CellPickerGrid.centre(h3, selected) }
    val region = remember { if (start == null) TimeZoneRegions.regionFor(TimeZone.currentSystemDefault().id) else null }
    val ink = MaterialTheme.colorScheme.onSurface
    val chosenCell = remember(selected) { CellPickerGrid.pickable(selected)?.let(H3Index::fromHex) }
    val gridJson = remember(cells, chosenCell) { CellOutlines.geoJson(h3, cells.filterNot { it == chosenCell }) }
    val selectedJson = remember(chosenCell) { CellOutlines.geoJson(h3, listOfNotNull(chosenCell), chosenCell) }
    val mapState = rememberBaseMapState(
        style = style,
        center = start ?: region?.let { LatLng((it.south + it.north) / 2, (it.west + it.east) / 2) } ?: LatLng(0.0, 0.0),
        zoom = if (start != null) CellPickerGrid.PICK_ZOOM else 3.0,
    ) {
        val grid = rememberGeoJsonSource(GeoJsonData.JsonString(gridJson))
        // Transparent, so the whole cell takes the tap, not only its outline.
        FillLayer(
            id = "picker-cells-hit",
            source = grid,
            color = const(Color.Transparent),
            onClick = { features ->
                pick(CellPickerGrid.tapped(features.firstOrNull()?.properties))
                ClickResult.Consume
            },
        )
        LineLayer(id = "picker-cells-outline", source = grid, color = const(ink), opacity = const(0.55f), width = const(1.dp))
        val chosen = rememberGeoJsonSource(GeoJsonData.JsonString(selectedJson))
        FillLayer(id = "picker-selected-fill", source = chosen, color = const(FloatingControl.Selected), opacity = const(0.35f))
        LineLayer(id = "picker-selected-outline", source = chosen, color = const(FloatingControl.Selected), width = const(3.dp))
    }
    val reduce = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(focus) {
        val target = focus?.let { CellPickerGrid.centre(h3, it.cell) } ?: return@LaunchedEffect
        mapState.moveCamera(Position(longitude = target.lng, latitude = target.lat), CellPickerGrid.PICK_ZOOM, reduce)
    }
    val label = stringResource(Res.string.map_picker_label)
    val chosenLabel = stringResource(Res.string.map_picker_selected)
    val pickMiddle = stringResource(Res.string.map_pick_middle)
    Box(Modifier.fillMaxSize()) {
        BaseMap(
            state = mapState,
            modifier = Modifier.semantics {
                contentDescription = label
                if (chosenCell != null) stateDescription = chosenLabel
                // Screen readers cannot aim at a cell, so the cell in the middle is one action away.
                val middle = centre
                if (enabled && middle != null) {
                    customActions = listOf(CustomAccessibilityAction(pickMiddle) { pick(H3Index.toHex(middle)); true })
                }
            },
            fitOnce = region,
            onCameraRest = { rest ->
                zoomedIn = rest.zoom >= CellPickerGrid.MIN_ZOOM
                centre = CellPickerGrid.centreCell(h3, rest.zoom, rest.lat, rest.lng)
                cells = CellPickerGrid.cellsAround(h3, rest)
            },
        )
        PlaceSearchField(
            onPlace = { place -> scope.launch { mapState.moveCamera(Position(longitude = place.lng, latitude = place.lat), PLACE_ZOOM, reduce) } },
            modifier = Modifier.align(Alignment.TopStart).padding(FloatingControl.Margin),
        )
        if (!zoomedIn) {
            FloatingSurface(Modifier.align(Alignment.Center).padding(FloatingControl.Margin), shape = FloatingControl.SmallShape) {
                Text(
                    stringResource(Res.string.map_pick_zoom_in),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(FloatingControl.Margin),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(MinTouchSpacing),
        ) {
            Box(Modifier.weight(1f)) { MapCredits(networkAllowed) }
            Column(Modifier.width(FloatingControl.Size), verticalArrangement = Arrangement.spacedBy(MinTouchSpacing)) {
                controls()
                ZoomControls(mapState)
            }
        }
    }
}

/** The OpenStreetMap credit while the basemap may load, and the map info with every source. */
@Composable
private fun MapCredits(networkAllowed: Boolean) {
    val info = InfoContent(
        stringResource(Res.string.map_info_title),
        listOfNotNull(
            if (networkAllowed) stringResource(Res.string.map_info_osm) else null,
            if (networkAllowed) stringResource(Res.string.map_info_protomaps) else null,
            stringResource(Res.string.map_info_geonames),
            stringResource(Res.string.map_info_grid),
        ),
    )
    FloatingSurface(shape = FloatingControl.SmallShape) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = if (networkAllowed) 10.dp else 0.dp)) {
            if (networkAllowed) Text(stringResource(Res.string.map_osm), style = MaterialTheme.typography.labelSmall)
            InfoButton(info)
        }
    }
}

/**
 * The phone location button for [CellPicker]'s controls. While [busy] a small spinner takes the
 * icon's place and [contentDescription] should name the stop action.
 */
@Composable
fun PickerLocateButton(onClick: () -> Unit, contentDescription: String, busy: Boolean = false, modifier: Modifier = Modifier) {
    FloatingIconButton(onClick = onClick, contentDescription = contentDescription, modifier = modifier) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(MapIcons.MyLocation, contentDescription = null)
    }
}

// A town fills the screen and the grid shows.
private const val PLACE_ZOOM = 13.0
