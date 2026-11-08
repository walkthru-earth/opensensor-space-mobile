package space.opensensor.map

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource
import org.maplibre.compose.layers.LocationIndicatorLayer
import org.maplibre.compose.location.LocationAccuracy
import org.maplibre.compose.location.LocationRequest
import org.maplibre.compose.location.LocationState
import org.maplibre.compose.location.rememberDefaultLocationProvider
import org.maplibre.compose.location.rememberLocationState
import org.maplibre.compose.map.MapState
import org.maplibre.compose.util.MaplibreComposable
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.extensions.meters
import space.opensensor.designsystem.FloatingControl
import space.opensensor.designsystem.FloatingIconButton
import space.opensensor.designsystem.FloatingSurface
import space.opensensor.designsystem.LocalReduceMotion
import space.opensensor.designsystem.MinTouchSpacing
import space.opensensor.map.resources.Res
import space.opensensor.map.resources.map_access_cancel
import space.opensensor.map.resources.map_access_continue
import space.opensensor.map.resources.map_access_not_now
import space.opensensor.map.resources.map_access_open_settings
import space.opensensor.map.resources.map_near_me
import space.opensensor.map.resources.map_near_me_access_body
import space.opensensor.map.resources.map_near_me_access_title
import space.opensensor.map.resources.map_near_me_none
import space.opensensor.map.resources.map_near_me_settings_body
import space.opensensor.map.resources.map_near_me_settings_title
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
 * "Near me" for one map session, from [rememberNearMe]. Its location stays null until the user taps
 * and grants, so no location provider exists before that. The fix only draws the puck
 * ([NearMeLayer]). The camera moves to the centre of a coarse cell of the fix ([NearMeButton]), so a
 * saved camera never holds the fix. The fix is never stored, logged or sent.
 */
class NearMe internal constructor(
    internal val location: LocationState?,
    internal val recenter: Int,
    internal val access: RuntimeAccess,
    internal val onTap: () -> Unit,
)

// Low maps to Core Location's kilometre accuracy and Android's low-power fused request, which is
// enough for the resolution 6 cell of NEAR_ME_RES and works with a coarse grant.
private val NEAR_ME_REQUEST = LocationRequest {
    accuracy = LocationAccuracy.Low
    minimumInterval = 30.seconds
    minimumDistance = 200.meters
}

/**
 * One [NearMe] for the map on screen. Nothing asks for location until the first tap of
 * [NearMeButton]. Each later tap recentres on the next fix.
 */
@Composable
fun rememberNearMe(): NearMe {
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
 * The library's standard blue puck and accuracy circle for [nearMe], nothing before the first
 * granted tap. Call it last in the map's content, so it draws above the caller's layers.
 */
@Composable
@MaplibreComposable
fun NearMeLayer(nearMe: NearMe) {
    val location = nearMe.location ?: return
    LocationIndicatorLayer(id = "map-near-me", locationState = location)
}

/**
 * Where "Near me" points the camera. The centre of the resolution [NEAR_ME_RES] cell holding the
 * fix, never the fix itself, because a resting camera may be saved (Discover keeps it in
 * `DiscoverPrefs`). Null when the platform has no H3 binding or the fix has no cell, and the map
 * then stays where it is.
 */
fun nearMeTarget(h3: H3Grid, lat: Double, lng: Double): LatLng? {
    if (!h3.available) return null
    val cell = runCatching { h3.latLngToCell(lat, lng, NEAR_ME_RES) }.getOrNull()?.takeIf { it != 0L } ?: return null
    return runCatching { h3.cellToLatLng(cell) }.getOrNull()
}

/**
 * The map button. After each granted tap, the camera moves to [target] of the next fix, usually
 * [nearMeTarget]. A fix that does not come within [NEAR_ME_WAIT] leaves the map where it is and
 * the miss is announced. The button shows the location dialogs of the shared permission policy.
 */
@Composable
fun NearMeButton(
    nearMe: NearMe,
    mapState: MapState,
    target: (lat: Double, lng: Double) -> LatLng?,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(Res.string.map_near_me)
    val none = stringResource(Res.string.map_near_me_none)
    AccessDialogs(nearMe.access, nearMeAccessTexts())
    val location = nearMe.location
    val reduce = LocalReduceMotion.current
    var missed by remember { mutableStateOf(false) }
    LaunchedEffect(nearMe.recenter, location) {
        missed = false
        if (nearMe.recenter == 0 || location == null) return@LaunchedEffect
        val fix = withTimeoutOrNull(NEAR_ME_WAIT) { snapshotFlow { location.lastLocation }.filterNotNull().first() }
        if (fix == null) {
            missed = true
            return@LaunchedEffect
        }
        val centre = target(fix.position.latitude, fix.position.longitude) ?: return@LaunchedEffect
        mapState.moveCamera(Position(longitude = centre.lng, latitude = centre.lat), NEAR_ME_ZOOM, reduce)
    }
    // The miss is shown beside the button as well as spoken. The note takes no width of its own, so
    // the legend beside the controls never shrinks for it. It goes on the next tap.
    Layout(
        content = {
            FloatingIconButton(
                onClick = nearMe.onTap,
                contentDescription = label,
                modifier = Modifier.semantics {
                    if (missed) stateDescription = none
                    liveRegion = LiveRegionMode.Polite
                },
            ) {
                Icon(MapIcons.MyLocation, contentDescription = null)
            }
            if (missed) {
                FloatingSurface(Modifier.clearAndSetSemantics {}, shape = FloatingControl.SmallShape) {
                    Text(none, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val button = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val note = measurables.getOrNull(1)?.measure(Constraints(maxWidth = NOTE_MAX.roundToPx()))
        layout(button.width, button.height) {
            button.placeRelative(0, 0)
            // To the start of the button, over the map. placeRelative mirrors it in right to left.
            note?.placeRelative(-note.width - MinTouchSpacing.roundToPx(), (button.height - note.height) / 2)
        }
    }
}

/**
 * Near me's approximate location dialogs. The rationale shows only when Android says it is due,
 * the settings dialog only after the system stops asking. iOS never shows the rationale.
 */
@Composable
private fun nearMeAccessTexts() = AccessDialogTexts(
    rationaleTitle = stringResource(Res.string.map_near_me_access_title),
    rationaleBody = stringResource(Res.string.map_near_me_access_body),
    settingsTitle = stringResource(Res.string.map_near_me_settings_title),
    settingsBody = stringResource(Res.string.map_near_me_settings_body),
    continueLabel = stringResource(Res.string.map_access_continue),
    notNow = stringResource(Res.string.map_access_not_now),
    openSettings = stringResource(Res.string.map_access_open_settings),
    cancel = stringResource(Res.string.map_access_cancel),
    icon = MapIcons.MyLocation,
)

private val NOTE_MAX = 240.dp

// Long enough for a cold coarse fix indoors. Past it the map stays and the button says so.
private val NEAR_ME_WAIT = 15.seconds

// About a resolution 7 cell (edge about 1.4 km) across a few hundred pixels.
private const val NEAR_ME_ZOOM = 10.5

// Edge about 3.2 km. Coarse enough that the saved camera does not locate the user, fine enough
// that the puck stays in view at NEAR_ME_ZOOM.
internal const val NEAR_ME_RES = 6
