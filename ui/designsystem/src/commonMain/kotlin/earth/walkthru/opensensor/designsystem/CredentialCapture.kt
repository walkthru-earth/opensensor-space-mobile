package earth.walkthru.opensensor.designsystem

import androidx.compose.runtime.Composable

/**
 * Keeps credential entry (the fixed PIN, the Wi-Fi passphrase) out of screen captures while the
 * calling field is in composition. Android sets `FLAG_SECURE` on the activity window, which blanks
 * screenshots, recordings and the recents thumbnail. iOS covers the scene before the app switcher
 * snapshot and reports a running recording or mirroring so the field forces its mask on. iOS offers
 * no supported API that blocks a still screenshot.
 *
 * Returns true while the platform says the screen is being captured right now.
 */
@Composable
expect fun rememberCredentialCaptureGuard(): Boolean

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
