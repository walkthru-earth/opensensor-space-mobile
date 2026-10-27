package space.opensensor.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import space.opensensor.app.R
import space.opensensor.family.air.PmsStatus
import space.opensensor.family.air.WhoStep

/**
 * App copy for sensor states and the WHO step names. The WHO edges stay in `:family:air`, only
 * the words come from `strings.xml`, so they follow the app's copy rules and can be translated. The
 * mood words and lines live in `:ui:designsystem`.
 */
@Composable
fun PmsStatus.titleText(): String = stringResource(
    when (this) {
        PmsStatus.ABSENT -> R.string.pms_absent_title
        PmsStatus.WARMING -> R.string.pms_warming_title
        PmsStatus.STALE -> R.string.pms_stale_title
        PmsStatus.ERROR -> R.string.pms_error_title
        PmsStatus.MODEL_MISMATCH -> R.string.pms_mismatch_title
        PmsStatus.SLEEPING -> R.string.pms_sleeping_title
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
