package earth.walkthru.aqsync.ui.device.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.history.MetricSpec
import earth.walkthru.aqsync.data.history.Metrics
import earth.walkthru.aqsync.data.history.NowReading
import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.data.settings.DetailLevel
import earth.walkthru.aqsync.ui.components.BrandCard
import earth.walkthru.aqsync.ui.components.CardHeader
import earth.walkthru.aqsync.ui.components.CardTone
import earth.walkthru.aqsync.ui.components.InfoContent
import earth.walkthru.aqsync.ui.components.MoodFace
import earth.walkthru.aqsync.ui.components.MoodPill
import earth.walkthru.aqsync.ui.components.TiltedBadge
import earth.walkthru.aqsync.ui.components.adviceText
import earth.walkthru.aqsync.ui.components.comparisonText
import earth.walkthru.aqsync.ui.components.hintText
import earth.walkthru.aqsync.ui.components.titleText
import earth.walkthru.aqsync.ui.theme.color
import kotlinx.coroutines.delay

/**
 * True while a reading received at [receivedAtEpochMs] is younger than [STALE_AFTER_MS]. Flips once
 * at the exact moment it goes stale instead of ticking every second, so the card around it only
 * recomposes when the answer changes.
 */
@Composable
fun rememberIsFresh(receivedAtEpochMs: Long?): Boolean {
    val fresh by produceState(
        initialValue = receivedAtEpochMs != null && System.currentTimeMillis() - receivedAtEpochMs <= STALE_AFTER_MS,
        receivedAtEpochMs,
    ) {
        if (receivedAtEpochMs == null) {
            value = false
            return@produceState
        }
        val remaining = receivedAtEpochMs + STALE_AFTER_MS - System.currentTimeMillis()
        value = remaining >= 0
        if (remaining >= 0) {
            delay(remaining + 1)
            value = false
        }
    }
    return fresh
}

/**
 * The hero of Today: the primary metric right now as a mood face, a big number, the mood in words and one
 * line of advice. The sensor's own state comes before any number. The number is a 10-second
 * snapshot and says so. Colour is never the only carrier: the mood is also a word and a face, and
 * the number is set in the card's text colour while the band colour sits on the face and the pill.
 */
@Composable
fun MetricHeroCard(
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

    BrandCard(modifier = modifier, tone = CardTone.HERO) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val title = stringResource(if (fresh) R.string.now_right_now else R.string.now_last_reading)
            CardHeader(
                title,
                modifier = Modifier.weight(1f),
                info = InfoContent(title, stringResource(R.string.now_snapshot_note), stringResource(R.string.info_now_body)),
                color = quiet,
            )
            FreshnessLabel(receivedAtEpochMs, color = quiet)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TiltedBadge(color = MaterialTheme.colorScheme.surfaceContainerLowest, size = 92.dp) {
                MoodFace(mood, mood?.color() ?: MaterialTheme.colorScheme.outlineVariant, Modifier.size(66.dp), description = mood?.titleText() ?: stringResource(R.string.now_no_reading))
            }
            Spacer(Modifier.width(20.dp))
            when (reading) {
                is NowReading.Reading -> Column(Modifier.weight(1f)) {
                    Row {
                        Text(
                            fmtPm(reading.pm25),
                            style = MaterialTheme.typography.displayLarge,
                            color = if (fresh) onCard else quiet,
                            maxLines = 1,
                            modifier = Modifier.alignByBaseline(),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(metric.unit, style = MaterialTheme.typography.titleMedium, color = quiet, modifier = Modifier.alignByBaseline())
                    }
                    Text(metric.label, style = MaterialTheme.typography.labelLarge, color = quiet)
                }
                NowReading.Waiting -> Text(
                    stringResource(R.string.now_waiting),
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MoodPill(reading.mood)
                Text(reading.mood.comparisonText(), style = MaterialTheme.typography.bodySmall, color = quiet, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Text(reading.mood.adviceText(), style = MaterialTheme.typography.bodyLarge)
        }
        if (level.developer) {
            live?.seq?.let {
                Text(stringResource(R.string.now_sample_number, it), style = MaterialTheme.typography.labelSmall, color = quiet, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
