package space.opensensor.ui.components

import android.content.Intent
import android.content.res.Resources
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import space.opensensor.app.R
import space.opensensor.data.ReconnectState
import space.opensensor.data.ble.BleConnectionFailure
import space.opensensor.data.ble.ConnectionState
import space.opensensor.designsystem.TickingText
import space.opensensor.today.TodayReconnect
import space.opensensor.units.ByteUnit
import space.opensensor.units.byteSize

/** Shown where a value is missing or not read yet. Never a long dash (copy rules). */
const val NO_VALUE = "n/a"

/** A byte count as [UiText], `1.5 KB` or `1.5 كيلوبايت`, through the shared [byteSize] that Files uses too. */
fun bytesText(bytes: Long): UiText {
    val size = byteSize(bytes)
    val id = when (size.unit) {
        ByteUnit.B -> R.string.unit_bytes
        ByteUnit.KB -> R.string.unit_kilobytes
        ByteUnit.MB -> R.string.unit_megabytes
        ByteUnit.GB -> R.string.unit_gigabytes
    }
    return UiText.Res(id, listOf(size.number))
}

/** [bytesText] resolved, for notifications built outside composition. */
fun formatBytes(bytes: Long, res: Resources): String = bytesText(bytes).resolve(res)

fun formatAgo(ageMs: Long, res: Resources): String {
    val s = ageMs / 1000
    return when {
        ageMs < 0 -> res.getString(R.string.ago_just_now)
        s < 60 -> res.getString(R.string.ago_seconds, s)
        s < 3600 -> res.getString(R.string.ago_minutes, s / 60)
        s < 86_400 -> res.getString(R.string.ago_hours, s / 3600)
        else -> res.getString(R.string.ago_days, s / 86_400)
    }
}

/** [formatAgo] bound to the current resources, for leaf `TickingText` lambdas. */
@Composable
fun agoFormatter(): (Long) -> String {
    val res = LocalResources.current
    return remember(res) { { ageMs -> formatAgo(ageMs, res) } }
}

/**
 * Collect one-off UI events only while the screen is at least STARTED: a snackbar or a system dialog
 * is never fired at a stopped activity. Events are buffered by the producer's `extraBufferCapacity`.
 */
@Composable
fun <T> CollectEvents(events: Flow<T>, onEvent: suspend (T) -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onEvent)
    LaunchedEffect(events, owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            withContext(Dispatchers.Main.immediate) { events.collect { latest(it) } }
        }
    }
}

/** The data layer's reconnect attempt as the shared thing banner reads it. */
fun ReconnectState.toShared(): TodayReconnect = TodayReconnect(attempt, maxAttempts, reason)

/** Opens Android's Bluetooth settings, for the shared banner's pairing repair link. */
@Composable
fun rememberOpenBluetoothSettings(): () -> Unit {
    val context = LocalContext.current
    return remember(context) { { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

/**
 * The shared thing banner for an Android link state. The pairing repair decision is Android's,
 * from its own failure codes.
 */
@Composable
fun ConnectionBanner(
    state: ConnectionState,
    modifier: Modifier = Modifier,
    reconnecting: ReconnectState? = null,
    technical: Boolean = false,
    heldInBackground: Boolean = false,
) = space.opensensor.today.ConnectionBanner(
    state = state,
    reconnecting = reconnecting?.toShared(),
    technical = technical,
    pairingRepair = BleConnectionFailure.needsPairingRepair(state),
    onOpenBluetoothSettings = rememberOpenBluetoothSettings(),
    modifier = modifier,
    heldInBackground = heldInBackground,
)
