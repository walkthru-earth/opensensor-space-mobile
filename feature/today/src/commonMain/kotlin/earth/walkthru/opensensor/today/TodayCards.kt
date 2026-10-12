package earth.walkthru.opensensor.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.data.SyncProgress
import earth.walkthru.aqsync.data.ble.ConfigSnapshot
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.history.MetricSpec
import earth.walkthru.aqsync.data.history.Metrics
import earth.walkthru.aqsync.data.history.NowReading
import earth.walkthru.aqsync.data.history.TodaySoFar
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.DeviceStatus
import earth.walkthru.aqsync.data.protocol.LiveProfile
import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.data.protocol.PlantowerBatchCandidate
import earth.walkthru.aqsync.data.protocol.PmsStatus
import earth.walkthru.aqsync.data.protocol.TemperatureSource
import earth.walkthru.aqsync.settings.DetailLevel
import earth.walkthru.aqsync.units.UnitFormatter
import earth.walkthru.opensensor.designsystem.AppIcons
import earth.walkthru.opensensor.designsystem.BigValue
import earth.walkthru.opensensor.designsystem.BrandCard
import earth.walkthru.opensensor.designsystem.CardHeader
import earth.walkthru.opensensor.designsystem.CardTone
import earth.walkthru.opensensor.designsystem.Eyebrow
import earth.walkthru.opensensor.designsystem.InfoContent
import earth.walkthru.opensensor.designsystem.OutlinePillButton
import earth.walkthru.opensensor.designsystem.PeacockExpression
import earth.walkthru.opensensor.designsystem.PeacockPortrait
import earth.walkthru.opensensor.designsystem.PillButton
import earth.walkthru.opensensor.designsystem.StatLine
import earth.walkthru.opensensor.designsystem.StatusDot
import earth.walkthru.opensensor.designsystem.TickingText
import earth.walkthru.opensensor.designsystem.WarningNote
import earth.walkthru.opensensor.designsystem.listSeparator
import earth.walkthru.opensensor.designsystem.moodWord
import earth.walkthru.opensensor.designsystem.nowEpochMillis
import earth.walkthru.opensensor.designsystem.peacockExpressionFor
import earth.walkthru.opensensor.designsystem.rememberNowMillis
import earth.walkthru.opensensor.designsystem.spokenList
import earth.walkthru.opensensor.today.resources.Res
import earth.walkthru.opensensor.today.resources.batch_explain
import earth.walkthru.opensensor.today.resources.batch_title
import earth.walkthru.opensensor.today.resources.batch_value
import earth.walkthru.opensensor.today.resources.batch_waiting
import earth.walkthru.opensensor.today.resources.board_ambient_note
import earth.walkthru.opensensor.today.resources.board_charging
import earth.walkthru.opensensor.today.resources.board_millivolts
import earth.walkthru.opensensor.today.resources.board_temp
import earth.walkthru.opensensor.today.resources.board_temp_unsupported
import earth.walkthru.opensensor.today.resources.board_usb_millivolts
import earth.walkthru.opensensor.today.resources.clock_actions_explain
import earth.walkthru.opensensor.today.resources.clock_generation
import earth.walkthru.opensensor.today.resources.clock_host
import earth.walkthru.opensensor.today.resources.clock_last_sample
import earth.walkthru.opensensor.today.resources.clock_network
import earth.walkthru.opensensor.today.resources.clock_none
import earth.walkthru.opensensor.today.resources.clock_none_explain
import earth.walkthru.opensensor.today.resources.clock_rtc
import earth.walkthru.opensensor.today.resources.clock_set
import earth.walkthru.opensensor.today.resources.clock_title
import earth.walkthru.opensensor.today.resources.clock_unknown
import earth.walkthru.opensensor.today.resources.device_boot
import earth.walkthru.opensensor.today.resources.device_config_read
import earth.walkthru.opensensor.today.resources.device_connected_over
import earth.walkthru.opensensor.today.resources.device_default
import earth.walkthru.opensensor.today.resources.device_dictionary
import earth.walkthru.opensensor.today.resources.device_firmware
import earth.walkthru.opensensor.today.resources.device_id
import earth.walkthru.opensensor.today.resources.device_link
import earth.walkthru.opensensor.today.resources.device_max_read
import earth.walkthru.opensensor.today.resources.device_payload
import earth.walkthru.opensensor.today.resources.device_protocol
import earth.walkthru.opensensor.today.resources.device_schema
import earth.walkthru.opensensor.today.resources.device_schema_cols
import earth.walkthru.opensensor.today.resources.device_station
import earth.walkthru.opensensor.today.resources.device_title
import earth.walkthru.opensensor.today.resources.device_unnamed
import earth.walkthru.opensensor.today.resources.env_title
import earth.walkthru.opensensor.today.resources.env_unsupported
import earth.walkthru.opensensor.today.resources.flush
import earth.walkthru.opensensor.today.resources.fresh_none
import earth.walkthru.opensensor.today.resources.fresh_stale
import earth.walkthru.opensensor.today.resources.fresh_updated
import earth.walkthru.opensensor.today.resources.now_last_reading
import earth.walkthru.opensensor.today.resources.now_right_now
import earth.walkthru.opensensor.today.resources.now_sample_number
import earth.walkthru.opensensor.today.resources.now_snapshot_note
import earth.walkthru.opensensor.today.resources.now_waiting
import earth.walkthru.opensensor.today.resources.particles_title
import earth.walkthru.opensensor.today.resources.readings_absent
import earth.walkthru.opensensor.today.resources.readings_cf1
import earth.walkthru.opensensor.today.resources.readings_title
import earth.walkthru.opensensor.today.resources.row_groups
import earth.walkthru.opensensor.today.resources.storage_buffered
import earth.walkthru.opensensor.today.resources.storage_codec
import earth.walkthru.opensensor.today.resources.storage_degraded
import earth.walkthru.opensensor.today.resources.storage_dropped
import earth.walkthru.opensensor.today.resources.storage_errors
import earth.walkthru.opensensor.today.resources.storage_failed
import earth.walkthru.opensensor.today.resources.storage_files_this_boot
import earth.walkthru.opensensor.today.resources.storage_full
import earth.walkthru.opensensor.today.resources.storage_heap
import earth.walkthru.opensensor.today.resources.storage_microsd
import earth.walkthru.opensensor.today.resources.storage_minutes
import earth.walkthru.opensensor.today.resources.storage_missed
import earth.walkthru.opensensor.today.resources.storage_none
import earth.walkthru.opensensor.today.resources.storage_not_mounted
import earth.walkthru.opensensor.today.resources.storage_open
import earth.walkthru.opensensor.today.resources.storage_open_row_groups
import earth.walkthru.opensensor.today.resources.storage_partial
import earth.walkthru.opensensor.today.resources.storage_rotation
import earth.walkthru.opensensor.today.resources.storage_status_age
import earth.walkthru.opensensor.today.resources.storage_title
import earth.walkthru.opensensor.today.resources.storage_uptime
import earth.walkthru.opensensor.today.resources.storage_used_of
import earth.walkthru.opensensor.today.resources.sync_button
import earth.walkthru.opensensor.today.resources.sync_button_running
import earth.walkthru.opensensor.today.resources.sync_caught_up
import earth.walkthru.opensensor.today.resources.sync_empty
import earth.walkthru.opensensor.today.resources.sync_estimated_detail
import earth.walkthru.opensensor.today.resources.sync_explain
import earth.walkthru.opensensor.today.resources.sync_ready
import earth.walkthru.opensensor.today.resources.sync_running
import earth.walkthru.opensensor.today.resources.sync_some_failed
import earth.walkthru.opensensor.today.resources.sync_title
import earth.walkthru.opensensor.today.resources.sync_unplaced_detail
import earth.walkthru.opensensor.today.resources.sync_unplaced_simple
import earth.walkthru.opensensor.today.resources.today_average
import earth.walkthru.opensensor.today.resources.today_none
import earth.walkthru.opensensor.today.resources.today_none_clock
import earth.walkthru.opensensor.today.resources.today_of_the_day
import earth.walkthru.opensensor.today.resources.today_readings_since_midnight
import earth.walkthru.opensensor.today.resources.today_so_far
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** The display units for Today's readings, provided by [TodayScreen] from its `units` parameter. */
internal val LocalTodayUnits = staticCompositionLocalOf { UnitFormatter.Metric }

/** The registry metric that a reviewed profile's LIVE `t` field measures. */
fun temperatureMetric(source: TemperatureSource?): MetricSpec =
    if (source == TemperatureSource.AMBIENT) Metrics.AMBIENT_TEMP else Metrics.BOARD_TEMP

/**
 * True while a reading received at [receivedAtEpochMs] is younger than [STALE_AFTER_MS]. Flips once
 * at the moment it goes stale instead of ticking every second.
 */
@Composable
internal fun rememberIsFresh(receivedAtEpochMs: Long?): Boolean {
    val fresh by produceState(
        initialValue = receivedAtEpochMs != null && nowEpochMillis() - receivedAtEpochMs <= STALE_AFTER_MS,
        receivedAtEpochMs,
    ) {
        if (receivedAtEpochMs == null) {
            value = false
            return@produceState
        }
        val remaining = receivedAtEpochMs + STALE_AFTER_MS - nowEpochMillis()
        value = remaining >= 0
        if (remaining >= 0) {
            delay(remaining + 1)
            value = false
        }
    }
    return fresh
}

/**
 * "Updated 5 s ago" with a dot that greys out when the reading is stale. A leaf, it owns the 1 Hz
 * clock so the card around it does not recompose every second.
 */
@Composable
internal fun FreshnessLabel(receivedAtEpochMs: Long?, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val now = rememberNowMillis()
    val ageMs = receivedAtEpochMs?.let { now - it }
    val stale = ageMs == null || ageMs > STALE_AFTER_MS
    val ago = agoFormatter()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusDot(if (stale) color.copy(alpha = 0.4f) else color, size = 8)
        Text(
            when {
                ageMs == null -> stringResource(Res.string.fresh_none)
                stale -> stringResource(Res.string.fresh_stale, ago(ageMs))
                else -> stringResource(Res.string.fresh_updated, ago(ageMs))
            },
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/**
 * The hero of Today: the primary metric right now as a peacock, a big number, the mood in words and
 * one line of advice. The sensor's own state comes before any number. Colour is never the only
 * carrier, the mood is also a word and a character expression.
 */
@Composable
internal fun MetricHeroCard(
    live: LiveSample?,
    receivedAtEpochMs: Long?,
    level: DetailLevel,
    modifier: Modifier = Modifier,
    metric: MetricSpec = Metrics.primary,
) {
    val reading = NowReading.of(live)
    val fresh = rememberIsFresh(receivedAtEpochMs)
    val mood = (reading as? NowReading.Reading)?.mood
    val onCard = MaterialTheme.colorScheme.onPrimaryContainer
    val quiet = onCard.copy(alpha = 0.85f)

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val portraitSize = if (maxWidth < 340.dp) 136.dp else 176.dp
        BrandCard(tone = CardTone.HERO) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val title = stringResource(if (fresh) Res.string.now_right_now else Res.string.now_last_reading)
                CardHeader(
                    title,
                    modifier = Modifier.weight(1f),
                    info = InfoContent(title, stringResource(Res.string.now_snapshot_note), stringResource(nowInfoBodyRes(level))),
                    color = quiet,
                )
                FreshnessLabel(receivedAtEpochMs, color = quiet)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PeacockPortrait(
                    expression = when (reading) {
                        is NowReading.Reading -> if (fresh) peacockExpressionFor(mood) else PeacockExpression.SLEEPING
                        NowReading.Waiting -> PeacockExpression.CONFUSED
                        is NowReading.SensorNotReady -> when (reading.status) {
                            PmsStatus.WARMING -> PeacockExpression.WORKING
                            PmsStatus.STALE -> PeacockExpression.SLEEPING
                            PmsStatus.ABSENT, PmsStatus.ERROR -> PeacockExpression.CONCERNED
                            PmsStatus.VALID -> PeacockExpression.CONFUSED
                        }
                    },
                    size = portraitSize,
                )
                Spacer(Modifier.width(14.dp))
                when (reading) {
                    // Value, unit and metric name read as one element ("12, µg/m³, PM2.5").
                    is NowReading.Reading -> Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                        val value = fmtPm(reading.pm25)
                        Text(
                            value,
                            style = if (value.length > 2 || portraitSize < 176.dp) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayLarge,
                            color = if (fresh) onCard else quiet,
                            maxLines = 1,
                        )
                        Text("${metric.label}  ${metric.unit}", style = MaterialTheme.typography.titleMedium, color = quiet)
                    }
                    NowReading.Waiting -> Text(
                        stringResource(Res.string.now_waiting),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    is NowReading.SensorNotReady -> Column(Modifier.weight(1f)) {
                        Text(reading.status.titleText(), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                        Text(reading.status.hintText(), style = MaterialTheme.typography.bodyMedium, color = quiet)
                    }
                }
            }
            if (reading is NowReading.Reading) {
                Spacer(Modifier.height(16.dp))
                // The mood and its comparison read as one element.
                Row(
                    Modifier.semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MoodPill(reading.mood)
                    Text(reading.mood.comparisonText(), style = MaterialTheme.typography.bodySmall, color = quiet, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(heroLineRes(reading.mood, level)), style = MaterialTheme.typography.bodyLarge)
            }
            if (level.developer) {
                live?.seq?.let {
                    Text(stringResource(Res.string.now_sample_number, it), style = MaterialTheme.typography.labelSmall, color = quiet, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

/**
 * Temperature (and humidity where the reviewed profile has it). The hero on a device without a
 * particle sensor, and a second card under the air on an ambient-sensing board. An unknown profile
 * says that live values are not supported yet, never guesses.
 */
@Composable
internal fun EnvironmentCard(profile: LiveProfile?, live: LiveSample?, receivedAtEpochMs: Long?, hero: Boolean, modifier: Modifier = Modifier) {
    BrandCard(modifier = modifier, tone = if (hero) CardTone.SOFT else CardTone.PLAIN) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(stringResource(Res.string.env_title), modifier = Modifier.weight(1f), heading = true)
            FreshnessLabel(receivedAtEpochMs)
        }
        Spacer(Modifier.height(12.dp))
        if (profile == null) {
            Text(stringResource(Res.string.env_unsupported), style = MaterialTheme.typography.bodyMedium)
            return@BrandCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            val temp = temperatureMetric(profile.temperature)
            val units = LocalTodayUnits.current
            BigValue(temp.label, fmt(units.value(live?.t, temp.unit)), units.unit(temp.unit), Modifier.weight(1f))
            if (profile.humidity) BigValue(Metrics.HUMIDITY.label, fmt(live?.rh), Metrics.HUMIDITY.unit, Modifier.weight(1f))
        }
        if (live == null) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(Res.string.now_waiting), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Today so far: the local day's real average from the phone archive, with how much of the day it
 * covers. Without timed readings it says what to do next instead of showing a number.
 */
@Composable
internal fun TodaySoFarCard(today: TodaySoFar?, clockMissing: Boolean, level: DetailLevel, modifier: Modifier = Modifier) {
    BrandCard(modifier = modifier, tone = CardTone.SOFT) {
        Eyebrow(stringResource(Res.string.today_so_far), heading = true)
        Spacer(Modifier.height(8.dp))
        if (today == null) {
            Text(
                stringResource(if (clockMissing) Res.string.today_none_clock else Res.string.today_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@BrandCard
        }
        // The current reading owns the page's character. This summary adds the average and coverage.
        Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.today_average, fmtPm(today.pm25Mean.toDouble())), style = MaterialTheme.typography.titleLarge)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${(today.coverage * 100).toInt()} %", style = MaterialTheme.typography.displaySmall)
                Text(stringResource(Res.string.today_of_the_day), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (level.detailed) {
            Spacer(Modifier.height(6.dp))
            Text(
                pluralStringResource(Res.plurals.today_readings_since_midnight, today.validRows, today.validRows),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Smart Sync: the sync state in words and the one primary action of Today. The hero owns the page's
 * character, so this card adds no repeated portrait. Detailed adds how earlier readings were placed.
 */
@Composable
internal fun SmartSyncCard(sync: SyncProgress, archive: TodayArchive, enabled: Boolean, level: DetailLevel, onSync: () -> Unit, modifier: Modifier = Modifier) {
    val mood = syncMood(sync, archive)
    BrandCard(modifier = modifier, tone = CardTone.SOFT) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CardHeader(stringResource(Res.string.sync_title), info = InfoContent(stringResource(Res.string.sync_title), stringResource(Res.string.sync_explain)))
                Text(
                    when (mood) {
                        SyncMood.RUNNING -> pluralStringResource(Res.plurals.sync_running, sync.filesTotal, sync.filesDone, sync.filesTotal)
                        SyncMood.CAUGHT_UP -> stringResource(Res.string.sync_caught_up)
                        SyncMood.SOME_FAILED -> pluralStringResource(Res.plurals.sync_some_failed, sync.failures.size, sync.failures.size)
                        SyncMood.READY -> pluralStringResource(Res.plurals.sync_ready, archive.files, archive.files)
                        SyncMood.EMPTY -> stringResource(Res.string.sync_empty)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    // The sync state in words changes while the user waits, so screen readers hear it.
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        if (mood == SyncMood.RUNNING) {
            Spacer(Modifier.height(10.dp))
            // Without a byte total yet (the plan is still being read, or the platform counts files only) the bar runs.
            if (sync.bytesTotal > 0) {
                LinearProgressIndicator(progress = { sync.fraction }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        if (archive.unplacedRows > 0) {
            Text(
                if (level.detailed) {
                    pluralStringResource(Res.plurals.sync_unplaced_detail, archive.unplacedRows.toPluralCount(), archive.unplacedRows)
                } else {
                    stringResource(Res.string.sync_unplaced_simple)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else if (level.detailed && archive.estimatedRows > 0) {
            Text(
                pluralStringResource(Res.plurals.sync_estimated_detail, archive.estimatedRows.toPluralCount(), archive.estimatedRows),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        PillButton(
            text = stringResource(if (sync.running) Res.string.sync_button_running else Res.string.sync_button),
            onClick = onSync,
            enabled = enabled && !sync.running,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun Long.toPluralCount(): Int = coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

/**
 * The logger's automatically synchronized clock in words. **Finalize buffered
 * rows** is a power action and appears from Detailed. Developer adds the anchor generation and the
 * last sample's UTC instant.
 */
@Composable
internal fun ClockCard(
    status: DeviceStatus?,
    live: LiveSample?,
    enabled: Boolean,
    level: DetailLevel,
    onFlush: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val synced = status?.utcSynced
    val label = when (status?.clockSource) {
        1 -> stringResource(Res.string.clock_host)
        2 -> stringResource(Res.string.clock_rtc)
        3 -> stringResource(Res.string.clock_network)
        0 -> stringResource(Res.string.clock_none)
        else -> when (synced) {
            true -> stringResource(Res.string.clock_set)
            false -> stringResource(Res.string.clock_none)
            null -> stringResource(Res.string.clock_unknown)
        }
    }
    BrandCard(modifier = modifier) {
        CardHeader(stringResource(Res.string.clock_title), info = InfoContent(stringResource(Res.string.clock_title), stringResource(Res.string.clock_actions_explain)))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(
                when (synced) {
                    true -> MaterialTheme.colorScheme.secondary
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.outline
                },
            )
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
        if (synced == false) {
            Text(
                stringResource(Res.string.clock_none_explain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (level.developer) {
            status?.gen?.let {
                Text(stringResource(Res.string.clock_generation, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            live?.utc?.let { ns ->
                Text(
                    stringResource(Res.string.clock_last_sample, isoInstant(ns)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (level.detailed) OutlinePillButton(stringResource(Res.string.flush), onClick = onFlush, enabled = enabled)
        }
    }
}

/** A LIVE `utc` in nanoseconds as an ISO-8601 instant, the Developer line on the clock card. */
internal fun isoInstant(utcNs: Long): String =
    Instant.fromEpochSeconds(utcNs / 1_000_000_000L, utcNs % 1_000_000_000L).toString()

/**
 * Detailed level, on the reviewed dictionary after the board acknowledged the opt-in: the
 * unvalidated batch estimate. Only a fresh valid count received after that acknowledgement is used.
 */
@Composable
internal fun BatchEstimateCard(live: LiveSample?, receivedAtEpochMs: Long?, config: ConfigSnapshot?, modifier: Modifier = Modifier) {
    val fresh = rememberIsFresh(receivedAtEpochMs) && receivedAtEpochMs != null && receivedAtEpochMs >= (config?.atEpochMs ?: Long.MAX_VALUE)
    val estimate = if (fresh) {
        PlantowerBatchCandidate.estimate(config?.config?.sensor?.serial, config?.config?.sensor?.batchCandidate, live?.pms, live?.n03)
    } else {
        null
    }
    BrandCard(modifier = modifier) {
        val title = stringResource(Res.string.batch_title)
        CardHeader(title, info = InfoContent(title, stringResource(Res.string.batch_explain, PlantowerBatchCandidate.FACTOR.toString())))
        Text(
            if (estimate != null) stringResource(Res.string.batch_value, fmt(estimate)) else stringResource(Res.string.batch_waiting),
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

/**
 * Detailed level: the other particle sizes beside the hero's PM2.5. Developer adds the CF=1 raw
 * values, which are a sensor-internal correction and easy to misread.
 */
@Composable
internal fun ReadingsCard(live: LiveSample?, receivedAtEpochMs: Long?, level: DetailLevel, modifier: Modifier = Modifier) {
    val showPm = live?.pmValid == true
    BrandCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(stringResource(Res.string.readings_title), modifier = Modifier.weight(1f), heading = true)
            FreshnessLabel(receivedAtEpochMs)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            BigValue(Metrics.PM10.label, if (showPm) fmt(live.pm10) else NO_VALUE, Metrics.PM10.unit, Modifier.weight(1f))
            BigValue(Metrics.PM1.label, if (showPm) fmt(live.pm1) else NO_VALUE, Metrics.PM1.unit, Modifier.weight(1f))
        }
        if (!showPm) {
            val status = live?.pmsStatus
            Text(
                when {
                    live == null -> stringResource(Res.string.now_waiting)
                    status == null || status == PmsStatus.VALID -> stringResource(Res.string.readings_absent)
                    else -> status.titleText()
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (level.developer && showPm && (live.c25 != null || live.c1 != null || live.c10 != null)) {
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(Res.string.readings_cf1, fmt(live.c1), fmt(live.c25), fmt(live.c10)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Detailed level: particle counts per 0.1 L by size bin. */
@Composable
internal fun ParticlesCard(live: LiveSample?, modifier: Modifier = Modifier) {
    val counts = listOf(
        ">0.3" to live?.n03,
        ">0.5" to live?.n05,
        ">1" to live?.n1,
        ">2.5" to live?.n25,
        ">5" to live?.n5,
        ">10" to live?.n10,
    )
    BrandCard(modifier = modifier) {
        // Not uppercased. "µ".uppercase() is the Greek capital mu, which once rendered as "(MM)".
        Eyebrow(stringResource(Res.string.particles_title), uppercase = false, heading = true)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            counts.forEach { (label, n) ->
                // The count and its size bin read as one element.
                Column(Modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(n?.toString() ?: NO_VALUE, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Detailed level: the board's own temperature and the battery. Millivolts and USB voltage are Developer facts. */
@Composable
internal fun BoardCard(live: LiveSample?, profile: LiveProfile?, level: DetailLevel, modifier: Modifier = Modifier) {
    BrandCard(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f)) {
                val temp = temperatureMetric(profile?.temperature)
                val units = LocalTodayUnits.current
                BigValue(temp.label, if (profile != null) fmt(units.value(live?.t, temp.unit)) else NO_VALUE, units.unit(temp.unit))
                Text(
                    stringResource(
                        when (profile?.temperature) {
                            TemperatureSource.BOARD -> Res.string.board_temp
                            TemperatureSource.AMBIENT -> Res.string.board_ambient_note
                            null -> Res.string.board_temp_unsupported
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Battery label, percent and charging state read as one element.
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Eyebrow(Metrics.BATTERY.label, uppercase = false)
                Row(verticalAlignment = Alignment.Bottom) {
                    Icon(
                        if (live?.chg == 1) AppIcons.BatteryChargingFull else AppIcons.BatteryStd,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(live?.pct?.let { "$it" } ?: NO_VALUE, style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.width(4.dp))
                    Text(Metrics.BATTERY.unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 7.dp))
                }
                val charging = live?.chg == 1
                val chargingText = stringResource(Res.string.board_charging)
                val millivolts = live?.bat?.let { stringResource(Res.string.board_millivolts, it) }
                val usb = live?.vbus?.takeIf { it > 0 }?.let { stringResource(Res.string.board_usb_millivolts, it) }
                val detail = buildList {
                    if (charging) add(chargingText)
                    if (level.developer) {
                        millivolts?.let(::add)
                        usb?.let(::add)
                    }
                }
                if (detail.isNotEmpty()) {
                    Text(spokenList(detail, listSeparator()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * Detailed level: the logger's storage counters and microSD use. Problem counters appear in Detailed
 * only when they are not zero. Developer adds codec, retained partial files, uptime and free heap.
 */
@Composable
internal fun StorageCard(status: DeviceStatus?, receivedAtEpochMs: Long?, level: DetailLevel, modifier: Modifier = Modifier) {
    BrandCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(stringResource(Res.string.storage_title), modifier = Modifier.weight(1f), heading = true)
            if (receivedAtEpochMs != null) {
                val template = stringResource(Res.string.storage_status_age)
                val ago = agoFormatter()
                TickingText({ now -> fill(template, ago(now - receivedAtEpochMs)) })
            }
        }
        Spacer(Modifier.height(8.dp))
        if (status == null) {
            Text(stringResource(Res.string.storage_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@BrandCard
        }
        val warn = MaterialTheme.colorScheme.error
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            // `status.fin` counts files finalized since the device booted, not the card total.
            Counter(stringResource(Res.string.storage_files_this_boot), status.fin)
            Counter(stringResource(Res.string.storage_buffered), status.buf?.toLong())
            // Firmware v6 keeps an open multi-row-group file on the card between batches.
            status.open?.let { Counter(stringResource(Res.string.storage_open), it.toLong()) }
            if (level.developer || (status.drop ?: 0) > 0) Counter(stringResource(Res.string.storage_dropped), status.drop, if ((status.drop ?: 0) > 0) warn else null)
            if (level.developer || (status.err ?: 0) > 0) Counter(stringResource(Res.string.storage_errors), status.err, if ((status.err ?: 0) > 0) warn else null)
            if (level.developer || (status.miss ?: 0) > 0) Counter(stringResource(Res.string.storage_missed), status.miss, if ((status.miss ?: 0) > 0) warn else null)
        }
        Spacer(Modifier.height(10.dp))
        StatLine(
            stringResource(Res.string.storage_microsd),
            when {
                status.sd == null -> NO_VALUE
                !status.sdMounted -> stringResource(Res.string.storage_not_mounted)
                else -> stringResource(
                    Res.string.storage_used_of,
                    status.sdUsedKib?.let { formatBytes(it * 1024) } ?: "?",
                    status.sdKib?.let { formatBytes(it * 1024) } ?: "?",
                )
            },
            valueColor = if (status.sd == 0) warn else MaterialTheme.colorScheme.onSurface,
        )
        StatLine(
            stringResource(Res.string.storage_rotation),
            status.intervalSeconds?.let { s -> stringResource(Res.string.storage_minutes, s / 60) } ?: NO_VALUE,
        )
        if (level.developer) {
            status.openRowGroups?.let { StatLine(stringResource(Res.string.storage_open_row_groups), pluralStringResource(Res.plurals.row_groups, it, it)) }
            StatLine(stringResource(Res.string.storage_codec), status.codec ?: NO_VALUE)
            StatLine(stringResource(Res.string.storage_partial), status.part?.toString() ?: NO_VALUE)
            StatLine(stringResource(Res.string.storage_uptime), status.upSeconds?.let(::formatUptime) ?: NO_VALUE)
            StatLine(stringResource(Res.string.storage_heap), status.heap?.let { formatBytes(it) } ?: NO_VALUE)
        }
        if (status.storageFailed) {
            Spacer(Modifier.height(8.dp))
            WarningNote(stringResource(Res.string.storage_failed))
        }
        if (status.sdFull || status.sdDegraded) {
            Spacer(Modifier.height(8.dp))
            WarningNote(stringResource(if (status.sdFull) Res.string.storage_full else Res.string.storage_degraded))
        }
    }
}

@Composable
private fun Counter(label: String, value: Long?, color: Color? = null) {
    // The number and its label read as one element.
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(value?.toString() ?: NO_VALUE, style = MaterialTheme.typography.titleLarge, color = color ?: MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The device card. Simple names the device and how it is connected. Detailed adds firmware and
 * protocol. Developer adds every identifier the firmware reports and the link details.
 */
@Composable
internal fun DeviceInfoCard(
    name: String?,
    info: DeviceInfo?,
    connection: ConnectionState,
    transport: TransportKind?,
    level: DetailLevel,
    modifier: Modifier = Modifier,
    pairingRepair: Boolean = false,
    configReadAtEpochMs: Long? = null,
) {
    BrandCard(modifier = modifier) {
        Eyebrow(stringResource(Res.string.device_title), heading = true)
        Spacer(Modifier.height(6.dp))
        Text(name ?: stringResource(Res.string.device_unnamed), style = MaterialTheme.typography.titleLarge)
        Text(
            if (connection.isReady && transport != null) {
                stringResource(Res.string.device_connected_over, transport.labelText())
            } else {
                connection.describe(level.developer, pairingRepair)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (level.detailed) {
            Spacer(Modifier.height(8.dp))
            StatLine(stringResource(Res.string.device_firmware), info?.fw ?: NO_VALUE)
            StatLine(stringResource(Res.string.device_protocol), info?.proto?.let { "v$it" } ?: NO_VALUE)
        }
        if (level.developer) {
            StatLine(stringResource(Res.string.device_schema), stringResource(Res.string.device_schema_cols, info?.schema ?: NO_VALUE, info?.cols?.toString() ?: NO_VALUE))
            StatLine(stringResource(Res.string.device_station), info?.station ?: NO_VALUE)
            StatLine(stringResource(Res.string.device_id), info?.dev ?: NO_VALUE)
            StatLine(stringResource(Res.string.device_boot), info?.boot?.take(16)?.plus("…") ?: NO_VALUE)
            StatLine(stringResource(Res.string.device_dictionary), info?.dict?.take(12)?.plus("…") ?: NO_VALUE)
            StatLine(stringResource(Res.string.device_max_read), info?.maxRead?.let { "$it B" } ?: stringResource(Res.string.device_default))
            val payload = (connection as? ConnectionState.Ready)?.mtu?.takeIf { it > 3 }?.let { stringResource(Res.string.device_payload, it - 3) }
            StatLine(
                stringResource(Res.string.device_link),
                spokenList(listOfNotNull(transport?.labelText(), payload), listSeparator()).ifEmpty { NO_VALUE },
            )
            if (configReadAtEpochMs != null) {
                val label = stringResource(Res.string.device_config_read)
                val ago = agoFormatter()
                TickingText({ now -> "$label ${ago(now - configReadAtEpochMs)}" }, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** The mood face's accessible name when there is no reading. */
@Composable
internal fun noMoodText(): String = moodWord(null)
