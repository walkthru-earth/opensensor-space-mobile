package space.opensensor.devices

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import space.opensensor.airquality.WhoStep
import space.opensensor.data.history.LatestDay
import space.opensensor.designsystem.AppIcons
import space.opensensor.designsystem.FloatingControl
import space.opensensor.designsystem.InfoButton
import space.opensensor.designsystem.InfoContent
import space.opensensor.designsystem.SignalBars
import space.opensensor.designsystem.StatusDot
import space.opensensor.designsystem.TickingText
import space.opensensor.designsystem.moodWord
import space.opensensor.designsystem.pm25Label
import space.opensensor.devices.resources.Res
import space.opensensor.devices.resources.ago_days
import space.opensensor.devices.resources.ago_hours
import space.opensensor.devices.resources.ago_just_now
import space.opensensor.devices.resources.ago_minutes
import space.opensensor.devices.resources.ago_seconds
import space.opensensor.devices.resources.conn_failed
import space.opensensor.devices.resources.conn_link_lost
import space.opensensor.devices.resources.conn_pairing
import space.opensensor.devices.resources.devices_on_wifi
import space.opensensor.devices.resources.info_wifi_only
import space.opensensor.devices.resources.month_1
import space.opensensor.devices.resources.month_10
import space.opensensor.devices.resources.month_11
import space.opensensor.devices.resources.month_12
import space.opensensor.devices.resources.month_2
import space.opensensor.devices.resources.month_3
import space.opensensor.devices.resources.month_4
import space.opensensor.devices.resources.month_5
import space.opensensor.devices.resources.month_6
import space.opensensor.devices.resources.month_7
import space.opensensor.devices.resources.month_8
import space.opensensor.devices.resources.month_9
import space.opensensor.devices.resources.scan_archive_coverage
import space.opensensor.devices.resources.scan_archive_day
import space.opensensor.devices.resources.scan_archive_day_plain
import space.opensensor.devices.resources.scan_background
import space.opensensor.devices.resources.scan_collecting
import space.opensensor.devices.resources.scan_files
import space.opensensor.devices.resources.scan_no_clock
import space.opensensor.devices.resources.scan_seen
import space.opensensor.devices.resources.scan_synced
import space.opensensor.devices.resources.scan_tag_known
import space.opensensor.devices.resources.scan_tag_on_lan
import space.opensensor.devices.resources.scan_tag_on_lan_no_token
import space.opensensor.devices.resources.scan_tag_paired
import space.opensensor.devices.resources.scan_unnamed
import space.opensensor.model.AppProfile
import space.opensensor.settings.DetailLevel
import space.opensensor.strings.resources.conn_connecting
import space.opensensor.strings.resources.conn_ready
import space.opensensor.units.UnitFormatter
import space.opensensor.strings.resources.Res as SharedRes

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
internal fun NearbyRow(row: DeviceRow, level: DetailLevel, onClick: () -> Unit) {
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
            Spacer(Modifier.width(12.dp))
        }
    }
}

@Composable
internal fun KnownRow(row: DeviceRow, level: DetailLevel, onClick: () -> Unit) {
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
                if (level.developer && row.archive == null) row.thing?.let { Mono(it.take(8)) }
                ArchiveLine(row.archive, level)
            }
            // Opens the sensor's page, where Forget lives.
            Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f))
            Spacer(Modifier.width(12.dp))
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
                if (level.developer) sensor.thing?.let { Mono(it.take(8)) }
            }
            InfoButton(InfoContent(stringResource(Res.string.devices_on_wifi), stringResource(Res.string.info_wifi_only)))
        }
    }
}

@Composable
private fun wifiTagText(tag: WifiTag): String =
    stringResource(if (tag == WifiTag.WITH_ACCESS) Res.string.scan_tag_on_lan else Res.string.scan_tag_on_lan_no_token)

/** The unit system the archive lines show day means in, from [MyDevicesScreen]. */
internal val LocalArchiveUnits = staticCompositionLocalOf { UnitFormatter.Metric }

/**
 * How the archive line shows [day]. The mean rounds to the profile's display step in [units], and a
 * unit [units] converts keeps its own decimals. [step] is the WHO step of a metric with a health
 * scale. Null when such a metric's mean has no step, so the line never shows a banded value unjudged.
 */
internal data class DayLine(val value: String, val unit: String, val step: WhoStep?)

internal fun dayLine(day: LatestDay, units: UnitFormatter): DayLine? {
    val metric = day.metric
    val mean = day.mean.toDouble()
    val step = if (metric.banded) metric.stepOf(mean) ?: return null else null
    return DayLine(units.format(mean, metric.unit, day.displayStep, AppProfile.decimalsOf(day.displayStep)), units.unit(metric.unit), step)
}

/**
 * From the archive on this phone, never from a live link. The last UTC day with valid readings,
 * with its WHO mood when the metric has a health scale and a neutral dot otherwise, or the file
 * count and the last sync when the platform has no day mean. The "synced N ago" part is a leaf
 * that owns the 1 Hz clock.
 */
@Composable
private fun ArchiveLine(glance: ArchiveGlance?, level: DetailLevel) {
    glance ?: return
    val day = glance.lastDay
    val line = day?.let { dayLine(it, LocalArchiveUnits.current) }
    val files = pluralStringResource(Res.plurals.scan_files, glance.files, glance.files)
    val syncedTemplate = stringResource(Res.string.scan_synced)
    val ago = agoFormatter()
    if (day != null && line != null) {
        val metric = day.metric
        val step = line.step
        val (dayOfMonth, month) = DeviceText.utcDayMonth(day.startEpochS)
        val label = metric.basis?.let { pm25Label(it) } ?: metric.label(Locale.current.language)
        val date = "$dayOfMonth ${stringResource(MONTHS[month - 1])}"
        val base = if (step != null) {
            stringResource(Res.string.scan_archive_day, moodWord(step), label, line.value, line.unit, date)
        } else {
            stringResource(Res.string.scan_archive_day_plain, label, line.value, line.unit, date)
        }
        val coverage = day.coverage?.takeIf { level.detailed }
            ?.let { ", " + stringResource(Res.string.scan_archive_coverage, "${(it * 100).toInt()} %") } ?: ""
        val fileCount = if (level.detailed) ", $files" else ""
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            StatusDot(step?.let { Color(it.color) } ?: MaterialTheme.colorScheme.onSurfaceVariant)
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
