package space.opensensor.files

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Files groups and labels its times in the zone it reads on each resume, so a zone changed in the
 * system settings shows on return (#315). Rendered headless on the iOS simulator (verifyIos).
 */
@OptIn(ExperimentalTestApi::class)
class SystemTimeZoneTest {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun theZoneIsReadAgainWhenTheScreenResumes() = runComposeUiTest {
        val owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
        var system = TimeZone.of("Africa/Accra")
        var shown: TimeZone? = null
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                shown = rememberSystemTimeZone { system }
            }
        }
        waitForIdle()
        assertEquals("Africa/Accra", shown?.id)
        // The owner leaves for the system settings, changes the zone and comes back.
        system = TimeZone.of("Africa/Cairo")
        runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        waitForIdle()
        assertEquals("Africa/Accra", shown?.id, "nothing changes while the app is away")
        runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        waitForIdle()
        assertEquals("Africa/Cairo", shown?.id)
    }
}
