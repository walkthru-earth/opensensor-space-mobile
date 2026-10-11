package earth.walkthru.opensensor.devices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.data.history.AirMood
import earth.walkthru.aqsync.data.history.Metrics
import earth.walkthru.aqsync.settings.DetailLevel
import earth.walkthru.aqsync.units.formatFixed
import earth.walkthru.opensensor.designsystem.AppIcons
import earth.walkthru.opensensor.designsystem.FloatingControl
import earth.walkthru.opensensor.designsystem.InfoButton
import earth.walkthru.opensensor.designsystem.InfoContent
import earth.walkthru.opensensor.designsystem.SignalBars
import earth.walkthru.opensensor.designsystem.StatusDot
import earth.walkthru.opensensor.designsystem.TickingText
import earth.walkthru.opensensor.designsystem.moodWord
import earth.walkthru.opensensor.devices.resources.Res
import earth.walkthru.opensensor.devices.resources.ago_days
import earth.walkthru.opensensor.devices.resources.ago_hours
import earth.walkthru.opensensor.devices.resources.ago_just_now
import earth.walkthru.opensensor.devices.resources.ago_minutes
import earth.walkthru.opensensor.devices.resources.ago_seconds
import earth.walkthru.opensensor.devices.resources.conn_failed
import earth.walkthru.opensensor.devices.resources.conn_link_lost
import earth.walkthru.opensensor.devices.resources.conn_pairing
import earth.walkthru.opensensor.devices.resources.devices_on_wifi
import earth.walkthru.opensensor.devices.resources.info_wifi_only
import earth.walkthru.opensensor.devices.resources.month_1
import earth.walkthru.opensensor.devices.resources.month_10
import earth.walkthru.opensensor.devices.resources.month_11
import earth.walkthru.opensensor.devices.resources.month_12
import earth.walkthru.opensensor.devices.resources.month_2
import earth.walkthru.opensensor.devices.resources.month_3
import earth.walkthru.opensensor.devices.resources.month_4
import earth.walkthru.opensensor.devices.resources.month_5
import earth.walkthru.opensensor.devices.resources.month_6
import earth.walkthru.opensensor.devices.resources.month_7
import earth.walkthru.opensensor.devices.resources.month_8
import earth.walkthru.opensensor.devices.resources.month_9
import earth.walkthru.opensensor.devices.resources.scan_archive_coverage
import earth.walkthru.opensensor.devices.resources.scan_archive_day
import earth.walkthru.opensensor.devices.resources.scan_background
import earth.walkthru.opensensor.devices.resources.scan_collecting
import earth.walkthru.opensensor.devices.resources.scan_files
import earth.walkthru.opensensor.devices.resources.scan_forget
import earth.walkthru.opensensor.devices.resources.scan_no_clock
import earth.walkthru.opensensor.devices.resources.scan_options
import earth.walkthru.opensensor.devices.resources.scan_seen
import earth.walkthru.opensensor.devices.resources.scan_synced
import earth.walkthru.opensensor.devices.resources.scan_tag_known
import earth.walkthru.opensensor.devices.resources.scan_tag_on_lan
import earth.walkthru.opensensor.devices.resources.scan_tag_on_lan_no_token
import earth.walkthru.opensensor.devices.resources.scan_tag_paired
import earth.walkthru.opensensor.devices.resources.scan_unnamed
import earth.walkthru.opensensor.strings.resources.conn_connecting
import earth.walkthru.opensensor.strings.resources.conn_ready
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import earth.walkthru.opensensor.strings.resources.Res as SharedRes

/** A link open now, its phase and, while a sync runs, the file count. */
@Composable
internal fun ActiveRow(link: ActiveLink, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(link.name ?: stringResource(Res.string.scan_unnamed), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        val (phase, color) = when (link.phase) {
            LinkPhase.CONNECTING -> SharedRes.string.conn_connecting to MaterialTheme.colorScheme.onSurfaceVariant
            LinkPhase.PAIRING -> Res.string.conn_pairing to MaterialTheme.colorScheme.tertiary
            LinkPhase.CONNECTED -> SharedRes.string.conn_ready to MaterialTheme.colorScheme.primary
            LinkPhase.RECONNECTING -> Res.string.conn_link_lost to MaterialTheme.colorScheme.onSurfaceVariant
            LinkPhase.FAILED -> Res.string.conn_failed to MaterialTheme.colorScheme.error
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(color)
            Text(stringResource(phase), style = MaterialTheme.typography.bodyMedium)
        }
        if (link.collecting) {
            Text(
                pluralStringResource(Res.plurals.scan_collecting, link.filesTotal, link.filesDone, link.filesTotal),
                style = MaterialTheme.typography.bodySmall,
                // The sync progress line, read out politely as files arrive.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        } else if (link.background) {
            Text(stringResource(Res.string.scan_background), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun NearbyRow(row: DeviceRow, level: DetailLevel, onClick: () -> Unit, onForget: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.name ?: stringResource(Res.string.scan_unnamed), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (level.developer) Mono(row.id)
                    if (row.bonded) Tag(stringResource(Res.string.scan_tag_paired))
                    if (row.known) Tag(stringResource(Res.string.scan_tag_known))
                    row.wifi?.let { Tag(wifiTagText(it)) }
                }
                ArchiveLine(row.archive, level)
            }
            Spacer(Modifier.width(12.dp))
            row.rssi?.let { SignalBars(it, showDbm = level.developer) }
            if (row.known) ForgetMenu(onForget) else Spacer(Modifier.width(12.dp))
        }
    }
}

@Composable
internal fun KnownRow(row: DeviceRow, level: DetailLevel, onClick: () -> Unit, onForget: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.name ?: stringResource(Res.string.scan_unnamed), style = MaterialTheme.typography.titleMedium)
                    row.wifi?.let { Tag(wifiTagText(it)) }
                }
                row.lastSeenMs?.let { seen ->
                    val seenTemplate = stringResource(Res.string.scan_seen)
                    val ago = agoFormatter()
                    TickingText({ now -> DeviceText.fill(seenTemplate, ago(now - seen)) }, style = MaterialTheme.typography.bodySmall)
                }
                if (level.developer && row.archive == null) row.station?.let { Mono(it.take(8)) }
                ArchiveLine(row.archive, level)
            }
            Icon(AppIcons.Link, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            ForgetMenu(onForget)
        }
    }
}

/**
 * A sensor on the Wi-Fi that this phone does not know. It cannot be opened, the token comes over
 * Bluetooth only, so the info icon says to connect it over Bluetooth once.
 */
@Composable
internal fun WifiOnlyRow(sensor: WifiSensor, level: DetailLevel) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        // Not clickable, so merge it by hand to read as one sensor. The info button stays its own stop.
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    ) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(sensor.name, style = MaterialTheme.typography.titleMedium)
                Tag(stringResource(Res.string.scan_tag_on_lan_no_token))
                if (level.developer) sensor.station?.let { Mono(it.take(8)) }
            }
            InfoButton(InfoContent(stringResource(Res.string.devices_on_wifi), stringResource(Res.string.info_wifi_only)))
        }
    }
}

@Composable
private fun wifiTagText(tag: WifiTag): String =
    stringResource(if (tag == WifiTag.WITH_ACCESS) Res.string.scan_tag_on_lan else Res.string.scan_tag_on_lan_no_token)

/**
 * From the archive on this phone, never from a live link. The last UTC day with valid readings as
 * a mood, or the file count and the last sync when the platform has no day mean. The "synced N ago"
 * part is a leaf that owns the 1 Hz clock.
 */
@Composable
private fun ArchiveLine(glance: ArchiveGlance?, level: DetailLevel) {
    glance ?: return
    val mean = glance.lastDayPm25Mean
    val dayStart = glance.lastDayStartS
    val mood = mean?.let { AirMood.ofOrNull(it.toDouble()) }
    val files = pluralStringResource(Res.plurals.scan_files, glance.files, glance.files)
    val syncedTemplate = stringResource(Res.string.scan_synced)
    val ago = agoFormatter()
    if (mean != null && mood != null && dayStart != null) {
        val metric = Metrics.primary
        val (day, month) = DeviceText.utcDayMonth(dayStart)
        val base = stringResource(
            Res.string.scan_archive_day,
            moodWord(mood.step),
            metric.label,
            formatFixed(mean.toDouble(), 0),
            metric.unit,
            "$day ${stringResource(MONTHS[month - 1])}",
        )
        val coverage = glance.lastDayCoverage?.takeIf { level.detailed }
            ?.let { ", " + stringResource(Res.string.scan_archive_coverage, "${(it * 100).toInt()} %") } ?: ""
        val fileCount = if (level.detailed) ", $files" else ""
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            StatusDot(Color(mood.step.color))
            val synced = glance.lastSyncedMs
            if (synced != null) {
                TickingText(
                    { now -> "$base$coverage$fileCount, " + DeviceText.fill(syncedTemplate, ago(now - synced)) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                )
            } else {
                Text("$base$coverage$fileCount", style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
        }
    } else if (glance.lastDataUtcNs == null && glance.files > 0) {
        Text(
            pluralStringResource(Res.plurals.scan_no_clock, glance.files, glance.files),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    } else if (glance.files > 0) {
        val synced = glance.lastSyncedMs
        if (synced != null) {
            TickingText(
                { now -> "$files, " + DeviceText.fill(syncedTemplate, ago(now - synced)) },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Text(files, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private val MONTHS = listOf(
    Res.string.month_1, Res.string.month_2, Res.string.month_3, Res.string.month_4,
    Res.string.month_5, Res.string.month_6, Res.string.month_7, Res.string.month_8,
    Res.string.month_9, Res.string.month_10, Res.string.month_11, Res.string.month_12,
)

/** "N ago" from the shared catalogue, for leaf `TickingText` lambdas. */
@Composable
private fun agoFormatter(): (Long) -> String {
    val justNow = stringResource(Res.string.ago_just_now)
    val seconds = stringResource(Res.string.ago_seconds)
    val minutes = stringResource(Res.string.ago_minutes)
    val hours = stringResource(Res.string.ago_hours)
    val days = stringResource(Res.string.ago_days)
    return remember(justNow, seconds, minutes, hours, days) {
        { ageMs ->
            val (unit, n) = DeviceText.ago(ageMs)
            when (unit) {
                AgoUnit.JUST_NOW -> justNow
                AgoUnit.SECONDS -> DeviceText.fill(seconds, n)
                AgoUnit.MINUTES -> DeviceText.fill(minutes, n)
                AgoUnit.HOURS -> DeviceText.fill(hours, n)
                AgoUnit.DAYS -> DeviceText.fill(days, n)
            }
        }
    }
}

@Composable
private fun ForgetMenu(onForget: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    IconButton(onClick = { menu = true }) { Icon(AppIcons.MoreVert, contentDescription = stringResource(Res.string.scan_options)) }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.scan_forget)) },
            onClick = {
                menu = false
                onForget()
            },
        )
    }
}

@Composable
private fun Mono(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A small tag on a card curve, like the website's `.brand-eyebrow`. */
@Composable
private fun Tag(text: String) {
    Surface(shape = FloatingControl.BadgeShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text,
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}
