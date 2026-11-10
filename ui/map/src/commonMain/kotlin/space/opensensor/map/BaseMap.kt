package space.opensensor.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.map.CameraConstraints
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.BoundingBox
import org.maplibre.spatialk.geojson.Position
import space.opensensor.publicfeed.GeoBounds
import space.opensensor.publicfeed.LatLng

/** Where the camera came to rest. [bounds] is the visible area, [lat] and [lng] its centre. */
data class CameraRest(val bounds: GeoBounds, val zoom: Double, val lat: Double, val lng: Double)

/**
 * The caller's sources and layers, drawn above the style. One line, because ktlint's annotation and
 * function type spacing rules disagree on how to wrap two annotations on a function type.
 */
@Suppress("ktlint:standard:annotation")
typealias MapContent = @Composable @MaplibreComposable () -> Unit

/**
 * The state of one map. It opens on [style] (a style JSON, [MapStyles] or [rememberMapStyle]) at
 * [center] and [zoom]. [content] is the caller's own sources and layers (maplibre-compose
 * `rememberGeoJsonSource`, `FillLayer`, `LineLayer` and the like, plus [NearMeLayer] last). It draws
 * above the style and recomposes like any composable, so a changed GeoJSON string updates its source.
 */
@Composable
fun rememberBaseMapState(
    style: String,
    center: LatLng,
    zoom: Double,
    content: MapContent = {},
): MapState = rememberMapState(
    baseStyle = BaseStyle.Json(style),
    initialCameraPosition = CameraPosition(center = Position(longitude = center.lng, latitude = center.lat), zoom = zoom),
) { content() }

/**
 * The map every screen shows. Zoom stays inside [MapZoom], with no rotation and no tilt, so pinch and
 * [ZoomControls] agree. [fitOnce] is an area the camera fits the first time the map is ready, never
 * again after a rotation, so a rebuilt map keeps the person's view. [onCameraRest] runs each time
 * the camera settles, [onMoved] on every rest after the first. [onMapClick] runs for a tap that no
 * layer of the caller consumed. Attribution is the caller's, drawn over the map. With [movable]
 * false the camera stays where the caller put it, so a map inside a scrolling list leaves drags to
 * the list. Such a map has no view of the person's to keep, so it fits [fitOnce] again each time
 * the area changes ([MapFit.due]).
 */
@Composable
fun BaseMap(
    state: MapState,
    modifier: Modifier = Modifier,
    viewportInsets: PaddingValues = PaddingValues(),
    fitOnce: GeoBounds? = null,
    onCameraRest: (CameraRest) -> Unit = {},
    onMoved: () -> Unit = {},
    onMapClick: () -> Unit = {},
    movable: Boolean = true,
) {
    val onRest by rememberUpdatedState(onCameraRest)
    val onMove by rememberUpdatedState(onMoved)
    // A map the person moves fits once, kept across a rotation, so a rebuilt map never refits over
    // their view. A still map fits each new area, so cells that arrive later stay in sight.
    var fitted by rememberSaveable { mutableStateOf(false) }
    var fittedTo by remember { mutableStateOf<GeoBounds?>(null) }
    LaunchedEffect(state, fitOnce, movable) {
        val b = fitOnce
        if (b != null && MapFit.due(b, movable, fitted, fittedTo)) {
            // A fit cut short by an unready map runs again next time. Cancellation propagates.
            if (catchingNonCancel { state.fitCameraToBounds(BoundingBox(west = b.west, south = b.south, east = b.east, north = b.north)) }.isSuccess) {
                fitted = true
                fittedTo = b
            }
        }
    }
    LaunchedEffect(state) {
        var first = true
        snapshotFlow { state.viewport }.filterNotNull().collectLatest { vp ->
            val bounds = vp.visibleBounds
            val center = vp.cameraPosition.center
            onRest(CameraRest(GeoBounds(bounds.west, bounds.south, bounds.east, bounds.north), vp.cameraPosition.zoom, center.latitude, center.longitude))
            if (!first) onMove()
            first = false
        }
    }
    MaplibreMap(
        modifier = modifier,
        state = state,
        viewportInsets = viewportInsets,
        cameraConstraints = CameraConstraints {
            minZoom = MapZoom.MIN
            maxZoom = MapZoom.MAX
        },
        interactions = MapInteractions {
            camera {
                rotate { enabled = false }
                pitch { enabled = false }
                if (!movable) {
                    pan { enabled = false }
                    zoom { enabled = false }
                }
            }
            callbacks {
                click {
                    onUnhandled {
                        onMapClick()
                        ClickResult.Consume
                    }
                }
            }
        },
        overlay = {},
    )
}

/** When [BaseMap] fits its area. */
object MapFit {
    /**
     * True when the camera should fit [target]. A [movable] map fits once, until [fitted]. A still
     * map fits whenever [target] differs from the area it last fitted, [fittedTo].
     */
    fun due(target: GeoBounds, movable: Boolean, fitted: Boolean, fittedTo: GeoBounds?): Boolean =
        if (movable) !fitted else target != fittedTo
}
