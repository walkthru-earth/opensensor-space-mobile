package earth.walkthru.aqsync.ui.settings

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import earth.walkthru.aqsync.BuildConfig
import earth.walkthru.aqsync.ui.components.InfoButton
import earth.walkthru.aqsync.ui.components.InfoContent
import earth.walkthru.aqsync.ui.components.resolveUnits
import earth.walkthru.aqsync.units.UnitSystem
import earth.walkthru.opensensor.settings.SettingsScreen as SharedSettingsScreen

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
