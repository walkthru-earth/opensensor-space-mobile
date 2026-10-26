package space.opensensor.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Keeps credential entry (the fixed PIN, the Wi-Fi passphrase) out of screen captures while the
 * calling field is in composition. Android sets `FLAG_SECURE` on the activity window, which blanks
 * screenshots, recordings and the recents thumbnail. iOS covers the scene before the app switcher
 * snapshot and reports a running recording or mirroring so the field forces its mask on. iOS offers
 * no supported API that blocks a still screenshot, so it calls [onScreenshot] right after one is
 * taken and the field hides a revealed secret again (#164). Android never calls it.
 *
 * Returns true while the platform says the screen is being captured right now.
 */
@Composable
fun rememberCredentialCaptureGuard(onScreenshot: () -> Unit): Boolean =
    LocalCredentialCaptureGuard.current.guard(onScreenshot)

/** The capture protection behind [rememberCredentialCaptureGuard], one per platform. */
interface CredentialCaptureGuard {
    /** Protects the screen while the caller is in composition. See [rememberCredentialCaptureGuard]. */
    @Composable
    fun guard(onScreenshot: () -> Unit): Boolean
}

/** The platform guard. Tests provide a fake that records each field and plays captures. */
val LocalCredentialCaptureGuard = staticCompositionLocalOf<CredentialCaptureGuard> { PlatformCredentialCaptureGuard }

/** `FLAG_SECURE` on Android, the scene cover, capture state and screenshot notice on iOS. */
internal expect object PlatformCredentialCaptureGuard : CredentialCaptureGuard

/**
 * Counts the credential fields on screen, so the platform protection turns on with the first one
 * and stays on until the last one leaves. Main thread only, like composition.
 */
class CaptureGuardCount {
    var holders: Int = 0
        private set

    /** Adds one holder. True when it is the first, so the protection turns on. */
    fun acquire(): Boolean = ++holders == 1

    /** Removes one holder. True when it was the last, so the protection turns off. */
    fun release(): Boolean {
        if (holders == 0) return false
        return --holders == 0
    }
}
