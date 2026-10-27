package space.opensensor

import kotlinx.coroutines.flow.emptyFlow
import space.opensensor.archive.ArchiveRecord
import space.opensensor.devices.ActiveLink
import space.opensensor.devices.KnownSensor
import space.opensensor.devices.LinkPhase
import space.opensensor.strings.UiText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DevicesBridgeTest {
    private val a = "11111111-1111-1111-1111-111111111111"
    private val b = "22222222-2222-2222-2222-222222222222"

    private class MemoryStorage(var text: String? = null) : KnownRecordStorage {
        override fun load(): String? = text
        override fun save(text: String) {
            this.text = text
        }
    }

    private class Calls {
        val forgotten = mutableListOf<Pair<String, String?>>()
        val collected = mutableListOf<Pair<String, String?>>()
    }

    private fun bridge(state: CompanionState = CompanionState(), storage: KnownRecordStorage = MemoryStorage(), calls: Calls = Calls(), now: Long = 5) =
        DevicesBridge(
            state = state,
            archive = emptyFlow(),
            storage = storage,
            now = { now },
            startScan = {},
            stopScan = {},
            forget = { p, d -> calls.forgotten += p to d },
            collect = { id, wifi -> calls.collected += id to wifi },
        )

    @Test fun mergeListsRecordsAndCredentialsOncePerPeripheral() {
        val records = listOf(KnownRecord(a, "Kitchen", "st-a", "aabbccddeeff", 10))
        val credentials = listOf(
            KnownRecord(a.lowercase(), null, "st-a", "AABBCCDDEEFF", null),
            KnownRecord(b, null, "st-b", "112233445566", null),
        )
        val merged = IosKnown.merge(records, credentials)
        assertEquals(listOf(a, b), merged.map { it.id })
        assertEquals("Kitchen", merged[0].name, "the record names the sensor")
        assertEquals(10L, merged[0].lastSeenMs)
        assertTrue(merged[0].hasWifiAccess, "the credential is matched by hardware id, any case")
        assertTrue(merged[1].hasWifiAccess)
        assertNull(merged[1].name)
        assertFalse(IosKnown.merge(records, emptyList()).single().hasWifiAccess)
        assertFalse(
            IosKnown.merge(records, listOf(KnownRecord(a, null, "older-thing", "aabbccddeeff", null))).single().hasWifiAccess,
            "a token for an earlier thing cannot grant current Wi-Fi access",
        )
    }

    @Test fun codecRoundTripsAndDropsDamagedLines() {
        val records = listOf(
            KnownRecord(a, "Kit\tchen\n2", "st-a", "aabbccddeeff", 10),
            KnownRecord(b, null, "st-b", "112233445566", null),
        )
        val text = IosKnown.encode(records)
        val back = IosKnown.decode(text + "\nbroken line\n\t\t\t\t")
        assertEquals(2, back.size)
        assertEquals("Kit chen 2", back[0].name)
        assertNull(back[1].name)
        assertNull(back[1].lastSeenMs)
        assertEquals(emptyList(), IosKnown.decode(null))
        assertEquals(emptyList(), IosKnown.decode("$a\tKitchen\tst-a\taabbccddeeff\t10").single().olderThings, "old saved records still load")
    }

    @Test fun changedHardwareCannotReplaceSavedIdentityOrItsArchiveThing() {
        val storage = MemoryStorage()
        val bridge = bridge(storage = storage)
        assertTrue(bridge.sensorOpened(a, "Kitchen", "st-a", "aabbccddeeff").accepted)
        val saved = storage.text
        val rejected = bridge.sensorOpened(a, "Other", "st-other", "112233445566")
        assertFalse(rejected.accepted)
        assertEquals(CompanionNotice.HARDWARE_CHANGED, rejected.notice)
        assertNull(rejected.credentialToRemove)
        assertEquals(saved, storage.text)
        assertEquals(listOf("st-a"), bridge.thingsFor(a))
        assertEquals("aabbccddeeff", bridge.deviceFor(a))
    }

    @Test fun sameHardwareNewThingPreservesOldEpochAndInvalidatesToken() {
        val storage = MemoryStorage()
        val bridge = bridge(storage = storage)
        bridge.sensorOpened(a, "Kitchen", "st-a", "aabbccddeeff")
        bridge.credentialsChanged(listOf(a), listOf("st-a"), listOf("aabbccddeeff"))
        val changed = bridge.sensorOpened(a, "Kitchen", "st-b", "AABBCCDDEEFF")
        assertTrue(changed.accepted)
        assertEquals("aabbccddeeff", changed.credentialToRemove)
        assertEquals(listOf("st-b", "st-a"), bridge.thingsFor(a))
        assertEquals(listOf("st-b", "st-a"), bridge(storage = storage).thingsFor(a), "epochs survive app restart")
        bridge.credentialsChanged(emptyList(), emptyList(), emptyList())
        assertFalse(bridge.knownNow().single().hasWifiAccess)
        bridge.sensorOpened(a, "Kitchen", "st-c", "aabbccddeeff")
        assertEquals(listOf("st-c", "st-b", "st-a"), bridge.thingsFor(a))
    }

    @Test fun keychainOnlyEnrollmentAlsoPinsHardwareAndThing() {
        val bridge = bridge()
        bridge.credentialsChanged(listOf(a), listOf("st-a"), listOf("aabbccddeeff"))
        assertFalse(bridge.sensorOpened(a, "Other", "st-b", "112233445566").accepted)
        val accepted = bridge.sensorOpened(a, "Kitchen", "st-b", "aabbccddeeff")
        assertTrue(accepted.accepted)
        assertEquals("aabbccddeeff", accepted.credentialToRemove)
        assertEquals(listOf("st-b", "st-a"), bridge.thingsFor(a))
    }

    @Test fun glanceCountsFilesPerThing() {
        fun record(thing: String, synced: Long, last: Long?) = ArchiveRecord(thing, "f$synced", 1, 1, "sha", synced, "d", lastUtcNs = last)
        val glance = IosKnown.glance(listOf(record("s", 1, 100), record("s", 3, null), record("t", 2, 7)))
        assertEquals(2, glance.getValue("s").files)
        assertEquals(3L, glance.getValue("s").lastSyncedMs)
        assertEquals(100L, glance.getValue("s").lastDataUtcNs)
        assertNull(glance.getValue("s").lastDayPrimaryMean, "iOS has no day mean yet")
        assertEquals(1, glance.getValue("t").files)
    }

    @Test fun activeLinksCoverBluetoothAndWifi() {
        val sessions = mapOf(
            "aq-b" to LanSessionState(connected = true, busy = true, message = UiText.None, filesDone = 1, filesTotal = 4, bytesDone = 0),
            "aq-c" to LanSessionState(connected = false, busy = true, message = UiText.None, filesDone = 0, filesTotal = 0, bytesDone = 0),
            "aq-d" to LanSessionState(connected = false, busy = false, message = UiText.None, filesDone = 0, filesTotal = 0, bytesDone = 0),
        )
        val links = IosKnown.active(a, connected = false, syncBusy = false, syncDone = 0, syncTotal = 0, name = "Kitchen", lanSessions = sessions)
        assertEquals(listOf(a, "wifi:aq-b", "wifi:aq-c"), links.map { it.id })
        assertEquals(LinkPhase.CONNECTING, links[0].phase)
        assertTrue(links[1].collecting)
        assertEquals(4, links[1].filesTotal)
        assertEquals(LinkPhase.CONNECTING, links[2].phase)
        assertTrue(IosKnown.active(null, false, false, 0, 0, null, emptyMap()).isEmpty())
    }

    @Test fun activeLinksShowReconnectingFailedAndBackgroundPhases() {
        val reconnecting = IosKnown.active(null, false, true, 2, 5, "Kitchen", emptyMap(), reconnectingId = a, bluetoothBackground = true).single()
        assertEquals(LinkPhase.RECONNECTING, reconnecting.phase)
        assertTrue(reconnecting.collecting, "the run goes on while the link is dialled again")
        assertTrue(reconnecting.background)
        assertEquals(2, reconnecting.filesDone)
        val back = IosKnown.active(a, true, true, 3, 5, "Kitchen", emptyMap()).single()
        assertEquals(LinkPhase.CONNECTED, back.phase)
        assertFalse(back.background)
        val failed = IosKnown.active(null, false, false, 0, 0, "Kitchen", emptyMap(), failedId = a).single()
        assertEquals(LinkPhase.FAILED, failed.phase)
        assertFalse(failed.collecting)
        assertEquals(LinkPhase.CONNECTING, IosKnown.active(a, false, false, 0, 0, null, emptyMap(), failedId = a).single().phase)
        val sessions = mapOf(
            "aq-p" to LanSessionState(connected = false, busy = true, message = UiText.None, filesDone = 1, filesTotal = 3, bytesDone = 0),
            "aq-q" to LanSessionState(connected = true, busy = true, message = UiText.None, filesDone = 0, filesTotal = 2, bytesDone = 0),
        )
        val wifi = IosKnown.active(null, false, false, 0, 0, null, sessions, lanPaused = setOf("aq-p"), lanBackground = setOf("aq-q"))
        assertEquals(LinkPhase.RECONNECTING, wifi[0].phase)
        assertTrue(wifi[0].collecting)
        assertFalse(wifi[0].background)
        assertEquals(LinkPhase.CONNECTED, wifi[1].phase)
        assertTrue(wifi[1].background)
    }

    @Test fun onlyPeripheralIdsOpenOverBluetooth() {
        assertTrue(IosKnown.isPeripheral(a))
        assertFalse(IosKnown.isPeripheral("wifi:aq-b"))
        assertFalse(IosKnown.isPeripheral("AA:BB:CC:DD:EE:FF"))
    }

    @Test fun activeWifiServiceOpensItsEnrolledSensor() {
        val state = CompanionState()
        val bridge = bridge(state = state)
        bridge.sensorOpened(a, "Kitchen", "st-a", "aabbccddeeff")
        state.lanScanStarted()
        state.lanSensorsFound(listOf("aq-kitchen", "aq-unknown"), listOf("st-a", "st-other"))
        assertEquals(a, bridge.peripheralForLan("aq-kitchen"))
        assertNull(bridge.peripheralForLan("aq-unknown"))
        bridge.credentialsChanged(listOf(b), listOf("st-b"), listOf("112233445566"))
        state.lanSensorsFound(listOf("aq-kitchen", "aq-unknown", "aq-b"), listOf("st-a", "st-other", "st-b"))
        assertEquals(b, bridge.peripheralForLan("aq-b"), "a Keychain-only enrollment opens the shared Today screen")
        assertEquals("st-b", bridge.thingFor(b))
        assertEquals("112233445566", bridge.deviceFor(b))
    }

    @Test fun openingASensorKeepsItAndForgettingRemovesItEverywhere() {
        val storage = MemoryStorage()
        val calls = Calls()
        val bridge = bridge(storage = storage, calls = calls, now = 42)
        bridge.sensorOpened(a, "Kitchen", "st-a", "AABBCCDDEEFF")
        bridge.credentialsChanged(listOf(a, b), listOf("st-a", "st-b"), listOf("aabbccddeeff", "112233445566"))
        assertEquals(listOf(a, b), bridge.knownNow().map { it.id })
        assertEquals(42L, bridge.knownNow().first().lastSeenMs)
        assertEquals("Kitchen", IosKnown.decode(storage.text).single().name, "the record is saved")

        bridge.forgetNow(a)
        assertEquals(listOf<Pair<String, String?>>(a to "aabbccddeeff"), calls.forgotten, "Swift removes the credential of this hardware id")
        assertEquals(listOf(b), bridge.knownNow().map { it.id })
        assertTrue(IosKnown.decode(storage.text).isEmpty())
        assertTrue(bridge(storage = storage).knownNow().isEmpty(), "forgotten stays forgotten after a restart")
    }

    @Test fun collectAllRunsOneSensorAtATime() {
        val state = CompanionState()
        val calls = Calls()
        val bridge = bridge(state = state, calls = calls)
        bridge.sensorOpened(a, "A", "st-a", "aabbccddeeff")
        bridge.sensorOpened(b, "B", "st-b", "112233445566")
        bridge.credentialsChanged(listOf(b), listOf("st-b"), listOf("112233445566"))
        state.lanScanStarted()
        state.lanSensorsFound(listOf("aq-b"), listOf("st-b"))

        bridge.startCollectAll()
        assertEquals(listOf<Pair<String, String?>>(b to "aq-b"), calls.collected, "Wi-Fi first when the token is held and the sensor is on the Wi-Fi")
        bridge.startCollectAll()
        assertEquals(1, calls.collected.size, "a second tap while running does nothing")

        bridge.collectFinished(b, ok = true)
        assertEquals(listOf<Pair<String, String?>>(b to "aq-b", a to null), calls.collected)
        bridge.collectFinished(a, ok = false)

        bridge.startCollectAll()
        assertEquals(3, calls.collected.size, "a finished pass can run again")
        bridge.cancelCollectAll()
        bridge.collectFinished(b, ok = false)
        assertEquals(3, calls.collected.size, "cancel drops the rest")
    }

    @Test fun aUserActionOnASensorOfThePassStopsItBeforeItRuns() {
        val state = CompanionState()
        val calls = Calls()
        val bridge = bridge(state = state, calls = calls)
        bridge.sensorOpened(a, "A", "st-a", "aabbccddeeff")
        bridge.sensorOpened(b, "B", "st-b", "112233445566")
        val seen = mutableListOf<String>()
        val sync = bridge.userFirst({ id: String -> seen += id })
        val oneFile = bridge.userFirst { id: String, _: String -> seen += id }

        bridge.startCollectAll()
        assertEquals(1, calls.collected.size)
        sync(b)
        assertEquals(listOf(b), seen, "the user action still runs")
        bridge.collectFinished(calls.collected.single().first, ok = true)
        assertEquals(1, calls.collected.size, "the pass starts no further step")
        assertTrue(!bridge.collectAllRunning, "the pass ended")

        bridge.startCollectAll()
        oneFile(a, "f.parquet")
        bridge.collectFinished(calls.collected.last().first, ok = false)
        assertEquals(2, calls.collected.size, "a single file sync stops the pass too")
        assertEquals(listOf(b, a), seen)
    }

    @Test fun aUserActionOnAnotherSensorLeavesThePassGoing() {
        val state = CompanionState()
        val calls = Calls()
        val bridge = bridge(state = state, calls = calls)
        bridge.sensorOpened(a, "A", "st-a", "aabbccddeeff")
        bridge.sensorOpened(b, "B", "st-b", "112233445566")
        val other = "33333333-3333-3333-3333-333333333333"
        val seen = mutableListOf<String>()
        val sync = bridge.userFirst({ id: String -> seen += id })
        val open = bridge.userFirst({ id: String -> seen += id }, takesBluetooth = true)

        bridge.startCollectAll()
        sync(other)
        assertEquals(listOf(other), seen)
        assertTrue(bridge.collectAllRunning, "a sensor outside the pass leaves it going")
        open(other)
        bridge.collectFinished(calls.collected.single().first, ok = false)
        assertEquals(1, calls.collected.size, "an open stops a pass on Bluetooth, no further step")
        assertTrue(!bridge.collectAllRunning)
    }

    @Test fun collectAllSkipsTheOpenLink() {
        val state = CompanionState()
        val calls = Calls()
        val bridge = bridge(state = state, calls = calls)
        bridge.sensorOpened(a, "A", "st-a", "aabbccddeeff")
        bridge.sensorOpened(b, "B", "st-b", "112233445566")
        state.connectionStarted(a)
        bridge.startCollectAll()
        assertEquals(listOf<Pair<String, String?>>(b to null), calls.collected)
        assertEquals("A", bridge.nameFor(a))
        assertEquals("aq-b", bridge.nameFor("wifi:aq-b"))
    }

    @Test fun busyIdsMapAWifiSessionToItsKnownSensorThroughTheThing() {
        val known = listOf(
            KnownSensor(a, "A", "st-a", null, hasWifiAccess = false),
            KnownSensor(b, "B", "st-b", null, hasWifiAccess = true),
        )
        val active = listOf(
            ActiveLink(a.lowercase(), "A", LinkPhase.CONNECTED),
            ActiveLink("wifi:aq-b", "aq-b", LinkPhase.CONNECTED),
            ActiveLink("wifi:aq-gone", "aq-gone", LinkPhase.CONNECTING),
        )
        val busy = IosKnown.busyIds(active, mapOf("aq-b" to "st-b"), known)
        assertTrue(a in busy, "a Bluetooth link matches its known id without case")
        assertTrue(b in busy, "a Wi-Fi session matches the known sensor of its thing")
        assertTrue("wifi:aq-gone" in busy, "a session without a thing still names itself")
        assertTrue(IosKnown.busyIds(emptyList(), mapOf("aq-b" to "st-b"), known).isEmpty())
    }

    @Test fun collectAllSkipsASensorTheUserHoldsOnWifi() {
        val state = CompanionState()
        val calls = Calls()
        val bridge = bridge(state = state, calls = calls)
        bridge.sensorOpened(a, "A", "st-a", "aabbccddeeff")
        bridge.sensorOpened(b, "B", "st-b", "112233445566")
        bridge.credentialsChanged(listOf(b), listOf("st-b"), listOf("112233445566"))
        state.lanScanStarted()
        state.lanSensorsFound(listOf("aq-b"), listOf("st-b"))
        state.lanSessionChanged("aq-b", connected = true, busy = false, message = "", filesDone = 0, filesTotal = 0, bytesDone = 0)

        bridge.startCollectAll()
        assertEquals(listOf<Pair<String, String?>>(a to null), calls.collected, "the open Wi-Fi session is not taken over")
    }
}
