package space.opensensor.device

import space.opensensor.permissions.AccessHooks
import space.opensensor.permissions.AccessKind
import space.opensensor.permissions.RuntimeAccess
import kotlin.test.Test
import kotlin.test.assertEquals

/** Device settings asks for Bluetooth from its own Reconnect taps, since opening a thing no longer does (#147). */
class ReconnectTapsTest {
    private class Setup(var granted: Boolean) {
        var prompts = 0
        val calls = mutableListOf<String>()
        val access = RuntimeAccess(AccessKind.BLUETOOTH).also { access ->
            access.bind(
                AccessHooks.rationaleBased(
                    granted = { granted },
                    rationaleDue = { false },
                    requestPermission = { prompts++ },
                    openSettings = {},
                    radioOn = { true },
                    requestTurnOn = {},
                ),
            )
        }
        val taps = ReconnectTaps(access, { calls += "reconnect" }, { calls += "ble" })

        fun answer(allow: Boolean) {
            granted = allow
            access.onPermissionResult()
        }
    }

    @Test
    fun reconnectAsksFirstAndRunsOnceAllowed() {
        val s = Setup(granted = false)
        s.taps.reconnect()
        assertEquals(1, s.prompts)
        assertEquals(emptyList(), s.calls)
        s.answer(allow = true)
        assertEquals(listOf("reconnect"), s.calls)
    }

    @Test
    fun aRefusedReconnectStillReconnectsForWifi() {
        val s = Setup(granted = false)
        s.taps.reconnect()
        s.answer(allow = false)
        assertEquals(listOf("reconnect"), s.calls)
    }

    @Test
    fun reconnectOverBluetoothAsksAndNeedsIt() {
        val s = Setup(granted = false)
        s.taps.overBluetooth()
        assertEquals(1, s.prompts)
        s.answer(allow = false)
        assertEquals(emptyList(), s.calls)
    }

    @Test
    fun grantedBluetoothRunsWithoutAPrompt() {
        val s = Setup(granted = true)
        s.taps.reconnect()
        s.taps.overBluetooth()
        assertEquals(0, s.prompts)
        assertEquals(listOf("reconnect", "ble"), s.calls)
    }
}
