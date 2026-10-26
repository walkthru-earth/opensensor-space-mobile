package space.opensensor.ui.settings

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import space.opensensor.app.BuildConfig
import space.opensensor.designsystem.InfoButton
import space.opensensor.designsystem.InfoContent
import space.opensensor.ui.components.resolveUnits
import space.opensensor.units.UnitSystem
import space.opensensor.settings.SettingsScreen as SharedSettingsScreen

/**
 * The Settings tab. The screen and its copy are shared with iOS (`:feature:settings`). Android
 * supplies the version name, what Automatic units resolve to and its own info button.
 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    SharedSettingsScreen(
        controller = viewModel.controller,
        appVersion = BuildConfig.VERSION_NAME,
        automaticUnits = resolveUnits(UnitSystem.AUTO),
        infoButton = { title, body, modifier -> InfoButton(InfoContent(title, body), modifier) },
    )
}
