package earth.walkthru.opensensor.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One destination in [BrandBottomBar]: the caller's [key], its label and its icon. */
@Immutable
class BottomBarTab<T>(val key: T, val label: String, val icon: ImageVector)

/**
 * The app's one bottom bar, the same on Android and iOS. A neutral brand surface with a hairline on
 * top. Only the selected tab is tinted, Signal blue behind its icon in a curved card (the floating
 * control rule), never a pill. The bar pads itself for the system navigation area.
 */
@Composable
fun <T> BrandBottomBar(
    tabs: List<BottomBarTab<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = FloatingControl.Margin, vertical = 6.dp)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(FloatingControl.MinSpacing),
            ) {
                tabs.forEach { tab ->
                    BottomBarItem(tab, selected = tab.key == selected, onClick = { onSelect(tab.key) }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun BottomBarItem(tab: BottomBarTab<*>, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .touchTarget(minHeight = FloatingControl.Size)
            .clip(FloatingControl.Shape)
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(width = 56.dp, height = 32.dp)
                .clip(FloatingControl.SmallShape)
                .background(if (selected) FloatingControl.Selected else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(tab.icon, contentDescription = null, tint = if (selected) FloatingControl.OnSelected else colors.onSurfaceVariant)
        }
        Text(
            tab.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.onSurface else colors.onSurfaceVariant,
            // Two lines at large font scales, never clipped mid-word.
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
