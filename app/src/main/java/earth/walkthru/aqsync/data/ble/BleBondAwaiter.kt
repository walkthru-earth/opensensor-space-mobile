package earth.walkthru.aqsync.data.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Bond-result history survives StateFlow conflation and asynchronous createBond broadcasts. */
internal class BleBondAwaiter(initialState: State) {
    enum class State { NONE, BONDING, BONDED }

    data class Snapshot(val state: State, val revision: Long = 0, val failureRevision: Long = 0)

    private val snapshots = MutableStateFlow(Snapshot(initialState))
    val current: Snapshot get() = snapshots.value

    @Synchronized
    fun update(state: State, previousState: State? = null): Snapshot {
        val previous = snapshots.value
        val revision = previous.revision + 1
        val failed = state == State.NONE && (previousState ?: previous.state) == State.BONDING
        return Snapshot(state, revision, if (failed) revision else previous.failureRevision).also { snapshots.value = it }
    }

    /** Capture [beforeRequest] before createBond, then await its asynchronous outcome. */
    suspend fun awaitResult(beforeRequest: Snapshot, timeoutMs: Long): Boolean = withTimeout(timeoutMs) {
        val result = snapshots.first {
            it.state == State.BONDED || it.failureRevision > beforeRequest.failureRevision ||
                (beforeRequest.state == State.BONDING && it.revision > beforeRequest.revision && it.state == State.NONE)
        }
        result.state == State.BONDED
    }
}
