package earth.walkthru.aqsync.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.airquality.WhoStep
import earth.walkthru.aqsync.data.history.AirMood
import earth.walkthru.aqsync.data.protocol.PmsStatus

/**
 * App copy for the shared moods and sensor states. The WHO edges stay in `:core:airquality`, only the
 * words come from `strings.xml`, so they follow the app's copy rules and can be translated.
 */
@Composable
fun AirMood.titleText(): String = stringResource(
    when (this) {
        AirMood.FRESH -> R.string.mood_fresh_title
        AirMood.FAIR -> R.string.mood_fair_title
        AirMood.HAZY -> R.string.mood_hazy_title
        AirMood.POOR -> R.string.mood_poor_title
        AirMood.SMOKY -> R.string.mood_smoky_title
        AirMood.THICK -> R.string.mood_thick_title
    },
)

@Composable
fun AirMood.adviceText(): String = stringResource(
    when (this) {
        AirMood.FRESH -> R.string.mood_fresh_advice
        AirMood.FAIR -> R.string.mood_fair_advice
        AirMood.HAZY -> R.string.mood_hazy_advice
        AirMood.POOR -> R.string.mood_poor_advice
        AirMood.SMOKY -> R.string.mood_smoky_advice
        AirMood.THICK -> R.string.mood_thick_advice
    },
)

@Composable
fun AirMood.comparisonText(): String = stringResource(
    when (this) {
        AirMood.FRESH -> R.string.mood_fresh_like
        AirMood.FAIR -> R.string.mood_fair_like
        AirMood.HAZY -> R.string.mood_hazy_like
        AirMood.POOR -> R.string.mood_poor_like
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

/** The WHO step in words, for readouts where colour must not be the only carrier. */
@Composable
fun WhoStep.labelText(): String = stringResource(
    when (this) {
        WhoStep.GUIDELINE -> R.string.who_step_guideline
        WhoStep.IT4 -> R.string.who_step_it4
        WhoStep.IT3 -> R.string.who_step_it3
        WhoStep.IT2 -> R.string.who_step_it2
        WhoStep.IT1 -> R.string.who_step_it1
        WhoStep.ABOVE_IT1 -> R.string.who_step_above_it1
    },
)
