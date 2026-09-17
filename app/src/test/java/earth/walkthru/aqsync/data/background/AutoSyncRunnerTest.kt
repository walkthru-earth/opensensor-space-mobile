package earth.walkthru.aqsync.data.background

import earth.walkthru.aqsync.data.BackgroundSyncResult
import earth.walkthru.aqsync.data.ble.DeviceSession
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.fake.FakeTransport
import earth.walkthru.aqsync.data.lan.LanDevice
import earth.walkthru.aqsync.data.local.database.ArchivedFile
import earth.walkthru.aqsync.data.history.HistoryIndexer
import earth.walkthru.aqsync.data.local.database.ArchivedFileDao
import earth.walkthru.aqsync.data.local.database.KnownDevice
import earth.walkthru.aqsync.data.protocol.LanFraming
import earth.walkthru.aqsync.data.sync.ArchivedIdentity
import earth.walkthru.aqsync.data.sync.FileArchive
import earth.walkthru.aqsync.data.sync.FileOutcome
import earth.walkthru.aqsync.data.sync.SyncEngine
import earth.walkthru.aqsync.data.sync.SyncPlanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files

/**
 * The auto-sync worker's decision logic ([AutoSyncRunner] / [AutoSyncPolicy]) against a gateway
 * whose "sync" is the real LIST → plan → download path over the in-process [FakeTransport], so
 * "new files" and "nothing new" come from actual transfers, not canned numbers.
 */
class AutoSyncRunnerTest {
    private lateinit var tmp: File
    private lateinit var gateway: TestGateway

    private val bleAddr = "AA:BB:CC:DD:EE:01"
    private val otherAddr = "AA:BB:CC:DD:EE:02"

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("aqsync-autosync").toFile()
        gateway = TestGateway(tmp)
    }

    @After
    fun tearDown() {
        gateway.close()
        tmp.deleteRecursively()
    }

    private fun device(address: String = bleAddr, token: String? = TOKEN, autoSync: Boolean = true, station: String? = gateway.fake.station) =
        KnownDevice(address, "AQ-6b40", station, "441bf6e26b40", "v5", 1L, 1L, lanToken = token, lanPort = 47390, autoSync = autoSync)

    private fun onLan(station: String = gateway.fake.station) = mapOf(
        station to LanDevice("AQ-6b40", InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 42)), 47390, mapOf(LanFraming.TXT_STATION to station), 0L)
    )

    private fun run(onlyAddress: String? = null, allKnown: Boolean = false) =
        runBlocking { withTimeout(60_000) { AutoSyncRunner(gateway, lanBrowseMs = 10, log = gateway.log::add).run(onlyAddress, allKnown) } }

    // ------------------------------------------------------------------ policy

    @Test
    fun `policy - LAN first when the station is discovered and a token is stored, then Bluetooth when bonded`() {
        val links = AutoSyncPolicy.linksFor(device(), onLan(), bonded = true)
        assertEquals(2, links.size)
        assertTrue(links[0] is AutoSyncLink.Lan)
        assertEquals(AutoSyncLink.Ble, links[1])
    }

    @Test
    fun `policy - no token means no LAN even when discovered, no bond means no Bluetooth`() {
        assertEquals(listOf<AutoSyncLink>(AutoSyncLink.Ble), AutoSyncPolicy.linksFor(device(token = null), onLan(), bonded = true))
        assertTrue(AutoSyncPolicy.linksFor(device(token = null), onLan(), bonded = false).isEmpty())
        // Discovered under another station: not this device.
        assertEquals(listOf<AutoSyncLink>(AutoSyncLink.Ble), AutoSyncPolicy.linksFor(device(), onLan("other-station"), bonded = true))
        // Unresolved address: not connectable.
        val unresolved = mapOf(gateway.fake.station to LanDevice("AQ-6b40", null, 47390, mapOf(LanFraming.TXT_STATION to gateway.fake.station), 0L))
        assertTrue(AutoSyncPolicy.linksFor(device(), unresolved, bonded = false).isEmpty())
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
        assertEquals("AQ-6b40", report.summaryDeviceName)
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
    fun `interactive session with another device - one link at a time, everything skipped`() {
        gateway.devices += device()
        gateway.bonded += bleAddr
        gateway.session = otherAddr

        val report = run()

        assertTrue(gateway.calls.isEmpty())
        assertEquals("the app is connected to $otherAddr", report.devices.single().skipped)
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
        assertEquals(setOf(gateway.fake.station), gateway.lastBrowseStations)
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

    // ---------------------------------------------------------------- fixtures

    /**
     * Gateway whose device list, LAN browse result, bond table and current session are plain
     * fields, and whose `sync` does the real thing over one [FakeTransport]: LIST, [SyncPlanner]
     * against the in-memory archive table, [SyncEngine.download] for each missing file.
     */
    private class TestGateway(tmp: File) : AutoSyncGateway {
        val fake = FakeTransport(chunkDelayMs = 0, liveIntervalMs = 3_600_000)
        val dao = InMemoryDao()
        private val engine = FileArchive(tmp).let { a -> SyncEngine(a, dao, HistoryIndexer(a, dao)) }.also { it.windowRetryBackoffMs = 1 }

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

        val calls = ArrayList<Pair<String, String>>()
        val log = ArrayList<String>()
        var browses = 0
        var lastBrowseStations: Set<String> = emptySet()

        override suspend fun autoSyncDevices() = devices.filter { it.autoSync }.sortedByDescending { it.lastConnectedEpochMs }
        override suspend fun knownDevice(address: String) = devices.firstOrNull { it.address == address }
        override suspend fun allKnownDevices() = devices.sortedByDescending { it.lastConnectedEpochMs }
        override fun interactiveSessionAddress() = session
        override suspend fun browseLan(stations: Set<String>, timeoutMs: Long): Map<String, LanDevice> {
            browses++
            lastBrowseStations = stations
            return lan
        }

        override fun isBonded(address: String) = address in bonded

        override suspend fun sync(device: KnownDevice, link: AutoSyncLink): BackgroundSyncResult {
            val label = if (link is AutoSyncLink.Lan) "LAN" else "Bluetooth"
            val kind = if (link is AutoSyncLink.Lan) TransportKind.LAN else TransportKind.BLE
            calls += device.address to label
            if (label in unreachable) return BackgroundSyncResult(device.address, device.name, kind, 0, 0, 0, skipped = "connect failed")
            if (label in throwing) throw IOException("socket reset by peer")

            fake.connect()
            val s = DeviceSession(fake)
            try {
                val (files, _) = s.list()
                val archived = dao.rows.map { ArchivedIdentity(it.station, it.name, it.bytes, it.crc32) }
                val plan = SyncPlanner.plan(fake.station, files, archived)
                val outcomes = ArrayList<FileOutcome>()
                for (entry in plan.toDownload) {
                    if (cutAfterFiles != null && outcomes.size >= cutAfterFiles!!) break
                    outcomes += engine.download(s, fake.station, device.address, entry.name, entry.bytes, fake.maxRead) {}
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
                fake.disconnect()
            }
        }

        fun close() = fake.close()
    }

    private class InMemoryDao : ArchivedFileDao {
        val rows = ArrayList<ArchivedFile>()
        override fun observeAll(): Flow<List<ArchivedFile>> = flowOf(rows)
        override fun observeForStation(station: String): Flow<List<ArchivedFile>> = flowOf(rows)
        override suspend fun forStation(station: String) = rows.filter { it.station == station }
        override suspend fun get(station: String, name: String) = rows.firstOrNull { it.station == station && it.name == name }
        override fun observeCount(): Flow<Int> = flowOf(rows.size)
        override fun observeTotalBytes(): Flow<Long> = flowOf(rows.sumOf { it.bytes })
        override suspend fun unindexed(limit: Int) = rows.filter { it.indexedAtEpochMs == null || it.indexError != null }.take(limit)
        override fun observeUnindexedCount(): Flow<Int> = flowOf(rows.count { it.indexedAtEpochMs == null || it.indexError != null })
        override suspend fun withUtcBetween(station: String, fromNs: Long, toNs: Long) =
            rows.filter { it.station == station && it.indexError == null && (it.utcRows ?: 0) > 0 && (it.lastUtcNs ?: Long.MIN_VALUE) >= fromNs && (it.firstUtcNs ?: Long.MAX_VALUE) < toNs }
        override suspend fun indexedForStation(station: String) = rows.filter { it.station == station && it.indexed }
        override suspend fun stations() = rows.map { it.station }.distinct()
        override suspend fun upsert(file: ArchivedFile) {
            rows.removeAll { it.station == file.station && it.name == file.name }
            rows += file
        }
        override suspend fun delete(station: String, name: String) {
            rows.removeAll { it.station == station && it.name == name }
        }
    }

    private companion object {
        const val TOKEN = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
    }
}
