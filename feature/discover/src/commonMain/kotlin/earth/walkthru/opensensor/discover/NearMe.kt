package earth.walkthru.opensensor.discover

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import earth.walkthru.opensensor.designsystem.FloatingIconButton
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.location.LocationAccuracy
import org.maplibre.compose.location.LocationRequest
import org.maplibre.compose.location.LocationState
import org.maplibre.compose.location.rememberDefaultLocationProvider
import org.maplibre.compose.location.rememberLocationState
import org.maplibre.compose.map.MapState
import org.maplibre.spatialk.units.extensions.meters
import kotlin.time.Duration.Companion.seconds

/**
 * Approximate foreground location, asked only from the "Near me" tap (docs/devices-and-discovery.md,
 * "Centring the map and Near me"). Android asks for ACCESS_COARSE_LOCATION alone, iOS for When In
 * Use with reduced accuracy as the default (Info.plist).
 */
internal interface ApproximateLocationPermission {
    /** True once approximate (or better) foreground location is granted. */
    val granted: Boolean

    /** Shows the system prompt when the OS still allows one, then reports the outcome. */
    fun request(onResult: (Boolean) -> Unit)
}

@Composable
internal expect fun rememberApproximateLocationPermission(): ApproximateLocationPermission

/**
 * "Near me" for one map session. [location] stays null until the user taps and grants, so no
 * location provider exists before that. The fix only moves the camera and draws the puck. It is
 * never stored or sent.
 */
internal class NearMe(val location: LocationState?, val recenter: Int, val onTap: () -> Unit)

// Low maps to Core Location's kilometre accuracy and Android's low-power fused request, which is
// enough for a resolution 7 cell and works with a coarse grant.
private val NEAR_ME_REQUEST = LocationRequest(accuracy = LocationAccuracy.Low, minimumInterval = 30.seconds, minimumDistance = 200.meters)

@Composable
internal fun rememberNearMe(): NearMe {
    val permission = rememberApproximateLocationPermission()
    var active by remember { mutableStateOf(false) }
    var recenter by remember { mutableIntStateOf(0) }
    val location = if (active) rememberLocationState(provider = rememberDefaultLocationProvider(), request = NEAR_ME_REQUEST) else null
    return NearMe(location, recenter) {
        if (active || permission.granted) {
            active = true
            recenter++
        } else {
            // A denial leaves the map as it is. The next tap asks again where the OS allows it.
            permission.request { granted ->
                if (granted) {
                    active = true
                    recenter++
                }
            }
        }
    }
}

/** The map button. After each granted tap, the camera moves to the next fix at about cell scale. */
@Composable
internal fun NearMeButton(nearMe: NearMe, mapState: MapState, label: String, modifier: Modifier = Modifier) {
    val location = nearMe.location
    LaunchedEffect(nearMe.recenter, location) {
        if (nearMe.recenter == 0 || location == null) return@LaunchedEffect
        val fix = snapshotFlow { location.lastLocation }.filterNotNull().first()
        runCatching { mapState.animateCamera(CameraUpdate(target = fix.position, zoom = NEAR_ME_ZOOM)) }
    }
    FloatingIconButton(onClick = nearMe.onTap, contentDescription = label, modifier = modifier) {
        Icon(DiscoverIcons.MyLocation, contentDescription = null)
    }
}

// About a resolution 7 cell (edge about 1.4 km) across a few hundred pixels.
private const val NEAR_ME_ZOOM = 10.5
