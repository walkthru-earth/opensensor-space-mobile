package space.opensensor.files

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.datetime.TimeZone

/**
 * The system time zone, read again each time the screen resumes (#315). The zone changes in the
 * system settings, which pauses the app, so the file times follow it on return. [read] is the
 * system zone, a test passes its own.
 */
@Composable
internal fun rememberSystemTimeZone(read: () -> TimeZone = TimeZone::currentSystemDefault): TimeZone {
    val latestRead by rememberUpdatedState(read)
    var zone by remember { mutableStateOf(read()) }
    LifecycleResumeEffect(Unit) {
        zone = latestRead()
        onPauseOrDispose {}
    }
    return zone
}
