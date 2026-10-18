package earth.walkthru.opensensor.designsystem

import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Android capture guard sets `FLAG_SECURE` while a credential field is composed and clears it
 * when the last one leaves, so screenshots work again in the rest of the app (#160, #170).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CredentialCaptureAndroidTest {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    private val secure: Boolean
        get() = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    private fun settle() {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    @Test
    fun flagSecureStaysOnUntilTheLastCredentialFieldLeaves() {
        var pin by mutableStateOf(true)
        var passphrase by mutableStateOf(false)
        val captured = mutableListOf<Boolean>()
        activity.setContent {
            if (pin) captured += rememberCredentialCaptureGuard(onScreenshot = {})
            if (passphrase) captured += rememberCredentialCaptureGuard(onScreenshot = {})
        }
        settle()
        assertTrue(secure, "the PIN field turns FLAG_SECURE on")

        passphrase = true
        settle()
        pin = false
        settle()
        assertTrue(secure, "the passphrase field still holds it")

        passphrase = false
        settle()
        assertFalse(secure, "the last field leaving clears FLAG_SECURE")

        pin = true
        settle()
        assertTrue(secure, "a field shown again turns it back on")
        assertTrue(captured.none { it }, "FLAG_SECURE blanks captures, so the field never forces its mask")
    }

    @Test
    fun leavingTheScreenClearsFlagSecure() {
        activity.setContent { rememberCredentialCaptureGuard(onScreenshot = {}) }
        settle()
        assertTrue(secure)
        activity.setContent { }
        settle()
        assertFalse(secure)
    }
}
