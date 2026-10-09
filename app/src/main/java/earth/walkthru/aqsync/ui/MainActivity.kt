package earth.walkthru.aqsync.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import earth.walkthru.aqsync.AppLanguage
import earth.walkthru.aqsync.data.settings.AppSettings
import earth.walkthru.aqsync.ui.components.LocalUnits
import earth.walkthru.aqsync.ui.components.resolveUnits
import earth.walkthru.aqsync.units.UnitFormatter
import earth.walkthru.aqsync.units.UnitSystem
import earth.walkthru.opensensor.designsystem.OpenSensorTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: AppSettings

    // The language `res/` resolved leads the configuration, so layout direction, Android strings and
    // the shared Compose strings agree (AppLanguage).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
            )
        )
        super.onCreate(savedInstanceState)
        setContent {
            // Display units for every screen, live from Settings. A locale change recreates the
            // activity, so Automatic re-resolves with it.
            val choice by settings.unitSystem.collectAsStateWithLifecycle(UnitSystem.AUTO)
            val units = remember(choice) { UnitFormatter(resolveUnits(choice)) }
            CompositionLocalProvider(LocalUnits provides units) {
                OpenSensorTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        // No permission wall. Each action asks for what it needs (ui/permissions).
                        MainNavigation()
                    }
                }
            }
        }
    }
}
