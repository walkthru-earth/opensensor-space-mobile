package space.opensensor.setup

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import space.opensensor.data.protocol.ConfigFields
import space.opensensor.permissions.AccessKind
import space.opensensor.permissions.PermissionTriggers
import space.opensensor.permissions.UserAction
import space.opensensor.thing.OpenSensorBoardKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class FakePort : SetupPort {
    override val nearby = MutableStateFlow(listOf(SetupCandidate("AA", "OS-1", added = false), SetupCandidate("BB", "OS-2", added = true)))
    val links = mutableMapOf<String, MutableStateFlow<SetupLink>>()
    val calls = mutableListOf<String>()
    val nicknames = mutableMapOf<String, String?>()
    val pending = mutableSetOf<String>()
    var finding = false

    override fun startFind() { finding = true; calls += "startFind" }
    override fun stopFind() { finding = false; calls += "stopFind" }
    override fun connect(id: String, name: String?) { calls += "connect $id" }
    override fun link(id: String): Flow<SetupLink> = links.getOrPut(id) { MutableStateFlow(SetupLink.Connecting) }
    override suspend fun nickname(id: String): String? = nicknames[id]
    override suspend fun saveNickname(id: String, nickname: String?) { nicknames[id] = nickname; calls += "saveNickname $id" }
    override suspend fun setPending(id: String, pending: Boolean) { if (pending) this.pending += id else this.pending -= id }

    fun ready(id: String, protocol: Int? = 3) { links.getValue(id).value = SetupLink.Ready(protocol) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SetupControllerTest {
    private fun TestScope.controller(port: FakePort, resume: String? = null) =
        SetupController(backgroundScope, port, resumeId = resume, resumeName = "OS-1").also { testScheduler.runCurrent() }

    private fun TestScope.paired(port: FakePort): SetupController {
        val c = controller(port)
        c.find()
        c.choose(port.nearby.value.first())
        port.ready("AA")
        testScheduler.runCurrent()
        return c
    }

    @Test
    fun boardPagesRunFindNameLocationWifiDone() {
        val pages = SetupFlows.BOARD.filter { it.isPage }
        assertEquals(listOf("find", "name", "location", "wifi", "done"), pages.map { it.id })
        assertEquals(SetupFlows.BOARD, SetupFlows.of(OpenSensorBoardKind.id))
        assertEquals(listOf(SetupFlows.WIFI), pages.filter { it.skippable })
        assertEquals(listOf<SetupStep>(SetupFlows.LOCATION), pages.filter { it.required })
    }

    @Test
    fun permissionStepsBindTheNextTapAndSharingIsNoStep() {
        val flow = SetupFlows.BOARD
        assertEquals(UserAction.FIND_SENSORS, flow.accessFor(SetupStep.Find))
        assertEquals(UserAction.WIFI_ACTION, flow.accessFor(SetupFlows.WIFI))
        assertNull(flow.accessFor(SetupStep.Name))
        assertTrue(AccessKind.BLUETOOTH in PermissionTriggers.of(UserAction.FIND_SENSORS).required)
        assertTrue(flow.none { it.id.contains("shar") || it.id.contains("upload") })
    }

    @Test
    fun wifiKeysComeFromTheFieldTable() {
        assertTrue(SetupFlows.WIFI.keys.all { ConfigFields.of(it) != null })
    }

    @Test
    fun pairingOpensNameAndMarksSetupPending() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val c = paired(port)
        assertEquals(SetupStep.Name, c.state.value.page)
        assertTrue(c.state.value.paired)
        assertEquals(setOf("AA"), port.pending)
        assertFalse(port.finding)
    }

    @Test
    fun backStopsAtNameAfterPairingAndCloseAsks() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val c = paired(port)
        c.next()
        assertEquals(SetupFlows.LOCATION, c.state.value.page)
        c.back()
        assertEquals(SetupStep.Name, c.state.value.page)
        c.back()
        assertEquals(SetupStep.Name, c.state.value.page)
        assertTrue(c.state.value.askClose)
        c.dismissClose()
        c.requestClose()
        assertTrue(c.state.value.askClose)
        c.finish()
        assertTrue(c.state.value.closed)
        assertEquals(setOf("AA"), port.pending)
    }

    @Test
    fun closingBeforePairingDoesNotAsk() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(FakePort())
        c.back()
        assertFalse(c.state.value.askClose)
        assertTrue(c.state.value.closed)
    }

    @Test
    fun locationHasNoSkipAndNextWaitsForASavedCell() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val c = paired(port)
        c.next()
        assertEquals(SetupFlows.LOCATION, c.state.value.page)
        assertFalse(SetupFlows.LOCATION.skippable)
        c.skip()
        assertEquals(SetupFlows.LOCATION, c.state.value.page)
        assertFalse(c.state.value.canAdvance)
        c.next()
        assertEquals(SetupFlows.LOCATION, c.state.value.page)
        c.onLocated(true)
        c.onLocated(false)
        c.next()
        assertEquals(SetupFlows.LOCATION, c.state.value.page)
        assertEquals(setOf("AA"), port.pending)
    }

    @Test
    fun doneNeedsASavedLocation() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val c = paired(port)
        c.next()
        repeat(3) { c.skip() }
        assertEquals(SetupFlows.LOCATION, c.state.value.page)
        assertEquals(setOf("AA"), port.pending)
    }

    @Test
    fun wifiSkipsToDoneOnceLocatedWhichClearsPending() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val c = paired(port)
        c.next()
        c.onLocated(true)
        c.next()
        assertEquals(SetupFlows.WIFI, c.state.value.page)
        c.skip()
        assertEquals(SetupStep.Done, c.state.value.page)
        assertEquals(emptySet(), port.pending)
        c.skip()
        assertEquals(SetupStep.Done, c.state.value.page)
        c.next()
        assertTrue(c.state.value.closed)
    }

    @Test
    fun losingTheSavedLocationOnWifiKeepsDoneClosed() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val c = paired(port)
        c.next()
        c.onLocated(true)
        c.next()
        assertEquals(SetupFlows.WIFI, c.state.value.page)
        // The board's cell went away after Location, so Wi-Fi Skip must not reach Done.
        c.onLocated(false)
        c.skip()
        assertEquals(SetupFlows.WIFI, c.state.value.page)
        assertEquals(setOf("AA"), port.pending)
    }

    @Test
    fun oldFirmwareFailsWithUpdateFirmware() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val c = controller(port)
        c.choose(port.nearby.value.first())
        port.ready("AA", protocol = 2)
        assertEquals(SetupFailure.UPDATE_FIRMWARE, c.state.value.failure)
        assertEquals(SetupStep.Find, c.state.value.page)
        assertEquals(emptySet(), port.pending)
    }

    @Test
    fun wrongPinAndCancelShowOneMessage() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val c = controller(port)
        c.choose(port.nearby.value.first())
        port.links.getValue("AA").value = SetupLink.PairingRefused
        assertEquals(SetupFailure.PAIRING, c.state.value.failure)
        assertFalse(c.state.value.pairing)
        c.retry()
        assertNull(c.state.value.failure)
        assertNull(c.state.value.selected)
        c.choose(port.nearby.value.first())
        port.links.getValue("AA").value = SetupLink.Unreachable
        assertEquals(SetupFailure.UNREACHABLE, c.state.value.failure)
    }

    @Test
    fun anAddedSensorRunsSetupAgain() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        port.nicknames["BB"] = "Kitchen"
        val c = controller(port)
        c.choose(port.nearby.value[1])
        port.ready("BB")
        assertEquals(SetupStep.Name, c.state.value.page)
        assertEquals("Kitchen", c.state.value.nickname)
    }

    @Test
    fun resumeOpensAtLocationAndReconnectsSilently() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val c = controller(port, resume = "AA")
        assertEquals(SetupFlows.LOCATION, c.state.value.page)
        assertFalse(c.state.value.canAdvance)
        assertTrue("connect AA" in port.calls)
        port.links.getValue("AA").value = SetupLink.Unreachable
        assertNull(c.state.value.failure)
        c.back()
        assertEquals(SetupStep.Name, c.state.value.page)
        c.back()
        assertTrue(c.state.value.askClose)
    }

    @Test
    fun nicknameIsAtMost32CharactersAndOnlyReachesTheStore() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val c = paired(port)
        c.setNickname("x".repeat(40))
        assertFalse(c.state.value.nicknameValid)
        c.next()
        assertEquals(SetupStep.Name, c.state.value.page)
        c.setNickname("  Balcony  ")
        c.next()
        assertEquals(SetupFlows.LOCATION, c.state.value.page)
        assertEquals("Balcony", port.nicknames["AA"])
        assertEquals("Balcony", c.state.value.displayName)
        assertEquals(listOf("saveNickname AA"), port.calls.filter { it.contains("AA") && !it.startsWith("connect") })
        assertTrue(port.calls.none { it.contains("Balcony") })
    }

    @Test
    fun aBlankNicknameShowsTheAdvertisedName() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        port.nicknames["AA"] = "Old"
        val c = paired(port)
        c.setNickname(" ")
        c.next()
        assertNull(port.nicknames["AA"])
        assertEquals("OS-1", c.state.value.displayName)
    }
}
