package space.opensensor.today

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
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import space.opensensor.data.SyncProgress
import space.opensensor.data.ble.ConfigSnapshot
import space.opensensor.data.ble.ConnectionState
import space.opensensor.data.ble.TransportKind
import space.opensensor.data.history.DeviceCapabilities
import space.opensensor.data.history.TodaySoFar
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.DeviceStatus
import space.opensensor.data.protocol.LiveReading
import space.opensensor.data.protocol.LiveSample
import space.opensensor.designsystem.AppIcons
import space.opensensor.designsystem.BigValue
import space.opensensor.designsystem.BodyText
import space.opensensor.designsystem.BrandCard
import space.opensensor.designsystem.CardHeader
import space.opensensor.designsystem.CardTone
import space.opensensor.designsystem.Eyebrow
import space.opensensor.designsystem.FitText
import space.opensensor.designsystem.InfoContent
import space.opensensor.designsystem.OutlinePillButton
import space.opensensor.designsystem.PeacockExpression
import space.opensensor.designsystem.PeacockPortrait
import space.opensensor.designsystem.PillButton
import space.opensensor.designsystem.StatLine
import space.opensensor.designsystem.StatusDot
import space.opensensor.designsystem.TickingText
import space.opensensor.designsystem.WarningNote
import space.opensensor.designsystem.listSeparator
import space.opensensor.designsystem.nowEpochMillis
import space.opensensor.designsystem.peacockExpressionFor
import space.opensensor.designsystem.pm25CorrectionInfo
import space.opensensor.designsystem.pm25Label
import space.opensensor.designsystem.rememberNowMillis
import space.opensensor.designsystem.spokenList
import space.opensensor.family.air.AirFamily
import space.opensensor.family.air.AirMetrics
import space.opensensor.family.air.NowReading
import space.opensensor.family.air.PlantowerBatchCandidate
import space.opensensor.family.air.Pm25Basis
import space.opensensor.family.air.PmsStatus
import space.opensensor.family.air.pmValid
import space.opensensor.family.air.pmsStatus
import space.opensensor.model.ThingDatastream
import space.opensensor.settings.DetailLevel
import space.opensensor.today.resources.Res
import space.opensensor.today.resources.batch_explain
import space.opensensor.today.resources.batch_title
import space.opensensor.today.resources.batch_value
import space.opensensor.today.resources.batch_waiting
import space.opensensor.today.resources.board_ambient_note
import space.opensensor.today.resources.board_battery_critical
import space.opensensor.today.resources.board_battery_low
import space.opensensor.today.resources.board_battery_ok
import space.opensensor.today.resources.board_battery_settling
import space.opensensor.today.resources.board_charging
import space.opensensor.today.resources.board_discharging
import space.opensensor.today.resources.board_external_power
import space.opensensor.today.resources.board_millivolts
import space.opensensor.today.resources.board_temp
import space.opensensor.today.resources.board_temp_unsupported
import space.opensensor.today.resources.board_title
import space.opensensor.today.resources.board_usb_millivolts
import space.opensensor.today.resources.clock_actions_explain
import space.opensensor.today.resources.clock_generation
import space.opensensor.today.resources.clock_host
import space.opensensor.today.resources.clock_last_sample
import space.opensensor.today.resources.clock_network
import space.opensensor.today.resources.clock_none
import space.opensensor.today.resources.clock_none_explain
import space.opensensor.today.resources.clock_rtc
import space.opensensor.today.resources.clock_set
import space.opensensor.today.resources.clock_title
import space.opensensor.today.resources.clock_unknown
import space.opensensor.today.resources.device_boot
import space.opensensor.today.resources.device_config_read
import space.opensensor.today.resources.device_connected_over
import space.opensensor.today.resources.device_default
import space.opensensor.today.resources.device_dictionary
import space.opensensor.today.resources.device_firmware
import space.opensensor.today.resources.device_id
import space.opensensor.today.resources.device_link
import space.opensensor.today.resources.device_max_read
import space.opensensor.today.resources.device_payload
import space.opensensor.today.resources.device_protocol
import space.opensensor.today.resources.device_schema
import space.opensensor.today.resources.device_schema_cols
import space.opensensor.today.resources.device_thing
import space.opensensor.today.resources.device_title
import space.opensensor.today.resources.device_unnamed
import space.opensensor.today.resources.env_title
import space.opensensor.today.resources.env_unsupported
import space.opensensor.today.resources.flush
import space.opensensor.today.resources.fresh_none
import space.opensensor.today.resources.fresh_stale
import space.opensensor.today.resources.fresh_updated
import space.opensensor.today.resources.now_last_reading
import space.opensensor.today.resources.now_right_now
import space.opensensor.today.resources.now_sample_number
import space.opensensor.today.resources.now_snapshot_note
import space.opensensor.today.resources.now_waiting
import space.opensensor.today.resources.particles_title
import space.opensensor.today.resources.readings_absent
import space.opensensor.today.resources.readings_cf1
import space.opensensor.today.resources.readings_title
import space.opensensor.today.resources.row_groups
import space.opensensor.today.resources.storage_buffered
import space.opensensor.today.resources.storage_codec
import space.opensensor.today.resources.storage_degraded
import space.opensensor.today.resources.storage_dropped
import space.opensensor.today.resources.storage_errors
import space.opensensor.today.resources.storage_failed
import space.opensensor.today.resources.storage_files_this_boot
import space.opensensor.today.resources.storage_full
import space.opensensor.today.resources.storage_heap
import space.opensensor.today.resources.storage_microsd
import space.opensensor.today.resources.storage_minutes
import space.opensensor.today.resources.storage_missed
import space.opensensor.today.resources.storage_none
import space.opensensor.today.resources.storage_not_mounted
import space.opensensor.today.resources.storage_open
import space.opensensor.today.resources.storage_open_row_groups
import space.opensensor.today.resources.storage_partial
import space.opensensor.today.resources.storage_problem
import space.opensensor.today.resources.storage_rotation
import space.opensensor.today.resources.storage_status_age
import space.opensensor.today.resources.storage_title
import space.opensensor.today.resources.storage_uptime
import space.opensensor.today.resources.storage_used_of
import space.opensensor.today.resources.sync_button
import space.opensensor.today.resources.sync_button_running
import space.opensensor.today.resources.sync_caught_up
import space.opensensor.today.resources.sync_empty
import space.opensensor.today.resources.sync_end_bluetooth_off
import space.opensensor.today.resources.sync_end_busy
import space.opensensor.today.resources.sync_end_interrupted
import space.opensensor.today.resources.sync_end_link_lost
import space.opensensor.today.resources.sync_end_no_space
import space.opensensor.today.resources.sync_end_other
import space.opensensor.today.resources.sync_end_out_of_range
import space.opensensor.today.resources.sync_end_pairing
import space.opensensor.today.resources.sync_end_permission
import space.opensensor.today.resources.sync_end_stopped
import space.opensensor.today.resources.sync_end_storage
import space.opensensor.today.resources.sync_end_token_rejected
import space.opensensor.today.resources.sync_end_token_rotated
import space.opensensor.today.resources.sync_end_update_firmware
import space.opensensor.today.resources.sync_end_wifi_access
import space.opensensor.today.resources.sync_estimated_detail
import space.opensensor.today.resources.sync_explain
import space.opensensor.today.resources.sync_ready
import space.opensensor.today.resources.sync_running
import space.opensensor.today.resources.sync_some_failed
import space.opensensor.today.resources.sync_stop
import space.opensensor.today.resources.sync_title
import space.opensensor.today.resources.sync_unplaced_detail
import space.opensensor.today.resources.sync_unplaced_simple
import space.opensensor.today.resources.today_average
import space.opensensor.today.resources.today_none
import space.opensensor.today.resources.today_none_clock
import space.opensensor.today.resources.today_of_the_day
import space.opensensor.today.resources.today_readings_since_midnight
import space.opensensor.today.resources.today_so_far
import space.opensensor.units.UnitFormatter
import kotlin.time.Instant

/** The display units for Today's readings, provided by [TodayScreen] from its `units` parameter. */
internal val LocalTodayUnits = staticCompositionLocalOf { UnitFormatter.Metric }

/** The size bins (label, column) LIVE shows for [reading]'s thing. None without a thing. */
internal fun particleBins(reading: LiveReading?): List<Pair<String, String>> =
    PARTICLE_BINS.filter { (_, column) -> reading?.declares(column) == true }

/** The CF=1 PM1, PM2.5 and PM10 columns, a Developer line on the readings card. */
private val CF1_COLUMNS = listOf("pm1_cf1_ug_m3", AirFamily.PM25_CF1, "pm10_cf1_ug_m3")

private val PARTICLE_BINS = listOf(
    ">0.3" to "particles_gt03_per_01l",
    ">0.5" to "particles_gt05_per_01l",
    ">1" to "particles_gt10_per_01l",
    ">2.5" to "particles_gt25_per_01l",
    ">5" to "particles_gt50_per_01l",
    ">10" to "particles_gt100_per_01l",
)

/** The column's label from the app profile in the app's language. */
@Composable
internal fun ThingDatastream.localLabel(): String = label(Locale.current.language)

/** A temperature in the reader's units, the unit from the column. */
@Composable
private fun TemperatureValue(datastream: ThingDatastream, reading: LiveReading?, modifier: Modifier = Modifier) {
    val units = LocalTodayUnits.current
    val unit = datastream.displayUnit.orEmpty()
    BigValue(datastream.localLabel(), fmt(units.value(reading?.value(datastream.name), unit)), units.unit(unit), modifier)
}

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
 * The hero of Today: PM2.5 right now, corrected when the live RH is valid and raw otherwise, as a peacock, a big number, the mood in words and
 * one line of advice. The sensor's own state comes before any number. Colour is never the only
 * carrier, the mood is also a word and a character expression.
 */
@Composable
internal fun MetricHeroCard(
    live: LiveReading?,
    receivedAtEpochMs: Long?,
    level: DetailLevel,
    modifier: Modifier = Modifier,
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
                    info = InfoContent(title, stringResource(Res.string.now_snapshot_note), stringResource(nowInfoBodyRes(level)), pm25CorrectionInfo()),
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
                            PmsStatus.STALE, PmsStatus.SLEEPING -> PeacockExpression.SLEEPING
                            PmsStatus.ABSENT, PmsStatus.ERROR, PmsStatus.MODEL_MISMATCH -> PeacockExpression.CONCERNED
                            PmsStatus.VALID -> PeacockExpression.CONFUSED
                        }
                    },
                    size = portraitSize,
                )
                Spacer(Modifier.width(14.dp))
                when (reading) {
                    // Value, unit and metric name read as one element ("12, µg/m³, PM2.5").
                    is NowReading.Reading -> Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                        val value = fmtPm(reading.primary)
                        FitText(
                            value,
                            style = if (value.length > 2 || portraitSize < 176.dp) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayLarge,
                            color = if (fresh) onCard else quiet,
                            maxLines = 1,
                        )
                        FitText("${pm25Label(reading.basis)}  ${AirMetrics.PM25.unit}", style = MaterialTheme.typography.titleMedium, color = quiet)
                        // Detailed shows the sensor's own value second, under the corrected one.
                        val raw = reading.raw
                        if (level.detailed && reading.basis == Pm25Basis.CORRECTED && raw != null) {
                            FitText("${pm25Label(Pm25Basis.RAW)}  ${fmtPm(raw)}", style = MaterialTheme.typography.bodyMedium, color = quiet)
                        }
                    }
                    NowReading.Waiting -> BodyText(
                        stringResource(Res.string.now_waiting),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                        heading = true,
                    )
                    is NowReading.SensorNotReady -> Column(Modifier.weight(1f)) {
                        BodyText(reading.status.titleText(), style = MaterialTheme.typography.headlineSmall, heading = true)
                        BodyText(reading.status.hintText(), style = MaterialTheme.typography.bodyMedium, color = quiet)
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
                    BodyText(reading.mood.comparisonText(), style = MaterialTheme.typography.bodySmall, color = quiet, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                BodyText(stringResource(heroLineRes(reading.mood, level)), style = MaterialTheme.typography.bodyLarge)
            }
            if (level.developer) {
                live?.sample?.seq?.let {
                    Text(stringResource(Res.string.now_sample_number, it), style = MaterialTheme.typography.labelSmall, color = quiet, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

/**
 * Temperature (and humidity where the profile shows it). The hero on a device without a particle
 * sensor, and a second card under the air on an ambient-sensing board. While `INFO` is pending it
 * waits quietly. Only a dictionary the app does not bundle says that live values are not supported,
 * never a missing or null value.
 */
@Composable
internal fun EnvironmentCard(
    support: LiveSupport,
    capabilities: DeviceCapabilities,
    live: LiveReading?,
    receivedAtEpochMs: Long?,
    hero: Boolean,
    modifier: Modifier = Modifier,
) {
    BrandCard(modifier = modifier, tone = if (hero) CardTone.SOFT else CardTone.PLAIN) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(stringResource(Res.string.env_title), modifier = Modifier.weight(1f), heading = true)
            FreshnessLabel(receivedAtEpochMs)
        }
        Spacer(Modifier.height(12.dp))
        when (support) {
            LiveSupport.PENDING -> {
                Text(stringResource(Res.string.now_waiting), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@BrandCard
            }
            LiveSupport.UNSUPPORTED -> {
                Text(stringResource(Res.string.env_unsupported), style = MaterialTheme.typography.bodyMedium)
                return@BrandCard
            }
            LiveSupport.READY -> Unit
        }
        val temperature = capabilities.temperature
        val humidity = capabilities.humidity
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            temperature?.let { TemperatureValue(it, live, Modifier.weight(1f)) }
            humidity?.let { BigValue(it.localLabel(), fmt(live?.value(it.name)), it.displayUnit.orEmpty(), Modifier.weight(1f)) }
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
        today?.let { FitText(it.metric.basis?.let { basis -> pm25Label(basis) } ?: it.metric.label(Locale.current.language), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
                Text(stringResource(Res.string.today_average, todayMean(today), LocalTodayUnits.current.unit(today.metric.unit)), style = MaterialTheme.typography.titleLarge)
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
internal fun SmartSyncCard(
    sync: SyncProgress,
    archive: TodayArchive,
    enabled: Boolean,
    level: DetailLevel,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
    onStop: (() -> Unit)? = null,
) {
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
                        SyncMood.STOPPED, SyncMood.FAILED -> stringResource((syncEndLine(sync, level) ?: SyncEndLine.OTHER).text)
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
        // The one Sync of this thing. Stop sits beside it while this phone's own run is going.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton(
                text = stringResource(if (sync.running) Res.string.sync_button_running else Res.string.sync_button),
                onClick = onSync,
                enabled = enabled && !sync.running,
                modifier = Modifier.weight(1f),
            )
            if (onStop != null) OutlinePillButton(stringResource(Res.string.sync_stop), onClick = onStop)
        }
    }
}

private val SyncEndLine.text: StringResource
    get() = when (this) {
        SyncEndLine.STOPPED -> Res.string.sync_end_stopped
        SyncEndLine.INTERRUPTED -> Res.string.sync_end_interrupted
        SyncEndLine.BLUETOOTH_OFF -> Res.string.sync_end_bluetooth_off
        SyncEndLine.PERMISSION_MISSING -> Res.string.sync_end_permission
        SyncEndLine.PAIRING_REPAIR -> Res.string.sync_end_pairing
        SyncEndLine.WIFI_ACCESS -> Res.string.sync_end_wifi_access
        SyncEndLine.TOKEN_ROTATED -> Res.string.sync_end_token_rotated
        SyncEndLine.TOKEN_REJECTED -> Res.string.sync_end_token_rejected
        SyncEndLine.DEVICE_BUSY -> Res.string.sync_end_busy
        SyncEndLine.OUT_OF_RANGE -> Res.string.sync_end_out_of_range
        SyncEndLine.UPDATE_FIRMWARE -> Res.string.sync_end_update_firmware
        SyncEndLine.NO_SPACE -> Res.string.sync_end_no_space
        SyncEndLine.STORAGE_ERROR -> Res.string.sync_end_storage
        SyncEndLine.LINK_LOST -> Res.string.sync_end_link_lost
        SyncEndLine.OTHER -> Res.string.sync_end_other
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
internal fun BatchEstimateCard(live: LiveReading?, receivedAtEpochMs: Long?, config: ConfigSnapshot?, modifier: Modifier = Modifier) {
    val fresh = rememberIsFresh(receivedAtEpochMs) && receivedAtEpochMs != null && receivedAtEpochMs >= (config?.atEpochMs ?: Long.MAX_VALUE)
    val estimate = if (fresh) {
        PlantowerBatchCandidate.estimate(
            config?.config?.sensor?.serial,
            config?.config?.sensor?.batchCandidate,
            live?.pmsStatus?.code,
            live?.value(AirMetrics.PARTICLES_GT03.column)?.toLong(),
        )
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
internal fun ReadingsCard(live: LiveReading?, receivedAtEpochMs: Long?, level: DetailLevel, modifier: Modifier = Modifier) {
    val showPm = live?.pmValid == true
    BrandCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow(stringResource(Res.string.readings_title), modifier = Modifier.weight(1f), heading = true)
            FreshnessLabel(receivedAtEpochMs)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            BigValue(AirMetrics.PM10.label, if (showPm) fmt(live.value(AirMetrics.PM10.column)) else NO_VALUE, AirMetrics.PM10.unit, Modifier.weight(1f))
            BigValue(AirMetrics.PM1.label, if (showPm) fmt(live.value(AirMetrics.PM1.column)) else NO_VALUE, AirMetrics.PM1.unit, Modifier.weight(1f))
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
        val cf1 = CF1_COLUMNS.map { live?.value(it) }
        if (level.developer && showPm && cf1.any { it != null }) {
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(Res.string.readings_cf1, fmt(cf1[0]), fmt(cf1[1]), fmt(cf1[2])),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Detailed level: particle counts per 0.1 L by size bin, only the bins the board's field table has. */
@Composable
internal fun ParticlesCard(live: LiveReading?, modifier: Modifier = Modifier) {
    val counts = particleBins(live).map { (label, column) -> label to live?.value(column)?.toLong() }
    BrandCard(modifier = modifier) {
        // Not uppercased. "µ".uppercase() is the Greek capital mu, which once rendered as "(MM)".
        Eyebrow(stringResource(Res.string.particles_title), uppercase = false, heading = true)
        Spacer(Modifier.height(8.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            counts.forEach { (label, n) ->
                // The count and its size bin read as one element.
                Column(Modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(n?.toString() ?: NO_VALUE, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    FitText(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Detailed level: the board's own temperature and the battery, both only when the thing has them.
 * The battery columns come from [BoardPower] by observed property. The battery state (CoreS3
 * `charging_status`, Waveshare `bstate`) is a word beside the icon, never colour alone. Millivolts
 * and USB voltage are Developer facts. Before `INFO` ([LiveSupport.PENDING]) the note waits quietly
 * and never says unsupported (#152).
 */
@Composable
internal fun BoardCard(
    support: LiveSupport,
    live: LiveReading?,
    capabilities: DeviceCapabilities,
    power: BoardPower,
    showTemperature: Boolean,
    level: DetailLevel,
    modifier: Modifier = Modifier,
) {
    val temperature = capabilities.temperature
    BrandCard(modifier = modifier) {
        Eyebrow(stringResource(Res.string.board_title), heading = true)
        Spacer(Modifier.height(8.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showTemperature) {
                Column(Modifier.weight(1f)) {
                    if (temperature != null) {
                        TemperatureValue(temperature, live)
                    } else {
                        BigValue(AirMetrics.BOARD_TEMP.label, NO_VALUE, LocalTodayUnits.current.unit(AirMetrics.BOARD_TEMP.unit))
                    }
                    BodyText(
                        stringResource(
                            when {
                                support == LiveSupport.PENDING -> Res.string.now_waiting
                                temperature == null -> Res.string.board_temp_unsupported
                                capabilities.ambientTemperature -> Res.string.board_ambient_note
                                else -> Res.string.board_temp
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            power.battery?.let { battery -> BatteryValue(battery, power, live, level, Modifier.weight(1f)) }
        }
    }
}

/** Battery label, percent and state, read as one element. */
@Composable
private fun BatteryValue(battery: ThingDatastream, power: BoardPower, live: LiveReading?, level: DetailLevel, modifier: Modifier = Modifier) {
    val state = power.stateOf(live)
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Eyebrow(battery.localLabel(), uppercase = false)
        Row(verticalAlignment = Alignment.Bottom) {
            Icon(
                if (state?.charging == true) AppIcons.BatteryChargingFull else AppIcons.BatteryStd,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(live?.value(battery.name)?.toLong()?.let { "$it" } ?: NO_VALUE, style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.width(4.dp))
            FitText(
                battery.displayUnit.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 7.dp),
                maxLines = 1,
            )
        }
        val stateText = state?.let { batteryStateText(it) }
        val millivolts = power.millivolts?.let { live?.value(it.name) }?.toInt()?.let { stringResource(Res.string.board_millivolts, it) }
        val usb = power.usbMillivolts?.let { live?.value(it.name) }?.toInt()?.takeIf { it > 0 }?.let { stringResource(Res.string.board_usb_millivolts, it) }
        val detail = buildList {
            stateText?.let(::add)
            if (level.developer) {
                millivolts?.let(::add)
                usb?.let(::add)
            }
        }
        if (detail.isNotEmpty()) {
            BodyText(spokenList(detail, listSeparator()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The battery state in words. A class the app does not know shows the template's own name. */
@Composable
private fun batteryStateText(state: BatteryState): String = when (state.kind) {
    BatteryState.Kind.CHARGING -> stringResource(Res.string.board_charging)
    BatteryState.Kind.DISCHARGING -> stringResource(Res.string.board_discharging)
    BatteryState.Kind.EXTERNAL_POWER -> stringResource(Res.string.board_external_power)
    BatteryState.Kind.OK -> stringResource(Res.string.board_battery_ok)
    BatteryState.Kind.LOW -> stringResource(Res.string.board_battery_low)
    BatteryState.Kind.CRITICAL -> stringResource(Res.string.board_battery_critical)
    BatteryState.Kind.SETTLING -> stringResource(Res.string.board_battery_settling)
    BatteryState.Kind.OTHER -> state.name
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
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    // The number and its label read as one element. A problem count also gets a warning icon, so
    // colour is never the only cue.
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (color != null) {
                Icon(AppIcons.Warning, contentDescription = stringResource(Res.string.storage_problem), tint = color, modifier = Modifier.size(18.dp))
            }
            Text(value?.toString() ?: NO_VALUE, style = MaterialTheme.typography.titleLarge, color = color ?: MaterialTheme.colorScheme.onSurface)
        }
        FitText(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Today's mean in the reader's units. PM2.5 keeps its own rounding. */
@Composable
private fun todayMean(today: TodaySoFar): String =
    if (today.metric.basis != null) {
        fmtPm(today.primaryMean.toDouble())
    } else {
        fmt(LocalTodayUnits.current.value(today.primaryMean.toDouble(), today.metric.unit))
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
            StatLine(stringResource(Res.string.device_thing), info?.thing ?: NO_VALUE)
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
