package earth.walkthru.aqsync.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.history.AirMood
import earth.walkthru.aqsync.data.protocol.PmsStatus

/**
 * App copy for the shared moods and sensor states. The band edges stay in `AirMood`; only the
 * words come from `strings.xml`, so they follow the app's copy rules and can be translated.
 */
@Composable
fun AirMood.titleText(): String = stringResource(
    when (this) {
        AirMood.FRESH -> R.string.mood_fresh_title
        AirMood.FINE -> R.string.mood_fine_title
        AirMood.HAZY -> R.string.mood_hazy_title
        AirMood.SMOKY -> R.string.mood_smoky_title
        AirMood.THICK -> R.string.mood_thick_title
    },
)

@Composable
fun AirMood.adviceText(): String = stringResource(
    when (this) {
        AirMood.FRESH -> R.string.mood_fresh_advice
        AirMood.FINE -> R.string.mood_fine_advice
        AirMood.HAZY -> R.string.mood_hazy_advice
        AirMood.SMOKY -> R.string.mood_smoky_advice
        AirMood.THICK -> R.string.mood_thick_advice
    },
)

@Composable
fun AirMood.comparisonText(): String = stringResource(
    when (this) {
        AirMood.FRESH -> R.string.mood_fresh_like
        AirMood.FINE -> R.string.mood_fine_like
        AirMood.HAZY -> R.string.mood_hazy_like
        AirMood.SMOKY -> R.string.mood_smoky_like
        AirMood.THICK -> R.string.mood_thick_like
    },
)

@Composable
fun PmsStatus.titleText(): String = stringResource(
    when (this) {
        PmsStatus.ABSENT -> R.string.pms_absent_title
        PmsStatus.WARMING -> R.string.pms_warming_title
        PmsStatus.STALE -> R.string.pms_stale_title
        PmsStatus.ERROR -> R.string.pms_error_title
        PmsStatus.VALID -> R.string.pms_valid_title
    },
)

@Composable
fun PmsStatus.hintText(): String = stringResource(
    when (this) {
        PmsStatus.ABSENT -> R.string.pms_absent_hint
        PmsStatus.WARMING -> R.string.pms_warming_hint
        PmsStatus.STALE -> R.string.pms_stale_hint
        PmsStatus.ERROR -> R.string.pms_error_hint
        PmsStatus.VALID -> R.string.pms_valid_title
    },
)
