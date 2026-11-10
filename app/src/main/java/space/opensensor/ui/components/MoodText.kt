package space.opensensor.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import space.opensensor.app.R
import space.opensensor.family.air.WhoStep

/**
 * The WHO step in words, for readouts where colour must not be the only carrier. The WHO edges
 * stay in `:family:air`, only the words come from `strings.xml`, so they follow the app's copy
 * rules and can be translated.
 */
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
