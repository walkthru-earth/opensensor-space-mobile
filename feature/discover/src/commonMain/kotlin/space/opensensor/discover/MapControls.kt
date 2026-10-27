package space.opensensor.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.map.MapState
import org.maplibre.spatialk.geojson.Position
import space.opensensor.designsystem.FloatingControl
import space.opensensor.designsystem.FloatingSurface
import space.opensensor.designsystem.LocalReduceMotion

// Basemap tiles run from zoom 0 to about 16. Past that the H3 cells are all the map shows. The map
// holds pinch to the same range (DiscoverMap), so pinch and the buttons agree.
internal const val MIN_ZOOM = 1.0
internal const val MAX_ZOOM = 16.0

/**
 * The zoom a button press lands on, one step from [zoom] toward the basemap's range and never past
 * it. A camera already outside the range, from an older saved view, only moves the way pressed.
 */
internal fun steppedZoom(zoom: Double, step: Int): Double = (zoom + step).coerceIn(minOf(zoom, MIN_ZOOM), maxOf(zoom, MAX_ZOOM))

/**
 * The zoom each button press aims at. A press while the camera still eases toward an earlier press
 * steps from that target, not from the zoom the camera passes through, so quick taps add up. Once
 * the last move ends the next press starts from the camera again.
 */
internal class ZoomSteps {
    private var pending: Double? = null

    fun next(current: Double, step: Int): Double = steppedZoom(pending ?: current, step).also { pending = it }

    /** The move toward [target] ended. A newer press keeps its own target. */
    fun settled(target: Double) {
        if (pending == target) pending = null
    }
}

/**
 * Moves the camera to [center] (kept when null) at [zoom] (kept when null). With reduce motion on it
 * snaps there, otherwise it eases. A map that is not ready yet keeps its view.
 */
internal suspend fun MapState.moveCamera(center: Position? = null, zoom: Double? = null, reduceMotion: Boolean) {
    catchingNonCancel {
        if (reduceMotion) {
            val now = cameraPosition
            setCameraPosition(now.copy(center = center ?: now.center, zoom = zoom ?: now.zoom))
        } else {
            animateCamera(CameraUpdate(center = center, zoom = zoom))
        }
    }
}

/**
 * Zoom in and zoom out, one compact floating pair, so the map never needs a pinch. Each half is a
 * full touch target with its own spoken name.
 */
@Composable
internal fun ZoomControls(mapState: MapState, zoomIn: String, zoomOut: String, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val reduce = LocalReduceMotion.current
    val steps = remember { ZoomSteps() }
    fun zoomBy(step: Int) {
        val target = steps.next(mapState.cameraPosition.zoom, step)
        scope.launch {
            try {
                mapState.moveCamera(zoom = target, reduceMotion = reduce)
            } finally {
                steps.settled(target)
            }
        }
    }
    ZoomPair(zoomIn, zoomOut, onZoomIn = { zoomBy(1) }, onZoomOut = { zoomBy(-1) }, modifier = modifier)
}

/** The two buttons alone, apart from any map, so they render in tests. */
@Composable
internal fun ZoomPair(zoomIn: String, zoomOut: String, onZoomIn: () -> Unit, onZoomOut: () -> Unit, modifier: Modifier = Modifier) {
    FloatingSurface(modifier.semantics { isTraversalGroup = true }) {
        Column(Modifier.width(FloatingControl.Size)) {
            ZoomHalf(zoomIn, DiscoverIcons.Add, onZoomIn)
            HorizontalDivider(Modifier.padding(horizontal = FloatingControl.Inset * 2), color = MaterialTheme.colorScheme.outlineVariant)
            ZoomHalf(zoomOut, DiscoverIcons.Remove, onZoomOut)
        }
    }
}

@Composable
private fun ZoomHalf(label: String, icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier.size(FloatingControl.Size)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null)
    }
}
