package space.opensensor.data.ble

import kotlinx.coroutines.CoroutineScope

/** A [DeviceSession] over [transport] whose link is open, run in [scope] (a test's `backgroundScope`). */
suspend fun openSession(transport: DeviceTransport, scope: CoroutineScope, frames: FrameRecorder = FrameRecorder()): DeviceSession =
    DeviceSession.over(transport, scope, frames).also { it.link.open() }
