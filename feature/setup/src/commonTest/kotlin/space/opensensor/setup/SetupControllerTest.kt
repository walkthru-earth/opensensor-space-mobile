package space.opensensor.setup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import space.opensensor.data.protocol.DeviceConfig
import space.opensensor.data.protocol.SetupAck
import space.opensensor.data.sync.PlanStepKind
import space.opensensor.data.sync.SetupPlan
import space.opensensor.model.ConfigApplies
import space.opensensor.model.ConfigKeySpec
import space.opensensor.model.ConfigKeyType
import space.opensensor.model.ConfigValues
import space.opensensor.model.ManifestStep
import space.opensensor.model.SetupManifest
import space.opensensor.model.StepId
import space.opensensor.settings.DetailLevel
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
    var finding = false

    override fun startFind() { finding = true; calls += "startFind" }
    override fun stopFind() { finding = false; calls += "stopFind" }
    override fun connect(id: String, name: String?) { calls += "connect $id" }
    override fun link(id: String): Flow<SetupLink> = links.getOrPut(id) { MutableStateFlow(SetupLink.Connecting) }
    override suspend fun nickname(id: String): String? = nicknames[id]
    override suspend fun saveNickname(id: String, nickname: String?) { nicknames[id] = nickname; calls += "saveNickname $id" }

    fun ready(id: String, protocol: Int? = 3) { links.getValue(id).value = SetupLink.Ready(protocol) }
}

/** The steps a test template declares, and page 1 as a board reports it. */
internal object Plans {
    const val CELL = "8828308281fffff"

    fun step(id: String, req: Boolean = false, vararg keys: String) = ManifestStep(StepId(id), req, keys.toList())

    private val panelTheme = ConfigKeySpec("panel.theme", ConfigKeyType.ENUM, ConfigValues.Choices(listOf("light", "dark")), ConfigApplies.IMMEDIATELY, page = 7)

    fun manifest(vararg steps: ManifestStep) = SetupManifest(setup = steps.toList(), configKeys = mapOf(panelTheme.key to panelTheme))

    /** Every catalog step, Name to Bonds, with Location required. */
    val BOARD = manifest(step("name"), step("location", req = true), step("site"), step("wifi"), step("pairing"), step("lan"), step("bonds"))
    val BOARD_V = SetupAck.version(BOARD.setup.map { it.id.id })

    fun page(mobility: String? = "fixed", cell: String? = "", setupV: Int? = null, ack: Int? = null): DeviceConfig {
        val thing = listOfNotNull(
            mobility?.let { """"mobility":"$it"""" },
            setupV?.let { """"setup_v":$it""" },
            ack?.let { """"setup_ack":$it""" },
        )
        val loc = """"location":{"cell":${cell?.let { "\"$it\"" } ?: "null"},"resolution":8}"""
        return DeviceConfig.parse("{$loc,\"thing\":{${thing.joinToString(",")}}}")
    }

    fun plan(manifest: SetupManifest = BOARD, page: DeviceConfig = page()) = SetupPlan.of(manifest, page)
}

@OptIn(ExperimentalCoroutinesApi::class)
class SetupControllerTest {
    private val name = StepId.NAME
    private val location = StepId.LOCATION

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

    /** Paired and walking [plan] at [level]. */
    private fun TestScope.walking(port: FakePort, plan: SetupPlan = Plans.plan(), level: DetailLevel = DetailLevel.SIMPLE): SetupController =
        paired(port).also { it.onPlan(plan, level) }

    private val SetupController.at: SetupPage get() = state.value.page

    /** Records the acks the wizard asks for, answering [answer]. */
    private class Acks(var answer: Boolean = true) {
        val sent = mutableListOf<List<StepId>>()
        val ack: suspend (Collection<StepId>) -> Boolean = { ids -> sent += ids.toList(); answer }
    }

    @Test
    fun pairingWaitsForThePlanThenOpensTheFirstStepNotDone() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val c = paired(port)
        assertEquals(SetupPage.Loading, c.at)
        assertTrue(c.state.value.paired)
        assertFalse(port.finding)
        assertFalse(c.state.value.canAdvance)
        c.onPlan(null, DetailLevel.SIMPLE)
        assertEquals(SetupPage.Loading, c.at, "no plan before page 1 is read")
        // Name is acked on the device, so the walk opens at Location.
        c.onPlan(Plans.plan(page = Plans.page(setupV = Plans.BOARD_V, ack = 0b1)), DetailLevel.SIMPLE)
        assertEquals(SetupPage.Step(location), c.at)
    }

    @Test
    fun aPlanWithEveryStepDoneOpensTheFirstStepToEditAgain() = runTest(UnconfinedTestDispatcher()) {
        val c = walking(FakePort(), Plans.plan(page = Plans.page(cell = Plans.CELL, setupV = Plans.BOARD_V, ack = 0b1111111)))
        assertEquals(SetupPage.Step(name), c.at)
    }

    @Test
    fun theStepsFollowTheTemplateOrderAndLevel() = runTest(UnconfinedTestDispatcher()) {
        val simple = walking(FakePort())
        assertEquals(listOf("name", "location", "site", "wifi"), simple.state.value.order.map { it.id })
        val detailed = walking(FakePort(), level = DetailLevel.DETAILED)
        assertEquals(listOf("name", "location", "site", "wifi", "pairing", "bonds"), detailed.state.value.order.map { it.id })
        val developer = walking(FakePort(), level = DetailLevel.DEVELOPER)
        assertFalse(StepId.LAN in developer.state.value.order, "Wi-Fi sync is never a wizard page")
    }

    @Test
    fun withoutATemplateAFixedThingWalksOnlyTheLocationStep() = runTest(UnconfinedTestDispatcher()) {
        val c = walking(FakePort(), Plans.plan(SetupManifest.NONE))
        assertEquals(listOf(location), c.state.value.order)
        assertEquals(SetupPage.Step(location), c.at)
        val moving = walking(FakePort(), Plans.plan(SetupManifest.NONE, Plans.page(mobility = "moving")))
        assertEquals(SetupPage.Done, moving.at)
    }

    @Test
    fun aRequiredLocationKeepsNextAndSkipOffUntilTheBoardHoldsACell() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val c = walking(port)
        val acks = Acks()
        c.next(ack = acks.ack)
        assertEquals(SetupPage.Step(location), c.at)
        assertFalse(c.state.value.canAdvance)
        assertFalse(c.state.value.canSkip)
        c.skip(acks.ack)
        c.next(ack = acks.ack)
        assertEquals(SetupPage.Step(location), c.at)
        assertEquals(1, acks.sent.size, "only Name was acked")
        c.onPlan(Plans.plan(page = Plans.page(cell = Plans.CELL)), DetailLevel.SIMPLE)
        assertTrue(c.state.value.canAdvance)
        c.next(ack = acks.ack)
        assertEquals(SetupPage.Step(StepId.SITE), c.at)
    }

    @Test
    fun nextAcksTheStepWithTheHiddenStepsAndMovesOn() = runTest(UnconfinedTestDispatcher()) {
        val c = walking(FakePort())
        val acks = Acks()
        c.next(ack = acks.ack)
        assertEquals(listOf(listOf(name, StepId.PAIRING, StepId.LAN, StepId.BONDS)), acks.sent)
        assertEquals(SetupPage.Step(location), c.at)
    }

    @Test
    fun skipAcksAnOptionalStepAndMovesOn() = runTest(UnconfinedTestDispatcher()) {
        val c = walking(FakePort(), Plans.plan(page = Plans.page(cell = Plans.CELL)))
        c.next()
        c.next()
        assertEquals(SetupPage.Step(StepId.SITE), c.at)
        assertTrue(c.state.value.canSkip)
        val acks = Acks()
        c.skip(acks.ack)
        assertEquals(StepId.SITE, acks.sent.single().first())
        assertEquals(SetupPage.Step(StepId.WIFI), c.at)
    }

    @Test
    fun aFailedSaveOrAckStaysOnThePage() = runTest(UnconfinedTestDispatcher()) {
        val c = walking(FakePort(), Plans.plan(page = Plans.page(cell = Plans.CELL)))
        c.next()
        c.next()
        assertEquals(SetupPage.Step(StepId.SITE), c.at)
        val acks = Acks()
        c.next(save = { false }, ack = acks.ack)
        assertEquals(SetupPage.Step(StepId.SITE), c.at, "a refused or unconfirmed save stays")
        assertTrue(acks.sent.isEmpty(), "nothing is acked before the save is confirmed")
        acks.answer = false
        c.next(save = { true }, ack = acks.ack)
        assertEquals(SetupPage.Step(StepId.SITE), c.at, "an ack the device did not confirm stays")
        c.skip(acks.ack)
        assertEquals(SetupPage.Step(StepId.SITE), c.at)
        acks.answer = true
        c.next(save = { true }, ack = acks.ack)
        assertEquals(SetupPage.Step(StepId.WIFI), c.at)
    }

    @Test
    fun skipWaitsForASaveAndASaveEndingAfterBackNeverMoves() = runTest(UnconfinedTestDispatcher()) {
        val c = walking(FakePort(), Plans.plan(page = Plans.page(cell = Plans.CELL)))
        c.next()
        c.next()
        val result = CompletableDeferred<Boolean>()
        c.next(save = { result.await() })
        assertTrue(c.state.value.saving)
        assertFalse(c.state.value.canSkip)
        c.skip()
        assertEquals(SetupPage.Step(StepId.SITE), c.at, "Skip cannot race the save")
        c.back()
        assertEquals(SetupPage.Step(location), c.at)
        result.complete(true)
        assertEquals(SetupPage.Step(location), c.at, "a save that ends after Back never moves the wizard")
        assertFalse(c.state.value.saving)
    }

    @Test
    fun doneWaitsForEveryRequiredStep() = runTest(UnconfinedTestDispatcher()) {
        val c = walking(FakePort(), Plans.plan(page = Plans.page(cell = Plans.CELL)))
        repeat(3) { c.next() }
        assertEquals(SetupPage.Step(StepId.WIFI), c.at)
        // The board's cell went away after Location, so Wi-Fi Next must not reach Done.
        c.onPlan(Plans.plan(), DetailLevel.SIMPLE)
        c.next()
        assertEquals(SetupPage.Step(StepId.WIFI), c.at)
        c.onPlan(Plans.plan(page = Plans.page(cell = Plans.CELL)), DetailLevel.SIMPLE)
        c.skip()
        assertEquals(SetupPage.Done, c.at)
    }

    @Test
    fun closeOnDoneAcksTheHiddenStepsNotDoneThenLeaves() = runTest(UnconfinedTestDispatcher()) {
        // Every page is acked, the hidden ones are not, so setup would stay pending.
        val c = walking(FakePort(), Plans.plan(page = Plans.page(cell = Plans.CELL, setupV = Plans.BOARD_V, ack = 0b1111)))
        repeat(4) { c.next() }
        assertEquals(SetupPage.Done, c.at)
        val acks = Acks(answer = false)
        c.next(ack = acks.ack)
        assertEquals(listOf(listOf(StepId.PAIRING, StepId.LAN, StepId.BONDS)), acks.sent)
        assertFalse(c.state.value.closed, "an unconfirmed ack stays on Done")
        acks.answer = true
        c.next(ack = acks.ack)
        assertTrue(c.state.value.closed)
    }

    @Test
    fun anUnsupportedRequiredStepOffersNeitherNextNorSkip() = runTest(UnconfinedTestDispatcher()) {
        val manifest = Plans.manifest(Plans.step("calibrate", req = true))
        val c = walking(FakePort(), Plans.plan(manifest, Plans.page(mobility = "moving")))
        assertEquals(SetupPage.Step(StepId("calibrate")), c.at)
        assertEquals(PlanStepKind.UNSUPPORTED, c.state.value.step?.kind)
        assertFalse(c.state.value.canAdvance)
        assertFalse(c.state.value.canSkip)
    }

    @Test
    fun aBoardStepShowsItsFieldsAndSavesBeforeItsAck() = runTest(UnconfinedTestDispatcher()) {
        val manifest = Plans.manifest(Plans.step("panel", false, "panel.theme"))
        val c = walking(FakePort(), Plans.plan(manifest, Plans.page(mobility = "moving")))
        assertEquals(PlanStepKind.GENERIC, c.state.value.step?.kind)
        assertEquals(listOf("panel.theme"), c.state.value.step?.fields?.map { it.field.key })
        val order = mutableListOf<String>()
        c.next(save = { order += "save"; true }, ack = { order += "ack"; true })
        assertEquals(listOf("save", "ack"), order)
        assertEquals(SetupPage.Done, c.at)
    }

    @Test
    fun aStepThatLeavesThePlanStillAdvances() = runTest(UnconfinedTestDispatcher()) {
        // No template Location, so a fixed Thing gets the app's own. Choosing moving drops it.
        val manifest = Plans.manifest(Plans.step("wifi"))
        val c = walking(FakePort(), Plans.plan(manifest))
        assertEquals(SetupPage.Step(location), c.at)
        assertFalse(c.state.value.canAdvance)
        c.onPlan(Plans.plan(manifest, Plans.page(mobility = "moving")), DetailLevel.SIMPLE)
        assertEquals(SetupPage.Step(location), c.at, "the page stays while the owner reads it")
        assertTrue(c.state.value.canAdvance)
        c.next()
        assertEquals(SetupPage.Step(StepId.WIFI), c.at)
        c.back()
        assertEquals(SetupPage.Step(location), c.at)
    }

    @Test
    fun backStopsAtTheFirstStepAfterPairingAndCloseAsks() = runTest(UnconfinedTestDispatcher()) {
        val c = walking(FakePort())
        c.next()
        assertEquals(SetupPage.Step(location), c.at)
        c.back()
        assertEquals(SetupPage.Step(name), c.at)
        c.back()
        assertEquals(SetupPage.Step(name), c.at)
        assertTrue(c.state.value.askClose)
        c.dismissClose()
        c.requestClose()
        assertTrue(c.state.value.askClose)
        c.finish()
        assertTrue(c.state.value.closed)
    }

    @Test
    fun closingBeforePairingDoesNotAsk() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(FakePort())
        c.back()
        assertFalse(c.state.value.askClose)
        assertTrue(c.state.value.closed)
    }

    @Test
    fun oldFirmwareFailsWithUpdateFirmware() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val c = controller(port)
        c.choose(port.nearby.value.first())
        port.ready("AA", protocol = 2)
        assertEquals(SetupFailure.UPDATE_FIRMWARE, c.state.value.failure)
        assertEquals(SetupPage.Find, c.at)
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
    fun aPickedSensorIsListedOnFindWhileItPairs() = runTest(UnconfinedTestDispatcher()) {
        // Android's wizard scans on its own, so a sensor picked on My devices is not heard yet.
        val port = FakePort()
        port.nearby.value = emptyList()
        val pick = SetupCandidate("CC", "OS-3", added = false)
        val c = SetupController(backgroundScope, port, pick = pick)
        assertEquals(listOf(pick), c.state.value.candidates)
        assertEquals(pick, c.state.value.selected)
        assertTrue(c.state.value.pairing)
        // A scan that hears it lists it once, and a failure leaves it on Find to tap again.
        port.nearby.value = listOf(SetupCandidate("cc", "OS-3", added = false), SetupCandidate("AA", "OS-1", added = false))
        assertEquals(listOf("cc", "AA"), c.state.value.candidates.map { it.id })
        // The scanned row with the id in lower case still reads as the one pairing.
        assertEquals(listOf(true, false), c.state.value.candidates.map { c.state.value.pairingWith(it) })
        port.nearby.value = emptyList()
        port.links.getValue("CC").value = SetupLink.Unreachable
        c.retry()
        assertEquals(listOf(pick), c.state.value.candidates)
    }

    @Test
    fun aNewSensorPickedOnMyDevicesPairsAtOnce() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val c = SetupController(backgroundScope, port, pick = SetupCandidate("AA", "OS-1", added = false))
        assertTrue("connect AA" in port.calls)
        assertTrue(c.state.value.pairing)
        port.ready("AA")
        assertEquals(SetupPage.Loading, c.at)
        assertTrue(c.state.value.paired)
    }

    @Test
    fun aSensorAskingForSetupIsOfferedOnFindNotPaired() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        port.nearby.value = emptyList()
        val opened = mutableListOf<String>()
        val offer = SetupCandidate("CC", "OS-3", added = false)
        val c = SetupController(backgroundScope, port, pick = offer, offer = true, opened = { opened += it })
        assertEquals(SetupPage.Find, c.at)
        assertEquals(listOf(offer), c.state.value.candidates)
        assertFalse(port.calls.any { it.startsWith("connect") }, "pairing stays the owner's tap")
        assertEquals(listOf("CC"), opened)

        // The scan on Find hears others, the offer stays first, and once heard it is listed once.
        port.nearby.value = listOf(SetupCandidate("AA", "OS-1", added = false))
        assertEquals(listOf("CC", "AA"), c.state.value.candidates.map { it.id })
        port.nearby.value = listOf(SetupCandidate("AA", "OS-1", added = false), SetupCandidate("cc", "OS-3", added = false))
        assertEquals(listOf("AA", "cc"), c.state.value.candidates.map { it.id })

        c.choose(c.state.value.candidates.last())
        assertTrue("connect cc" in port.calls)
        assertEquals(listOf("CC", "cc"), opened)
    }

    @Test
    fun anAutomaticOpenOfAnAddedSensorMakesNoConnectCallBeforeTheTap() = runTest(UnconfinedTestDispatcher()) {
        // The shell opens it as an offer (TabNavigator.finishSetup with auto), never as a resume.
        val port = FakePort()
        val c = SetupController(backgroundScope, port, pick = SetupCandidate("BB", "OS-2", added = false), offer = true)
        c.find()
        assertEquals(SetupPage.Find, c.at)
        assertFalse(c.state.value.paired)
        assertTrue(port.calls.none { it.startsWith("connect") }, "the open alone never connects")

        c.choose(c.state.value.candidates.single { it.id == "BB" })
        assertEquals(listOf("connect BB"), port.calls.filter { it.startsWith("connect") })
        port.ready("BB")
        assertEquals(SetupPage.Loading, c.at)
    }

    @Test
    fun everyRunTellsTheWatchItOpened() = runTest(UnconfinedTestDispatcher()) {
        val opened = mutableListOf<String>()
        SetupController(backgroundScope, FakePort(), resumeId = "BB", opened = { opened += it })
        SetupController(backgroundScope, FakePort(), pick = SetupCandidate("AA", "OS-1", added = false), opened = { opened += it })
        val port = FakePort()
        val c = SetupController(backgroundScope, port, opened = { opened += it })
        assertEquals(listOf("BB", "AA"), opened)
        c.choose(port.nearby.value[1])
        assertEquals(listOf("BB", "AA", "BB"), opened)
        // Closing counts again, as the foreground session may have ended while the wizard showed.
        c.finish()
        assertEquals(listOf("BB", "AA", "BB", "BB"), opened)
    }

    @Test
    fun anAddedSensorRunsSetupAgainWithItsNickname() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        port.nicknames["BB"] = "Kitchen"
        val c = controller(port)
        c.choose(port.nearby.value[1])
        port.ready("BB")
        assertEquals(SetupPage.Loading, c.at)
        assertEquals("Kitchen", c.state.value.nickname)
    }

    @Test
    fun resumeWaitsForThePlanAndReconnectsSilently() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val c = controller(port, resume = "AA")
        assertEquals(SetupPage.Loading, c.at)
        assertTrue(c.state.value.paired)
        assertTrue("connect AA" in port.calls)
        port.links.getValue("AA").value = SetupLink.Unreachable
        assertNull(c.state.value.failure)
        c.onPlan(Plans.plan(page = Plans.page(setupV = Plans.BOARD_V, ack = 0b1)), DetailLevel.SIMPLE)
        assertEquals(SetupPage.Step(location), c.at)
        assertFalse(c.state.value.canAdvance)
        c.back()
        assertEquals(SetupPage.Step(name), c.at)
        c.back()
        assertTrue(c.state.value.askClose)
    }

    @Test
    fun nicknameIsAtMost32CharactersAndOnlyReachesTheStore() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        val c = walking(port)
        c.setNickname("x".repeat(40))
        assertFalse(c.state.value.nicknameValid)
        assertFalse(c.state.value.canAdvance)
        c.next()
        assertEquals(SetupPage.Step(name), c.at)
        c.setNickname("  Balcony  ")
        c.next()
        assertEquals(SetupPage.Step(location), c.at)
        assertEquals("Balcony", port.nicknames["AA"])
        assertEquals("Balcony", c.state.value.displayName)
        assertEquals(listOf("saveNickname AA"), port.calls.filter { it.contains("AA") && !it.startsWith("connect") })
        assertTrue(port.calls.none { it.contains("Balcony") })
    }

    @Test
    fun aBlankNicknameShowsTheAdvertisedName() = runTest(UnconfinedTestDispatcher()) {
        val port = FakePort()
        port.nicknames["AA"] = "Old"
        val c = walking(port)
        c.setNickname(" ")
        c.next()
        assertNull(port.nicknames["AA"])
        assertEquals("OS-1", c.state.value.displayName)
    }
}
