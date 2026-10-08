package earth.walkthru.aqsync.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.data.history.AirMood
import earth.walkthru.aqsync.ui.theme.color
import earth.walkthru.aqsync.ui.theme.onColor
import earth.walkthru.opensensor.designsystem.FloatingControl

/*
 * The brand components (cards, buttons, mascot, info sheet, rows, signal bars, the bottom bar) live
 * in `:ui:designsystem` for both apps. This package keeps only what still needs app types or
 * `R.string`, like this pill, until the health and presentation modules exist.
 */

/** The mood name on its own WHO step colour, with ink chosen for contrast (`AirMood.onColor`). A small curved card. */
@Composable
fun MoodPill(mood: AirMood, modifier: Modifier = Modifier) {
    Surface(shape = FloatingControl.SmallShape, color = mood.color(), contentColor = mood.onColor(), modifier = modifier) {
        Text(
            mood.titleText(),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}
