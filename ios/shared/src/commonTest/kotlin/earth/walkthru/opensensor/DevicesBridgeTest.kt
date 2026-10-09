package earth.walkthru.opensensor

import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.opensensor.devices.LinkPhase
import earth.walkthru.opensensor.strings.UiText
import kotlinx.coroutines.flow.emptyFlow
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
    }

    @Test fun glanceCountsFilesPerStation() {
        fun record(station: String, synced: Long, last: Long?) = ArchiveRecord(station, "f$synced", 1, 1, "sha", synced, "d", lastUtcNs = last)
        val glance = IosKnown.glance(listOf(record("s", 1, 100), record("s", 3, null), record("t", 2, 7)))
        assertEquals(2, glance.getValue("s").files)
        assertEquals(3L, glance.getValue("s").lastSyncedMs)
        assertEquals(100L, glance.getValue("s").lastDataUtcNs)
        assertNull(glance.getValue("s").lastDayPm25Mean, "iOS has no day mean yet")
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

    @Test fun onlyPeripheralIdsOpenOverBluetooth() {
        assertTrue(IosKnown.isPeripheral(a))
        assertFalse(IosKnown.isPeripheral("wifi:aq-b"))
        assertFalse(IosKnown.isPeripheral("AA:BB:CC:DD:EE:FF"))
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
}
