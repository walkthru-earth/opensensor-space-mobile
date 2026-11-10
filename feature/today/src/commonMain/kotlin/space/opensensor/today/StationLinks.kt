package space.opensensor.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import space.opensensor.designsystem.AppIcons
import space.opensensor.designsystem.BrandCard
import space.opensensor.designsystem.CardHeader
import space.opensensor.designsystem.CardTone
import space.opensensor.designsystem.FitText
import space.opensensor.designsystem.InfoContent
import space.opensensor.designsystem.MinTouchTarget
import space.opensensor.settings.DetailLevel
import space.opensensor.today.resources.Res
import space.opensensor.today.resources.nav_debug
import space.opensensor.today.resources.station_files
import space.opensensor.today.resources.station_history
import space.opensensor.today.resources.station_more
import space.opensensor.today.resources.station_more_info

/** One row of the Station page's More card, the page it opens and the level that shows it. */
internal enum class StationLink(val label: StringResource, val action: TodayAction) {
    HISTORY(Res.string.station_history, TodayAction.OpenHistory),
    FILES(Res.string.station_files, TodayAction.OpenFiles),
    DEBUG(Res.string.nav_debug, TodayAction.OpenDebug),
    ;

    val icon: ImageVector
        get() = when (this) {
            HISTORY -> AppIcons.Schedule
            FILES -> AppIcons.Storage
            DEBUG -> AppIcons.BugReport
        }
}

/**
 * The rows the Station page offers at [level]. History and Files read the phone archive, so they
 * show at every level and open without a link. Debug shows at Developer only.
 */
internal fun stationLinks(level: DetailLevel): List<StationLink> = buildList {
    add(StationLink.HISTORY)
    add(StationLink.FILES)
    if (level.developer) add(StationLink.DEBUG)
}

/**
 * The More card at the end of the Station page. Each row is a plain push, so Back returns here.
 * Device settings sit behind the gear in the top bar. Neutral ink, since brand blue marks only a
 * selection, and every label fits one line.
 */
@Composable
internal fun StationLinksCard(level: DetailLevel, onAction: (TodayAction) -> Unit, modifier: Modifier = Modifier) {
    val title = stringResource(Res.string.station_more)
    BrandCard(modifier = modifier, tone = CardTone.PLAIN, contentPadding = PaddingValues(start = 22.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
        CardHeader(title, info = InfoContent(title, stringResource(Res.string.station_more_info)))
        Column {
            stationLinks(level).forEachIndexed { index, link ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                StationLinkRow(link, onClick = { onAction(link.action) })
            }
        }
    }
}

@Composable
private fun StationLinkRow(link: StationLink, onClick: () -> Unit) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(link.icon, contentDescription = null, tint = quiet)
        FitText(stringResource(link.label), style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f))
        Icon(AppIcons.ChevronRight, contentDescription = null, tint = quiet)
    }
}
