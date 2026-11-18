package space.opensensor.device

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import space.opensensor.designsystem.BrandCard
import space.opensensor.designsystem.CardHeader
import space.opensensor.designsystem.InfoContent
import space.opensensor.designsystem.SwitchRow
import space.opensensor.device.resources.Res
import space.opensensor.device.resources.autosync_off
import space.opensensor.device.resources.autosync_on
import space.opensensor.device.resources.autosync_on_system
import space.opensensor.device.resources.autosync_title

/** How often Auto-sync runs. Android names its period, iOS picks the moment itself. */
sealed interface AutoSyncCadence {
    data class Every(val minutes: Int) : AutoSyncCadence

    data object SystemPicks : AutoSyncCadence
}

/**
 * The Auto-sync card of a known sensor's Device page, the same on both apps (#303). The switch
 * row reads its state and cadence as one Role.Switch node. [info] explains the card, and
 * [content] adds a platform's own rows under the switch, Android's bond, token and wake when
 * nearby. A permission the switch needs is asked by the host's [onToggle], never here.
 */
@Composable
fun AutoSyncCard(
    on: Boolean,
    cadence: AutoSyncCadence,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    info: List<String> = emptyList(),
    content: @Composable ColumnScope.() -> Unit = {},
) {
    BrandCard(modifier = modifier) {
        val title = stringResource(Res.string.autosync_title)
        CardHeader(title, info = if (info.isEmpty()) null else InfoContent(title, info))
        SwitchRow(autoSyncLabel(on, cadence), checked = on, onCheckedChange = onToggle, enabled = enabled)
        content()
    }
}

@Composable
private fun autoSyncLabel(on: Boolean, cadence: AutoSyncCadence): String = when {
    !on -> stringResource(Res.string.autosync_off)
    cadence is AutoSyncCadence.Every -> pluralStringResource(Res.plurals.autosync_on, cadence.minutes, cadence.minutes)
    else -> stringResource(Res.string.autosync_on_system)
}
