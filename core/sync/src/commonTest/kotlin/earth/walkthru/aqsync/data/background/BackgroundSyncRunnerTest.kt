package earth.walkthru.aqsync.data.background

import earth.walkthru.aqsync.data.BackgroundSyncResult
import earth.walkthru.aqsync.data.ble.TransportKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shared background run against a fake gateway, the same rules Android's worker and iOS's BGTask follow. */
class BackgroundSyncRunnerTest {
    private data class Dev(override val address: String, override val name: String? = "OS-6b40", override val station: String? = "st-$address", override val hasLanToken: Boolean = true) : BackgroundSyncDevice

    private data class Target(val station: String, override val isConnectable: Boolean = true) : BackgroundLanTarget

    private class FakeGateway(
        val auto: List<Dev> = emptyList(),
        val all: List<Dev> = auto,
        val lan: Map<String, Target> = emptyMap(),
        val bonded: Set<String> = emptySet(),
    ) : BackgroundSyncGateway<Dev, Target> {
        var owned: String? = null
        val browsed = mutableListOf<Set<String>>()
        val calls = mutableListOf<Pair<String, String>>()

        /** Per (address, link label) outcome. Missing means one new file. */
        val outcomes = mutableMapOf<Pair<String, String>, (Dev) -> BackgroundSyncResult>()

        override suspend fun autoSyncDevices() = auto
        override suspend fun knownDevice(address: String) = all.firstOrNull { it.address == address }
        override suspend fun allKnownDevices() = all
        override fun interactiveSessionAddress() = owned
        override suspend fun browseLan(stations: Set<String>, timeoutMs: Long): Map<String, Target> {
            browsed += stations
            return lan.filterKeys { it in stations }
        }
        override fun isBonded(address: String) = address in bonded
        override suspend fun sync(device: Dev, link: BackgroundSyncLink<Target>): BackgroundSyncResult {
            val label = if (link is BackgroundSyncLink.Lan) "LAN" else "BLE"
            calls += device.address to label
            val kind = if (link is BackgroundSyncLink.Lan) TransportKind.LAN else TransportKind.BLE
            return outcomes[device.address to label]?.invoke(device) ?: BackgroundSyncResult(device.address, device.name, kind, 1, 100, 0)
        }
    }

    private fun skipped(d: Dev) = BackgroundSyncResult(d.address, d.name, null, 0, 0, 0, skipped = "connect failed")

    @Test
    fun linksPreferLanThenBond() {
        val d = Dev("a")
        val links = BackgroundSyncPolicy.linksFor(d, mapOf("st-a" to Target("st-a")), bonded = true)
        assertEquals(2, links.size)
        assertTrue(links[0] is BackgroundSyncLink.Lan)
        assertEquals(BackgroundSyncLink.Ble, links[1])
    }

    @Test
    fun noTokenOrUnresolvedSkipsLan() {
        val lan = mapOf("st-a" to Target("st-a"))
        assertEquals(listOf<BackgroundSyncLink<Target>>(BackgroundSyncLink.Ble), BackgroundSyncPolicy.linksFor(Dev("a", hasLanToken = false), lan, bonded = true))
        assertTrue(BackgroundSyncPolicy.linksFor(Dev("a"), mapOf("st-a" to Target("st-a", isConnectable = false)), bonded = false).isEmpty())
        assertTrue(BackgroundSyncPolicy.linksFor(Dev("a"), lan, bonded = true, lanOnly = true).single() is BackgroundSyncLink.Lan)
        assertTrue(BackgroundSyncPolicy.linksFor(Dev("a"), emptyMap<String, Target>(), bonded = true, lanOnly = true).isEmpty())
    }

    @Test
    fun dueAfterOnePeriodOrNeverRun() {
        val p = BackgroundSyncPolicy.PERIOD_MS
        assertTrue(BackgroundSyncPolicy.isDue(null, 0))
        assertFalse(BackgroundSyncPolicy.isDue(1_000, 1_000 + p - 1))
        assertTrue(BackgroundSyncPolicy.isDue(1_000, 1_000 + p))
        // A clock that went backwards does not stall the device for a period.
        assertTrue(BackgroundSyncPolicy.isDue(5_000, 1_000))
        val due = BackgroundSyncPolicy.dueDevices(listOf(Dev("a"), Dev("b"), Dev("c")), mapOf("a" to 10L, "b" to 10L - p), nowMs = 10)
        assertEquals(listOf("b", "c"), due.map { it.address })
    }

    @Test
    fun retriesAreBoundedAndBackedOff() {
        val broken = BackgroundSyncReport(listOf(DeviceRunReport(Dev("a"), listOf(BackgroundSyncLink.Ble), BackgroundSyncResult("a", null, TransportKind.BLE, 0, 0, 0, incomplete = true))))
        assertTrue(BackgroundSyncPolicy.shouldRetry(broken, 0))
        assertTrue(BackgroundSyncPolicy.shouldRetry(broken, BackgroundSyncPolicy.MAX_RETRIES - 1))
        assertFalse(BackgroundSyncPolicy.shouldRetry(broken, BackgroundSyncPolicy.MAX_RETRIES))
        assertFalse(BackgroundSyncPolicy.shouldRetry(BackgroundSyncReport<Dev>(emptyList()), 0))
        assertEquals(BackgroundSyncPolicy.BACKOFF_MS, BackgroundSyncPolicy.backoffMs(0))
        assertEquals(BackgroundSyncPolicy.BACKOFF_MS * 4, BackgroundSyncPolicy.backoffMs(2))
    }

    @Test
    fun runBrowsesOnceAndFallsThroughToBond() = runTest {
        val a = Dev("a")
        val b = Dev("b", hasLanToken = false)
        val gw = FakeGateway(auto = listOf(a, b), lan = mapOf("st-a" to Target("st-a")), bonded = setOf("a", "b"))
        gw.outcomes["a" to "LAN"] = ::skipped
        val log = mutableListOf<String>()
        val report = BackgroundSyncRunner(gw, lanBrowseMs = 10, log = log::add).run()
        assertEquals(listOf(setOf("st-a")), gw.browsed)
        assertEquals(listOf("LAN", "BLE"), gw.calls.filter { it.first == "a" }.map { it.second })
        assertEquals(listOf("BLE"), gw.calls.filter { it.first == "b" }.map { it.second })
        assertEquals(2, report.newFiles)
        assertTrue(report.shouldNotify)
        assertEquals("2 loggers", report.summaryDeviceName)
        assertFalse(report.transientFailure)
        assertTrue(log.any { it == "auto-sync OS-6b40 over LAN: connect failed" })
    }

    @Test
    fun ownedDeviceIsLeftAlone() = runTest {
        val gw = FakeGateway(auto = listOf(Dev("a")), bonded = setOf("a"))
        gw.owned = "a"
        val report = BackgroundSyncRunner(gw).run()
        assertTrue(gw.calls.isEmpty())
        assertEquals("the app has an interactive session with this device", report.devices.single().skipped)
        assertFalse(report.shouldNotify)
        assertEquals("logger", report.summaryDeviceName)
    }

    @Test
    fun userOpeningTheDeviceStopsTheSecondLink() = runTest {
        val gw = FakeGateway(auto = listOf(Dev("a")), lan = mapOf("st-a" to Target("st-a")), bonded = setOf("a"))
        gw.outcomes["a" to "LAN"] = { d ->
            gw.owned = "a"
            skipped(d)
        }
        val report = BackgroundSyncRunner(gw).run()
        assertEquals(listOf("LAN"), gw.calls.map { it.second })
        assertEquals("connect failed", report.devices.single().skipped)
    }

    @Test
    fun unreachableAndLanOnlyReasons() = runTest {
        val gw = FakeGateway(auto = listOf(Dev("a")), bonded = setOf("a"))
        assertEquals("not on the LAN (Bluetooth not tried on a Wi-Fi trigger)", BackgroundSyncRunner(gw).run(lanOnly = true).devices.single().skipped)
        val none = FakeGateway(auto = listOf(Dev("a")))
        assertEquals("not on the LAN and not bonded", BackgroundSyncRunner(none).run().devices.single().skipped)
        assertTrue(none.browsed.isNotEmpty())
    }

    @Test
    fun exceptionIsRecordedAndNextLinkTried() = runTest {
        val gw = FakeGateway(auto = listOf(Dev("a")), lan = mapOf("st-a" to Target("st-a")), bonded = setOf("a"))
        gw.outcomes["a" to "LAN"] = { error("socket closed") }
        gw.outcomes["a" to "BLE"] = ::skipped
        val report = BackgroundSyncRunner(gw).run()
        val dev = report.devices.single()
        assertNotNull(dev.error)
        assertEquals(2, dev.tried.size)
        assertTrue(report.transientFailure)
        assertFalse(report.shouldNotify)
    }

    @Test
    fun failuresNotifyWithTheDeviceName() = runTest {
        val gw = FakeGateway(auto = listOf(Dev("a", name = null), Dev("b")), bonded = setOf("a", "b"))
        gw.outcomes["a" to "BLE"] = { d -> BackgroundSyncResult(d.address, "OS-0001", TransportKind.BLE, 0, 0, 2) }
        gw.outcomes["b" to "BLE"] = { d -> BackgroundSyncResult(d.address, d.name, TransportKind.BLE, 0, 0, 0) }
        val report = BackgroundSyncRunner(gw).run()
        assertEquals(2, report.failures)
        assertTrue(report.shouldNotify)
        assertEquals("OS-0001", report.summaryDeviceName)
        assertEquals(listOf("a"), report.devicesWithNews.map { it.device.address })
    }

    @Test
    fun explicitDevicesAndOnlyAddress() = runTest {
        val a = Dev("a")
        val b = Dev("b")
        val gw = FakeGateway(auto = listOf(a), all = listOf(a, b), bonded = setOf("a", "b"))
        assertEquals(listOf("b"), BackgroundSyncRunner(gw).run(onlyAddress = "b").devices.map { it.device.address })
        assertEquals(listOf("a", "b"), BackgroundSyncRunner(gw).run(allKnown = true).devices.map { it.device.address })
        assertEquals(listOf("b"), BackgroundSyncRunner(gw).run(devices = listOf(b, b)).devices.map { it.device.address })
        assertTrue(BackgroundSyncRunner(gw).run(onlyAddress = "zz").devices.isEmpty())
        assertNull(BackgroundSyncRunner(FakeGateway()).run().devices.firstOrNull())
    }

    @Test
    fun atMostTwoDevicesAtOnce() = runTest {
        var active = 0
        var peak = 0
        val gate = CompletableDeferred<Unit>()
        val devices = listOf(Dev("a"), Dev("b"), Dev("c"))
        val gw = object : BackgroundSyncGateway<Dev, Target> {
            override suspend fun autoSyncDevices() = devices
            override suspend fun knownDevice(address: String) = null
            override suspend fun allKnownDevices() = devices
            override fun interactiveSessionAddress(): String? = null
            override suspend fun browseLan(stations: Set<String>, timeoutMs: Long) = emptyMap<String, Target>()
            override fun isBonded(address: String) = true
            override suspend fun sync(device: Dev, link: BackgroundSyncLink<Target>): BackgroundSyncResult {
                active++
                peak = maxOf(peak, active)
                if (active == 2) gate.complete(Unit)
                gate.await()
                active--
                return BackgroundSyncResult(device.address, device.name, TransportKind.BLE, 0, 0, 0)
            }
        }
        BackgroundSyncRunner(gw).run()
        assertEquals(2, peak)
    }
}
