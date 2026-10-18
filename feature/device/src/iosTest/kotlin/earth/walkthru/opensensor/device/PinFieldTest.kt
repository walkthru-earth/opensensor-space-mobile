package earth.walkthru.opensensor.device

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The fixed PIN never shows on screen unless asked, keeps password semantics for TalkBack and
 * VoiceOver, and is dropped when Fixed PIN is left (#160). Rendered headless on the iOS simulator.
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

    private fun ComposeUiTest.showCard() {
        setContent { MaterialTheme { PairingCard(state, onSave = {}, onReboot = {}) } }
        onNodeWithText("Fixed PIN").performSemanticsAction(SemanticsActions.OnClick)
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

    private companion object {
        const val SECRET_PIN = "482913"
    }
}
