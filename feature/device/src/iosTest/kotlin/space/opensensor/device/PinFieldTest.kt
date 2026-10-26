package space.opensensor.device

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationUserDidTakeScreenshotNotification
import space.opensensor.data.WifiScanState
import space.opensensor.data.ble.ConnectionState
import space.opensensor.data.ble.TransportKind
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.Frame
import space.opensensor.designsystem.CredentialCaptureGuard
import space.opensensor.designsystem.LocalCredentialCaptureGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The fixed PIN never shows on screen unless asked, keeps password semantics for TalkBack and
 * VoiceOver, and is dropped when Fixed PIN is left (#160). The Wi-Fi passphrase also reopens hidden.
 * Both fields hold the capture guard while shown, mask while the screen is captured and hide again
 * after a screenshot (#164, #170). Rendered headless on the iOS simulator.
 */
@OptIn(ExperimentalTestApi::class)
class PinFieldTest {
    private val state = DeviceConfigUiState(
        DeviceConfigSnapshot(
            address = "AA",
            connection = ConnectionState.Ready(247),
            transport = TransportKind.BLE,
            info = DeviceInfo(proto = 3, schema = "waveshare-sim7670g-telemetry-v4"),
        ),
    )

    private val scanned = state.copy(
        snapshot = state.snapshot.copy(
            wifiScan = WifiScanState.Done(listOf(Frame.WifiAp(rssi = -50, auth = 3, channel = 6, ssid = "home")), atEpochMs = 1),
        ),
    )

    /** Stands in for the platform guard. Counts the fields holding it and plays captures and screenshots. */
    private class FakeGuard : CredentialCaptureGuard {
        var holders = 0
        var captured by mutableStateOf(false)
        private val handlers = mutableListOf<() -> Unit>()

        fun screenshot() = handlers.toList().forEach { it() }

        @Composable
        override fun guard(onScreenshot: () -> Unit): Boolean {
            val latest by rememberUpdatedState(onScreenshot)
            DisposableEffect(Unit) {
                val handler = { latest() }
                holders++
                handlers += handler
                onDispose {
                    holders--
                    handlers -= handler
                }
            }
            return captured
        }
    }

    private fun ComposeUiTest.showCard(guard: CredentialCaptureGuard? = null) {
        setContent {
            MaterialTheme {
                if (guard == null) {
                    PairingCard(state, onSave = {}, onReboot = {})
                } else {
                    CompositionLocalProvider(LocalCredentialCaptureGuard provides guard) { PairingCard(state, onSave = {}, onReboot = {}) }
                }
            }
        }
        onNodeWithText("Fixed PIN").performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    private fun ComposeUiTest.showJoinForm(guard: CredentialCaptureGuard) {
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalCredentialCaptureGuard provides guard) {
                    WifiCard(scanned, onToggleWifi = {}, onScan = {}, onClearScan = {}, onJoin = {})
                }
            }
        }
        onNodeWithText("home").performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    private fun ComposeUiTest.pinField(): SemanticsNodeInteraction = onNode(hasSetTextAction())

    private fun SemanticsNodeInteraction.shownText(): String =
        fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.EditableText) { null }?.text.orEmpty()

    private fun SemanticsNodeInteraction.isPassword(): Boolean =
        SemanticsProperties.Password in fetchSemanticsNode().config

    @Test
    fun theTypedPinIsMaskedAndReadAsAPassword() = runComposeUiTest {
        showCard()
        pinField().performTextInput(SECRET_PIN)
        val shown = pinField().shownText()
        assertEquals(SECRET_PIN.length, shown.length)
        assertTrue(SECRET_PIN.none { it in shown }, "no digit of the PIN is on screen")
        assertTrue(pinField().isPassword())
    }

    @Test
    fun showRevealsThePinAndItStaysAPasswordForScreenReaders() = runComposeUiTest {
        showCard()
        pinField().performTextInput(SECRET_PIN)
        onNodeWithContentDescription("Show PIN").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(SECRET_PIN, pinField().shownText())
        assertTrue(pinField().isPassword())
        onNodeWithContentDescription("Hide PIN").performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(SECRET_PIN.none { it in pinField().shownText() })
    }

    @Test
    fun leavingFixedPinDropsTheTypedPinAndHidesItAgain() = runComposeUiTest {
        showCard()
        pinField().performTextInput(SECRET_PIN)
        onNodeWithContentDescription("Show PIN").performSemanticsAction(SemanticsActions.OnClick)
        onNodeWithText("Random code").performSemanticsAction(SemanticsActions.OnClick)
        onNodeWithText("Fixed PIN").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("", pinField().shownText())
        // Masked again: typing after coming back shows dots, not digits.
        pinField().performTextInput("1")
        assertEquals("•", pinField().shownText())
    }

    @Test
    fun aReopenedWifiJoinFormStartsEmptyAndHidden() = runComposeUiTest {
        setContent { MaterialTheme { WifiCard(scanned, onToggleWifi = {}, onScan = {}, onClearScan = {}, onJoin = {}) } }
        onNodeWithText("home").performSemanticsAction(SemanticsActions.OnClick)
        pinField().performTextInput(SECRET_PSK)
        onNodeWithContentDescription("Show passphrase").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(SECRET_PSK, pinField().shownText())
        onNodeWithText("Cancel").performSemanticsAction(SemanticsActions.OnClick)
        onNodeWithText("home").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("", pinField().shownText())
        pinField().performTextInput("x")
        assertEquals("\u2022", pinField().shownText())
    }

    @Test
    fun thePinFieldHoldsTheCaptureGuardOnlyWhileItIsShown() = runComposeUiTest {
        val guard = FakeGuard()
        showCard(guard)
        assertEquals(1, guard.holders)
        onNodeWithText("Random code").performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertEquals(0, guard.holders)
    }

    @Test
    fun aCapturedScreenMasksTheRevealedPinAndDisablesShow() = runComposeUiTest {
        val guard = FakeGuard()
        showCard(guard)
        pinField().performTextInput(SECRET_PIN)
        onNodeWithContentDescription("Show PIN").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(SECRET_PIN, pinField().shownText())
        guard.captured = true
        waitForIdle()
        assertTrue(SECRET_PIN.none { it in pinField().shownText() }, "no digit of the PIN is on screen while captured")
        onNodeWithContentDescription("Show PIN").assertIsNotEnabled()
    }

    @Test
    fun aScreenshotHidesTheRevealedPinAgain() = runComposeUiTest {
        val guard = FakeGuard()
        showCard(guard)
        pinField().performTextInput(SECRET_PIN)
        onNodeWithContentDescription("Show PIN").performSemanticsAction(SemanticsActions.OnClick)
        guard.screenshot()
        waitForIdle()
        assertTrue(SECRET_PIN.none { it in pinField().shownText() })
        // Hidden, not just masked for a moment. The capture is over and the PIN stays hidden.
        onNodeWithContentDescription("Show PIN").assertIsEnabled()
    }

    @Test
    fun thePassphraseFieldFollowsTheSameCaptureRules() = runComposeUiTest {
        val guard = FakeGuard()
        showJoinForm(guard)
        assertEquals(1, guard.holders)
        pinField().performTextInput(SECRET_PSK)
        onNodeWithContentDescription("Show passphrase").performSemanticsAction(SemanticsActions.OnClick)
        guard.screenshot()
        waitForIdle()
        assertEquals("\u2022".repeat(SECRET_PSK.length), pinField().shownText())
        onNodeWithContentDescription("Show passphrase").performSemanticsAction(SemanticsActions.OnClick)
        guard.captured = true
        waitForIdle()
        assertEquals("\u2022".repeat(SECRET_PSK.length), pinField().shownText())
        onNodeWithContentDescription("Show passphrase").assertIsNotEnabled()
        onNodeWithText("Cancel").performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertEquals(0, guard.holders)
    }

    @Test
    fun theIosScreenshotNoticeHidesTheRevealedPinAgain() = runComposeUiTest {
        showCard()
        pinField().performTextInput(SECRET_PIN)
        onNodeWithContentDescription("Show PIN").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(SECRET_PIN, pinField().shownText())
        NSNotificationCenter.defaultCenter.postNotificationName(UIApplicationUserDidTakeScreenshotNotification, `object` = null)
        waitForIdle()
        assertTrue(SECRET_PIN.none { it in pinField().shownText() }, "the posted screenshot notice hid the PIN")
        onNodeWithContentDescription("Show PIN").assertExists()
    }

    private companion object {
        const val SECRET_PIN = "482913"
        const val SECRET_PSK = "correct horse"
    }
}
