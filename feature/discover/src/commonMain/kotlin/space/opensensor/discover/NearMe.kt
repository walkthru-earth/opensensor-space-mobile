package space.opensensor.discover

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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.location.LocationAccuracy
import org.maplibre.compose.location.LocationRequest
import org.maplibre.compose.location.LocationState
import org.maplibre.compose.location.rememberDefaultLocationProvider
import org.maplibre.compose.location.rememberLocationState
import org.maplibre.compose.map.MapState
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.extensions.meters
import space.opensensor.designsystem.FloatingIconButton
import space.opensensor.permissions.AccessDialogTexts
import space.opensensor.permissions.AccessDialogs
import space.opensensor.permissions.RuntimeAccess
import space.opensensor.publicfeed.H3Grid
import space.opensensor.publicfeed.LatLng
import kotlin.time.Duration.Companion.seconds

/**
 * Approximate foreground location, asked only from the "Near me" tap (docs/devices-and-discovery.md,
 * "Centring the map and Near me"), through the shared permission policy (`:ui:permissions`).
 * Android asks for ACCESS_COARSE_LOCATION alone, iOS for When In Use with reduced accuracy as the
 * default (Info.plist). After a denial the system no longer asks, so the tap offers settings.
 */
@Composable
internal expect fun rememberApproximateLocationAccess(): RuntimeAccess

/**
 * "Near me" for one map session. [location] stays null until the user taps and grants, so no
 * location provider exists before that. The fix only draws the puck. The camera moves to the centre
 * of the fix's coarse cell ([nearMeTarget]), so the saved camera never holds the fix. The fix is
 * never stored or sent.
 */
internal class NearMe(val location: LocationState?, val recenter: Int, val access: RuntimeAccess, val onTap: () -> Unit)

// Low maps to Core Location's kilometre accuracy and Android's low-power fused request, which is
// enough for a resolution 7 cell and works with a coarse grant.
private val NEAR_ME_REQUEST = LocationRequest {
    accuracy = LocationAccuracy.Low
    minimumInterval = 30.seconds
    minimumDistance = 200.meters
}

@Composable
internal fun rememberNearMe(): NearMe {
    val access = rememberApproximateLocationAccess()
    var active by remember { mutableStateOf(false) }
    var recenter by remember { mutableIntStateOf(0) }
    val location = if (active) rememberLocationState(provider = rememberDefaultLocationProvider(), request = NEAR_ME_REQUEST) else null
    return NearMe(location, recenter, access) {
        if (active) {
            recenter++
        } else {
            // A fresh "no" leaves the map as it is. A later tap offers settings once the system
            // stops asking (PermissionFlow).
            access.run {
                active = true
                recenter++
            }
        }
    }
}

/**
 * Where "Near me" points the camera. The centre of the resolution [NEAR_ME_RES] cell holding the
 * fix, never the fix itself, because the resting camera is saved in `DiscoverPrefs`. Null when the
 * platform has no H3 binding or the fix has no cell, and the map then stays where it is.
 */
internal fun nearMeTarget(h3: H3Grid, lat: Double, lng: Double): LatLng? {
    if (!h3.available) return null
    val cell = runCatching { h3.latLngToCell(lat, lng, NEAR_ME_RES) }.getOrNull()?.takeIf { it != 0L } ?: return null
    return runCatching { h3.cellToLatLng(cell) }.getOrNull()
}

/** The map button. After each granted tap, the camera moves to the coarse cell of the next fix. */
@Composable
internal fun NearMeButton(
    nearMe: NearMe,
    mapState: MapState,
    target: (lat: Double, lng: Double) -> LatLng?,
    label: String,
    accessTexts: AccessDialogTexts,
    modifier: Modifier = Modifier,
) {
    AccessDialogs(nearMe.access, accessTexts)
    val location = nearMe.location
    LaunchedEffect(nearMe.recenter, location) {
        if (nearMe.recenter == 0 || location == null) return@LaunchedEffect
        val fix = snapshotFlow { location.lastLocation }.filterNotNull().first()
        val centre = target(fix.position.latitude, fix.position.longitude) ?: return@LaunchedEffect
        catchingNonCancel { mapState.animateCamera(CameraUpdate(center = Position(longitude = centre.lng, latitude = centre.lat), zoom = NEAR_ME_ZOOM)) }
    }
    FloatingIconButton(onClick = nearMe.onTap, contentDescription = label, modifier = modifier) {
        Icon(DiscoverIcons.MyLocation, contentDescription = null)
    }
}

// About a resolution 7 cell (edge about 1.4 km) across a few hundred pixels.
private const val NEAR_ME_ZOOM = 10.5

// Edge about 3.2 km. Coarse enough that the saved camera does not locate the user, fine enough
// that the puck stays in view at NEAR_ME_ZOOM.
internal const val NEAR_ME_RES = 6
