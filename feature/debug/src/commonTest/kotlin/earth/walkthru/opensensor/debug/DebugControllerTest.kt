package earth.walkthru.opensensor.debug

import earth.walkthru.aqsync.data.ble.FrameDirection
import earth.walkthru.aqsync.data.ble.FrameRecorder
import earth.walkthru.aqsync.data.ble.RawFrameRecord
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.FrameType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeSource : DebugSource {
    val recorder = FrameRecorder()
    val sent = mutableListOf<ByteArray>()
    var replies: List<Pair<ByteArray, Frame?>> = emptyList()
    var block: DebugBlock? = null
    var throwBlock: DebugBlock? = null
    override val link = MutableStateFlow(DebugLink())
    override val status = MutableStateFlow<DebugJson?>(null)
    override val config = MutableStateFlow<DebugJson?>(null)
    override val frames: Flow<List<RawFrameRecord>> = recorder.records
    override val log = MutableStateFlow<DebugLog>(DebugLog.Idle)
    override val syncTrace = MutableStateFlow<DebugSyncTrace?>(null)
    override val configOnRequest = true
    override fun rawBlock(): DebugBlock? = block
    override suspend fun rawCommand(request: ByteArray): List<Pair<ByteArray, Frame?>> {
        throwBlock?.let { throw DebugBlockedException(it) }
        sent += request
        recorder.tx(request)
        replies.forEach { recorder.rx(it.first) }
        return replies
    }
    override fun requestStatus() = Unit
    override fun refreshLog() = Unit
    override fun clearFrames() = recorder.clear()
}

@OptIn(ExperimentalCoroutinesApi::class)
class DebugControllerTest {
    private fun TestScope.controller(source: FakeSource): Pair<DebugController, StateFlow<DebugUiState>> {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val c = DebugController(scope, source)
        scope.launch { c.state.collect {} }
        return c to c.state
    }

    @Test
    fun rebootAndSetConfigNeverReachTheSource() = runTest {
        val source = FakeSource()
        val (c, state) = controller(source)
        c.sendRaw("0b")
        assertEquals(RawError.NeedsConfirmation("REBOOT"), state.value.rawError)
        c.sendRaw("09 7b 22 70 73 6b 22 7d")
        assertEquals(RawError.NeedsConfirmation("SET_CONFIG"), state.value.rawError)
        assertTrue(source.sent.isEmpty())
        assertTrue(state.value.frames.isEmpty())
    }

    @Test
    fun badInputIsReportedWithoutSending() = runTest {
        val source = FakeSource()
        val (c, state) = controller(source)
        c.sendRaw("zz")
        assertEquals(RawError.InvalidHex, state.value.rawError)
        c.sendRaw("")
        assertEquals(RawError.BadLength, state.value.rawError)
        assertTrue(source.sent.isEmpty())
    }

    @Test
    fun aBlockedLinkRefusesBeforeSending() = runTest {
        val source = FakeSource()
        val (c, state) = controller(source)
        source.block = DebugBlock.BACKGROUND_SYNC
        c.sendRaw("08")
        assertEquals(RawError.BackgroundOwns, state.value.rawError)
        source.block = null
        source.throwBlock = DebugBlock.LINK_BUSY
        c.sendRaw("08")
        assertEquals(RawError.LinkBusy, state.value.rawError)
        assertFalse(state.value.rawBusy)
        assertTrue(source.sent.isEmpty())
    }

    @Test
    fun aTokenReplyIsShownAndRecordedRedacted() = runTest {
        val source = FakeSource()
        val (c, state) = controller(source)
        val token = ByteArray(32) { 0x5A }
        source.replies = listOf((byteArrayOf(FrameType.TOKEN.toByte(), 0x51, 0x1F) + token) to Frame.Token(8017, token))
        c.sendRaw("0d")
        val s = state.value
        assertNull(s.rawError)
        assertEquals(1, s.rawResults.size)
        assertFalse("5a" in s.rawResults[0].hex)
        assertFalse("5a5a" in s.rawResults[0].decoded.lowercase())
        assertEquals(2, s.frames.size)
        val rx = s.frames.first()
        assertEquals(FrameDirection.RX, rx.direction)
        assertTrue(rx.redacted)
        assertFalse("5a" in frameLine(rx))
        assertTrue(frameLine(rx).endsWith("<redacted>"))
    }

    @Test
    fun noReplyIsSaidPlainly() = runTest {
        val source = FakeSource()
        val (c, state) = controller(source)
        c.sendRaw("01")
        assertEquals(RawError.NoResponse, state.value.rawError)
        assertEquals(1, source.sent.size)
    }

    @Test
    fun statusAndConfigJsonArePrettyAndScrubbed() = runTest {
        val source = FakeSource()
        val (_, state) = controller(source)
        source.config.value = DebugJson("""{"wifi":{"ssid":"home","psk":"hunter22"},"pin":"123456","psk_set":true}""", 1L)
        source.status.value = DebugJson("""{"token":"abc","uptime_s":5}""", 2L)
        val s = state.value
        val config = s.configJsonPretty!!
        assertFalse("hunter22" in config)
        assertFalse("123456" in config)
        assertTrue("\"psk_set\": true" in config)
        assertTrue("\n" in config)
        assertFalse("abc" in s.statusJsonPretty!!)
        assertEquals(2L, s.statusAtEpochMs)
    }

    @Test
    fun framesAreNewestFirstAndClearEmptiesThem() = runTest {
        val source = FakeSource()
        val (c, state) = controller(source)
        source.recorder.tx(byteArrayOf(0x01))
        source.recorder.tx(byteArrayOf(0x07))
        assertEquals(listOf(0x07, 0x01), state.value.frames.map { it.type })
        c.clearFrames()
        assertTrue(state.value.frames.isEmpty())
    }

    @Test
    fun frameLineKeepsTheAndroidLayout() {
        val rec = RawFrameRecord(0L, FrameDirection.TX, 0x08, 1, "08", redacted = false)
        val line = frameLine(rec)
        assertTrue(line.endsWith(" GET_CONFIG        1 B  08"), line)
        val long = rec.copy(length = 40)
        assertTrue(frameLine(long).endsWith("…"))
        assertIs<String>(timeOf(0L))
        assertEquals(12, timeOf(1_234L).length)
    }

    @Test
    fun agoUsesTheCatalogueTemplates() {
        val m = ""
        assertEquals("now", formatAgo(-1, "now", "${m}s", "${m}m", "${m}h", "${m}d"))
        assertEquals("5s", formatAgo(5_000, "now", "${m}s", "${m}m", "${m}h", "${m}d"))
        assertEquals("2m", formatAgo(120_000, "now", "${m}s", "${m}m", "${m}h", "${m}d"))
        assertEquals("3h", formatAgo(3 * 3_600_000L, "now", "${m}s", "${m}m", "${m}h", "${m}d"))
        assertEquals("1d", formatAgo(86_400_000L, "now", "${m}s", "${m}m", "${m}h", "${m}d"))
    }

    @Test
    fun anyConfirmedLevelBelowDeveloperLeavesDebug() {
        assertTrue(leavesDebug(false))
        assertFalse(leavesDebug(null))
        assertFalse(leavesDebug(true))
    }
}
