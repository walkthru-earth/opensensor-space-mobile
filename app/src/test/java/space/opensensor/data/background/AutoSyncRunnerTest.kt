package space.opensensor.data.background

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.opensensor.data.BackgroundSyncResult
import space.opensensor.data.ble.DeviceSession
import space.opensensor.data.ble.TransportKind
import space.opensensor.data.fake.FakeTransport
import space.opensensor.data.lan.LanDevice
import space.opensensor.data.local.database.ArchivedFile
import space.opensensor.data.local.database.DerivedArchivedFileQueries
import space.opensensor.data.local.database.KnownDevice
import space.opensensor.data.protocol.LanFraming
import space.opensensor.data.sync.ArchivedIdentity
import space.opensensor.data.sync.FileArchive
import space.opensensor.data.sync.FileOutcome
import space.opensensor.data.sync.SyncEngine
import space.opensensor.data.sync.SyncPlanner
import space.opensensor.data.sync.androidSyncEngine
import space.opensensor.data.sync.thingParquetFixture
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files

/**
 * The auto-sync worker's decision logic ([AutoSyncRunner] / [BackgroundSyncPolicy]) against a gateway
 * whose "sync" is the real LIST → plan → download path over the in-process [FakeTransport], so
 * "new files" and "nothing new" come from actual transfers, not canned numbers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoSyncRunnerTest {
    private lateinit var tmp: File
    private lateinit var gateway: TestGateway

    private val bleAddr = "AA:BB:CC:DD:EE:01"
    private val otherAddr = "AA:BB:CC:DD:EE:02"

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("opensensor-autosync").toFile()
        gateway = TestGateway(tmp)
    }

    @After
    fun tearDown() {
        gateway.close()
        tmp.deleteRecursively()
    }

    private fun device(address: String = bleAddr, token: String? = TOKEN, autoSync: Boolean = true, thing: String? = gateway.fake.thing) =
        KnownDevice(address, "OS-6b40", thing, "441bf6e26b40", "v5", 1L, 1L, lanToken = token, lanPort = 47390, autoSync = autoSync)

    private fun onLan(thing: String = gateway.fake.thing) = mapOf(
        thing to LanDevice("OS-6b40", InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 42)), 47390, mapOf(LanFraming.TXT_THING to thing), 0L)
    )

    private fun run(onlyAddress: String? = null, allKnown: Boolean = false, lanOnly: Boolean = false): AutoSyncReport {
        var report: AutoSyncReport? = null
        // The real session and fake response handlers share virtual time. Keep the demo's files
        // and simulated OPEN/READ delays without racing wall-clock receive timeout slices.
        runTest(gateway.dispatcher) {
            report = withTimeout(60_000) {
                AutoSyncRunner(gateway, lanBrowseMs = 10, log = gateway.log::add).run(onlyAddress, allKnown, lanOnly)
            }
        }
        return requireNotNull(report)
    }

    // ------------------------------------------------------------------ policy

    @Test
    fun `policy - LAN first when the thing is discovered and a token is stored, then Bluetooth when bonded`() {
        val links = BackgroundSyncPolicy.linksFor(device(), onLan(), bonded = true)
        assertEquals(2, links.size)
        assertTrue(links[0] is BackgroundSyncLink.Lan)
        assertEquals(BackgroundSyncLink.Ble, links[1])
    }

    @Test
    fun `policy - no token means no LAN even when discovered, no bond means no Bluetooth`() {
        assertEquals(listOf<AutoSyncLink>(BackgroundSyncLink.Ble), BackgroundSyncPolicy.linksFor(device(token = null), onLan(), bonded = true))
        assertTrue(BackgroundSyncPolicy.linksFor(device(token = null), onLan(), bonded = false).isEmpty())
        // Discovered under another thing: not this device.
        assertEquals(listOf<AutoSyncLink>(BackgroundSyncLink.Ble), BackgroundSyncPolicy.linksFor(device(), onLan("other-thing"), bonded = true))
        // Unresolved address: not connectable.
        val unresolved = mapOf(gateway.fake.thing to LanDevice("OS-6b40", null, 47390, mapOf(LanFraming.TXT_THING to gateway.fake.thing), 0L))
        assertTrue(BackgroundSyncPolicy.linksFor(device(), unresolved, bonded = false).isEmpty())
    }

    @Test
    fun `policy - lanOnly (Wi-Fi trigger) never offers Bluetooth, even when bonded`() {
        val links = BackgroundSyncPolicy.linksFor(device(), onLan(), bonded = true, lanOnly = true)
        assertEquals(1, links.size)
        assertTrue(links[0] is BackgroundSyncLink.Lan)
        assertTrue(BackgroundSyncPolicy.linksFor(device(), emptyMap(), bonded = true, lanOnly = true).isEmpty())
    }

    // ------------------------------------------------------------------ runner

    @Test
    fun `first run pulls every missing file over LAN, never tries Bluetooth, and wants a summary`() {
        gateway.devices += device()
        gateway.lan = onLan()
        gateway.bonded += bleAddr

        val report = run()

        assertEquals(listOf(bleAddr to "LAN"), gateway.calls)
        assertEquals(gateway.fake.files.size, report.newFiles)
        assertEquals(gateway.fake.files.sumOf { it.bytes.size.toLong() }, report.newBytes)
        assertEquals(0, report.failures)
        assertTrue(report.shouldNotify)
        assertEquals("OS-6b40", report.summaryDeviceName)
        assertFalse(report.transientFailure)
        assertEquals(gateway.fake.files.size, gateway.dao.rows.size)
        assertEquals(TransportKind.LAN, report.devices.single().result?.transport)
    }

    @Test
    fun `second run finds nothing new and posts no notification`() {
        gateway.devices += device()
        gateway.bonded += bleAddr
        run()
        gateway.calls.clear()

        val report = run()

        assertEquals(listOf(bleAddr to "Bluetooth"), gateway.calls)
        assertEquals(0, report.newFiles)
        assertEquals(0, report.failures)
        assertFalse("nothing new must not notify", report.shouldNotify)
        assertFalse(report.transientFailure)
        assertTrue(report.devices.single().result!!.attempted)
    }

    @Test
    fun `LAN not reachable falls back to Bluetooth when bonded, and stops after the first attempted link`() {
        gateway.devices += device()
        gateway.lan = onLan()
        gateway.bonded += bleAddr
        gateway.unreachable += "LAN"

        val report = run()

        assertEquals(listOf(bleAddr to "LAN", bleAddr to "Bluetooth"), gateway.calls)
        val dev = report.devices.single()
        assertEquals(2, dev.tried.size)
        assertEquals(TransportKind.BLE, dev.result?.transport)
        assertEquals(gateway.fake.files.size, report.newFiles)
    }

    @Test
    fun `Wi-Fi trigger run - LAN gone means the device is skipped, Bluetooth is not tried`() {
        gateway.devices += device()
        gateway.lan = onLan()
        gateway.bonded += bleAddr
        gateway.unreachable += "LAN"

        val report = run(lanOnly = true)

        assertEquals(listOf(bleAddr to "LAN"), gateway.calls)
        assertFalse(report.shouldNotify)
        assertFalse(report.transientFailure)
    }

    @Test
    fun `Wi-Fi trigger run - device not on the LAN is skipped before any link`() {
        gateway.devices += device()
        gateway.bonded += bleAddr

        val report = run(lanOnly = true)

        assertTrue(gateway.calls.isEmpty())
        assertEquals("not on the LAN (Bluetooth not tried on a Wi-Fi trigger)", report.devices.single().skipped)
    }

    @Test
    fun `LAN not reachable and not bonded finishes quietly - no link tried, no notification`() {
        gateway.devices += device()
        gateway.lan = onLan()
        gateway.unreachable += "LAN"

        val report = run()

        assertEquals(listOf(bleAddr to "LAN"), gateway.calls)
        val dev = report.devices.single()
        assertNull(dev.result?.attempted?.takeIf { it })
        assertEquals("connect failed", dev.skipped)
        assertFalse(report.shouldNotify)
        assertFalse("an absent device is not a transient failure", report.transientFailure)
    }

    @Test
    fun `device off the LAN and not bonded is skipped without touching any link`() {
        gateway.devices += device()

        val report = run()

        assertTrue(gateway.calls.isEmpty())
        assertEquals("not on the LAN and not bonded", report.devices.single().skipped)
        assertFalse(report.shouldNotify)
    }

    @Test
    fun `interactive session with the device - worker leaves it alone`() {
        gateway.devices += device()
        gateway.lan = onLan()
        gateway.bonded += bleAddr
        gateway.session = bleAddr

        val report = run()

        assertTrue("no link may be tried while the user holds the session", gateway.calls.isEmpty())
        assertEquals("the app has an interactive session with this device", report.devices.single().skipped)
        assertFalse(report.shouldNotify)
        assertFalse(report.transientFailure)
    }

    @Test
    fun `interactive session with another device leaves this device available`() {
        gateway.devices += device()
        gateway.bonded += bleAddr
        gateway.session = otherAddr

        val report = run()

        assertEquals(listOf(bleAddr to "Bluetooth"), gateway.calls)
        assertTrue(report.devices.single().result!!.attempted)
    }

    @Test
    fun `exception on LAN is transient, Bluetooth is still tried`() {
        gateway.devices += device()
        gateway.lan = onLan()
        gateway.bonded += bleAddr
        gateway.throwing += "LAN"

        val report = run()

        assertEquals(listOf(bleAddr to "LAN", bleAddr to "Bluetooth"), gateway.calls)
        val dev = report.devices.single()
        assertEquals(gateway.fake.files.size, dev.newFiles)
        assertTrue(report.shouldNotify)
        // The Bluetooth run completed, so the LAN exception does not make the run transient any more.
        assertNull(dev.error)
        assertFalse(report.transientFailure)
    }

    @Test
    fun `exception on the only link is a transient failure worth a retry`() {
        gateway.devices += device(token = null)
        gateway.bonded += bleAddr
        gateway.throwing += "Bluetooth"

        val report = run()

        assertEquals(listOf(bleAddr to "Bluetooth"), gateway.calls)
        val dev = report.devices.single()
        assertNotNull(dev.error)
        assertTrue(report.transientFailure)
        assertFalse(report.shouldNotify)
    }

    @Test
    fun `a transfer that broke off is transient`() {
        gateway.devices += device(token = null)
        gateway.bonded += bleAddr
        gateway.cutAfterFiles = 1

        val report = run()

        val r = report.devices.single().result!!
        assertEquals(1, r.newFiles)
        assertTrue(r.incomplete)
        assertTrue(report.transientFailure)
        assertTrue("the one file that arrived is still announced", report.shouldNotify)
    }

    @Test
    fun `periodic run only sees devices with the switch on, most recent first, one LAN browse for all`() {
        gateway.devices += device(address = otherAddr, autoSync = false)
        gateway.devices += device()
        gateway.bonded += bleAddr
        gateway.bonded += otherAddr

        val report = run()

        assertEquals(listOf(bleAddr to "Bluetooth"), gateway.calls)
        assertEquals(1, report.devices.size)
        assertEquals(1, gateway.browses)
        assertEquals(setOf(gateway.fake.thing), gateway.lastBrowseThings)
    }

    @Test
    fun `no device with a token skips the LAN browse entirely`() {
        gateway.devices += device(token = null)
        gateway.bonded += bleAddr

        run()

        assertEquals(0, gateway.browses)
    }

    @Test
    fun `manual sync-now targets one device even with its switch off`() {
        gateway.devices += device(address = otherAddr, autoSync = false)
        gateway.bonded += otherAddr

        val report = run(onlyAddress = otherAddr)

        assertEquals(listOf(otherAddr to "Bluetooth"), gateway.calls)
        assertEquals(gateway.fake.files.size, report.newFiles)
        assertTrue(run(onlyAddress = "00:00:00:00:00:00").devices.isEmpty())
    }

    @Test
    fun `collect from all loggers - visits a device whose switch is off, the periodic run does not`() {
        gateway.devices += device(autoSync = false)
        gateway.bonded += bleAddr

        val periodic = run()
        assertTrue("periodic run ignores a switched-off device", periodic.devices.isEmpty())
        assertTrue(gateway.calls.isEmpty())

        val collect = run(allKnown = true)
        assertEquals(listOf(bleAddr to "Bluetooth"), gateway.calls)
        assertEquals(gateway.fake.files.size, collect.newFiles)
        assertTrue(collect.shouldNotify)
    }

    @Test
    fun `no auto-sync device at all - empty report, nothing to notify or retry`() {
        val report = run()
        assertTrue(report.devices.isEmpty())
        assertFalse(report.shouldNotify)
        assertFalse(report.transientFailure)
        assertEquals("logger", report.summaryDeviceName)
    }

    @Test
    fun `collect all transfers distinct devices simultaneously using real sessions and engine`() = runTest(gateway.dispatcher) {
        val payload = checkNotNull(javaClass.classLoader?.getResourceAsStream("parquet/device/v1-none-90rows-dated.parquet")).use { it.readBytes() }
        val second = FakeTransport(address = otherAddr, chunkDelayMs = 0, liveIntervalMs = 3_600_000, thing = "thing-b", deviceId = "441bf6e26b41", dispatcher = gateway.dispatcher)
        gateway.peers[otherAddr] = second
        listOf(gateway.fake, second).forEach { fake ->
            fake.files.clear()
            fake.files += FakeTransport.FakeFile("legacy-parquet/readings.parquet", thingParquetFixture(payload, fake.thing))
        }
        gateway.devices += device(token = null)
        gateway.devices += device(address = otherAddr, token = null, thing = second.thing)
        gateway.bonded += listOf(bleAddr, otherAddr)
        val bothEntered = CompletableDeferred<Unit>()
        var entered = 0
        gateway.beforeSync = {
            entered++
            if (entered == 2) bothEntered.complete(Unit)
            bothEntered.await()
        }
        val report = withTimeout(10_000) { AutoSyncRunner(gateway).run(allKnown = true) }
        assertEquals(2, report.newFiles)
        assertEquals(0, report.failures)
        assertEquals(2, gateway.dao.rows.size)
        assertEquals(setOf(gateway.fake.thing, second.thing), gateway.dao.rows.map { it.thing }.toSet())
        assertTrue(gateway.dao.rows.all { it.isIndexed })
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * Gateway whose device list, LAN browse result, bond table and current session are plain
     * fields, and whose `sync` does the real thing over one [FakeTransport]: LIST, [SyncPlanner]
     * against the in-memory archive table, [SyncEngine.download] for each missing file.
     */
    private class TestGateway(tmp: File) : AutoSyncGateway {
        val dispatcher = StandardTestDispatcher(TestCoroutineScheduler())
        val fake = FakeTransport(chunkDelayMs = 0, liveIntervalMs = 3_600_000, dispatcher = dispatcher)
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val peers = java.util.concurrent.ConcurrentHashMap<String, FakeTransport>()
        var beforeSync: suspend (KnownDevice) -> Unit = {}
        val dao = InMemoryDao()
        private val engine = androidSyncEngine(FileArchive(tmp), dao, windowRetryBackoffMs = 1, io = dispatcher)

        val devices = ArrayList<KnownDevice>()
        var session: String? = null
        var lan: Map<String, LanDevice> = emptyMap()
        val bonded = HashSet<String>()

        /** Link labels ("LAN" / "Bluetooth") whose connect "fails" → a `skipped` result, like the repository's. */
        val unreachable = HashSet<String>()

        /** Link labels whose sync throws an IOException mid-way. */
        val throwing = HashSet<String>()

        /** Stop the transfer after this many files (link lost) → `incomplete`. */
        var cutAfterFiles: Int? = null

        val calls = java.util.Collections.synchronizedList(ArrayList<Pair<String, String>>())
        val log = java.util.Collections.synchronizedList(ArrayList<String>())
        var browses = 0
        var lastBrowseThings: Set<String> = emptySet()

        override suspend fun autoSyncDevices() = devices.filter { it.autoSync }.sortedByDescending { it.lastConnectedEpochMs }
        override suspend fun knownDevice(address: String) = devices.firstOrNull { it.address == address }
        override suspend fun allKnownDevices() = devices.sortedByDescending { it.lastConnectedEpochMs }
        override fun interactiveSessionAddress() = session
        override suspend fun browseLan(things: Set<String>, timeoutMs: Long): Map<String, LanDevice> {
            browses++
            lastBrowseThings = things
            return lan
        }

        override fun isBonded(address: String) = address in bonded

        override suspend fun sync(device: KnownDevice, link: AutoSyncLink): BackgroundSyncResult {
            val label = if (link is BackgroundSyncLink.Lan) "LAN" else "Bluetooth"
            val kind = if (link is BackgroundSyncLink.Lan) TransportKind.LAN else TransportKind.BLE
            calls += device.address to label
            if (label in unreachable) return BackgroundSyncResult(device.address, device.name, kind, 0, 0, 0, skipped = "connect failed")
            if (label in throwing) throw IOException("socket reset by peer")

            val fake = peers[device.address] ?: this.fake
            beforeSync(device)
            val s = DeviceSession.over(fake, scope)
            s.link.open()
            try {
                val (files, _) = s.list()
                val archived = dao.rows.map { ArchivedIdentity(it.thing, it.name, it.bytes, it.crc32) }
                val plan = SyncPlanner.plan(fake.thing, files, archived)
                val outcomes = ArrayList<FileOutcome>()
                for (entry in plan.toDownload) {
                    if (cutAfterFiles != null && outcomes.size >= cutAfterFiles!!) break
                    outcomes += engine.download(s, fake.thing, device.address, entry.name, entry.bytes, fake.maxRead) {}
                }
                val synced = outcomes.filterIsInstance<FileOutcome.Synced>()
                return BackgroundSyncResult(
                    address = device.address,
                    deviceName = fake.displayName,
                    transport = kind,
                    newFiles = synced.size,
                    newBytes = synced.sumOf { it.bytes },
                    failures = outcomes.size - synced.size,
                    incomplete = outcomes.size < plan.toDownload.size,
                )
            } finally {
                s.link.close("done")
                fake.disconnect()
            }
        }

        fun close() { fake.close(); peers.values.forEach { it.close() } }
    }

    private class InMemoryDao : DerivedArchivedFileQueries {
        val rows = java.util.concurrent.CopyOnWriteArrayList<ArchivedFile>()
        override fun observeAll(): Flow<List<ArchivedFile>> = flowOf(rows)
        override fun observeForThing(thing: String): Flow<List<ArchivedFile>> = flowOf(rows)
        override suspend fun forThing(thing: String) = rows.filter { it.thing == thing }
        override suspend fun get(thing: String, name: String) = rows.firstOrNull { it.thing == thing && it.name == name }
        override fun observeCount(): Flow<Int> = flowOf(rows.size)
        override fun observeTotalBytes(): Flow<Long> = flowOf(rows.sumOf { it.bytes })
        override suspend fun unindexed(limit: Int) = rows.filter { it.indexedAtEpochMs == null || it.indexError != null }.take(limit)
        override fun observeUnindexedCount(): Flow<Int> = flowOf(rows.count { it.indexedAtEpochMs == null || it.indexError != null })
        override suspend fun withUtcBetween(thing: String, fromNs: Long, toNs: Long) =
            rows.filter { it.thing == thing && it.indexError == null && (it.utcRows ?: 0) > 0 && (it.lastUtcNs ?: return@filter false) >= fromNs && (it.firstUtcNs ?: return@filter false) < toNs }
        override suspend fun indexedForThing(thing: String) = rows.filter { it.thing == thing && it.isIndexed }
        override suspend fun things() = rows.map { it.thing }.distinct()
        override suspend fun upsert(file: ArchivedFile) {
            rows.removeAll { it.thing == file.thing && it.name == file.name }
            rows += file
        }
        override suspend fun insertIfAbsent(file: ArchivedFile): Long {
            if (rows.any { it.thing == file.thing && it.name == file.name }) return -1L
            rows += file
            return rows.size.toLong()
        }
        override suspend fun delete(thing: String, name: String) {
            rows.removeAll { it.thing == thing && it.name == name }
        }
    }

    private companion object {
        const val TOKEN = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
    }
}
