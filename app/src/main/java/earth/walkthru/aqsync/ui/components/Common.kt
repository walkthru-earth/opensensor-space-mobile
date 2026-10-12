package earth.walkthru.aqsync.ui.components

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
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.ReconnectState
import earth.walkthru.aqsync.data.ble.BleConnectionFailure
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.opensensor.designsystem.TickingText
import earth.walkthru.opensensor.today.TodayReconnect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.Locale

/** Shown where a value is missing or not read yet. Never a long dash (copy rules). */
const val NO_VALUE = "n/a"

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f MiB", bytes / 1048576.0)
    else -> String.format(Locale.US, "%.2f GiB", bytes / 1073741824.0)
}

fun formatRate(bytesPerSecond: Double): String = when {
    bytesPerSecond < 1024 -> String.format(Locale.US, "%.0f B/s", bytesPerSecond)
    else -> String.format(Locale.US, "%.1f KiB/s", bytesPerSecond / 1024.0)
}

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

/** The data layer's reconnect attempt as the shared station banner reads it. */
fun ReconnectState.toShared(): TodayReconnect = TodayReconnect(attempt, maxAttempts, reason)

/** Opens Android's Bluetooth settings, for the shared banner's pairing repair link. */
@Composable
fun rememberOpenBluetoothSettings(): () -> Unit {
    val context = LocalContext.current
    return remember(context) { { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

/**
 * The shared station banner for an Android link state. The pairing repair decision is Android's,
 * from its own failure codes.
 */
@Composable
fun ConnectionBanner(
    state: ConnectionState,
    modifier: Modifier = Modifier,
    reconnecting: ReconnectState? = null,
    technical: Boolean = false,
    heldInBackground: Boolean = false,
) = earth.walkthru.opensensor.today.ConnectionBanner(
    state = state,
    reconnecting = reconnecting?.toShared(),
    technical = technical,
    pairingRepair = BleConnectionFailure.needsPairingRepair(state),
    onOpenBluetoothSettings = rememberOpenBluetoothSettings(),
    modifier = modifier,
    heldInBackground = heldInBackground,
)
