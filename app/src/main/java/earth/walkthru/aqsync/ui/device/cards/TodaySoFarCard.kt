package earth.walkthru.aqsync.ui.device.cards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.history.TodaySoFar
import earth.walkthru.aqsync.data.settings.DetailLevel
import earth.walkthru.aqsync.ui.components.MoodFace
import earth.walkthru.aqsync.ui.components.titleText
import earth.walkthru.aqsync.ui.theme.color
import earth.walkthru.opensensor.designsystem.BrandCard
import earth.walkthru.opensensor.designsystem.CardTone
import earth.walkthru.opensensor.designsystem.Eyebrow

/**
 * Today so far: the local day's real average from the phone archive, with how much of the day it
 * covers. Without timed readings it says what to do next instead of showing a number.
 */
@Composable
fun TodaySoFarCard(today: TodaySoFar?, clockMissing: Boolean, level: DetailLevel, modifier: Modifier = Modifier) {
    BrandCard(modifier = modifier, tone = CardTone.SOFT) {
        Eyebrow(stringResource(R.string.today_so_far))
        Spacer(Modifier.height(8.dp))
        if (today == null) {
            Text(
                stringResource(if (clockMissing) R.string.today_none_clock else R.string.today_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@BrandCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            MoodFace(today.mood, today.mood.color(), Modifier.size(40.dp), description = null)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(today.mood.titleText(), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.today_average, fmtPm(today.pm25Mean.toDouble())),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${(today.coverage * 100).toInt()} %", style = MaterialTheme.typography.displaySmall)
                Text(stringResource(R.string.today_of_the_day), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (level.detailed) {
            Spacer(Modifier.height(6.dp))
            Text(
                pluralStringResource(R.plurals.today_readings_since_midnight, today.validRows, today.validRows),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
