package space.opensensor.today

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import space.opensensor.designsystem.FloatingControl
import space.opensensor.family.air.AirMood
import space.opensensor.family.air.WhoStep
import space.opensensor.designsystem.color as designSystemColor
import space.opensensor.designsystem.onBand as designSystemOnBand

// Today's WHO colour helpers delegate to :ui:designsystem (ColorContrast.kt). The ARGB values live
// only in :family:air (WhoStep), so both apps paint a step the same colour.

internal fun WhoStep.color(): Color = designSystemColor()

internal fun AirMood.color(): Color = step.color()

/** Ink drawn on a band colour, whichever of dark or light contrasts more. */
internal fun onBand(band: Color): Color = designSystemOnBand(band)

/** The mood name on its own WHO step colour, with ink chosen for contrast. A small curved card. */
@Composable
internal fun MoodPill(mood: AirMood, modifier: Modifier = Modifier) {
    val band = mood.color()
    Surface(shape = FloatingControl.SmallShape, color = band, contentColor = onBand(band), modifier = modifier) {
        Text(
            mood.titleText(),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}
