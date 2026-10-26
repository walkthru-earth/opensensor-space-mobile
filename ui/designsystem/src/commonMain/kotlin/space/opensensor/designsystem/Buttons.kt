package space.opensensor.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The primary action: a Signal blue curved card with ink text and a trailing arrow. One per screen.
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    arrow: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = FloatingControl.Shape,
        colors = FloatingControl.primaryButtonColors(),
        contentPadding = PaddingValues(start = 24.dp, end = if (arrow) 18.dp else 24.dp, top = 14.dp, bottom = 14.dp),
        modifier = modifier.touchTarget(),
    ) {
        FitText(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f, fill = false))
        if (arrow) {
            Spacer(Modifier.width(10.dp))
            Icon(AppIcons.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

/** A quieter outlined card for the secondary action next to a [PillButton], same curve. */
@Composable
fun OutlinePillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = FloatingControl.Shape,
        border = BorderStroke(1.5.dp, if (enabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        modifier = modifier.touchTarget(),
    ) {
        FitText(text, style = MaterialTheme.typography.labelLarge)
    }
}
