package earth.walkthru.aqsync.data

import android.content.ContextWrapper
import earth.walkthru.aqsync.data.ble.AqTransport
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.DeviceSession
import earth.walkthru.aqsync.data.ble.FrameDirection
import earth.walkthru.aqsync.data.ble.RawCommandRefusedException
import earth.walkthru.aqsync.data.ble.TransportException
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.fake.FakeTransport
import earth.walkthru.aqsync.data.history.HistoryDeriver
import earth.walkthru.aqsync.data.lan.LanConnectException
import earth.walkthru.aqsync.data.lan.LanDevice
import earth.walkthru.aqsync.data.lan.LanFailure
import earth.walkthru.aqsync.data.lan.LanTargets
import earth.walkthru.aqsync.data.local.database.ArchivedFile
import earth.walkthru.aqsync.data.local.database.ArchivedFileDao
import earth.walkthru.aqsync.data.local.database.KnownDevice
import earth.walkthru.aqsync.data.local.database.KnownDeviceDao
import earth.walkthru.aqsync.data.local.database.StationEpoch
import earth.walkthru.aqsync.data.local.database.StationEpochDao
import earth.walkthru.aqsync.data.local.security.LanTokenCipherContract
import earth.walkthru.aqsync.data.local.security.SecureKnownDeviceDao
import earth.walkthru.aqsync.data.protocol.ConfigPatch
import earth.walkthru.aqsync.data.protocol.LanFraming
import earth.walkthru.aqsync.data.protocol.Opcode
import earth.walkthru.aqsync.data.sync.ClockSyncMode
import earth.walkthru.aqsync.data.sync.FileArchive
import earth.walkthru.aqsync.data.sync.FileOutcome
import earth.walkthru.aqsync.data.sync.androidSyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Characterization tests for [DeviceConnectionRepository] on virtual time, with the in-process
 * [FakeTransport] standing in for the device and in-memory DAOs. They pin the behavior that the
 * planned split of the repository (production-readiness plan, W7) must keep.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceConnectionRepositoryTest {
    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("repo-test").toFile()
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `connection synchronizes UTC before listing without a user action`() = harnessTest { h ->
        h.clockEpochOffset = 1_789_581_931_987L
        h.repo.connect(ADDRESS, "AQ-6b40", fake = true)
        h.until { h.repo.plan.value != null }
        val sent = h.repo.session.value!!.frames.records.value.filter { it.direction == FrameDirection.TX }.map { it.type }
        assertTrue(sent.indexOf(Opcode.SET_TIME) in 0 until sent.indexOf(Opcode.LIST))
        assertEquals(1, sent.count { it == Opcode.SET_TIME })
        assertEquals(1, h.repo.status.value?.status?.utc)
    }

    @Test
    fun `a refused automatic clock still lists original files`() = harnessTest { h ->
        // Virtual epoch zero is deliberately refused by the firmware-compatible fake.
        h.repo.connect(ADDRESS, "AQ-6b40", fake = true)
        h.until { h.repo.plan.value != null }
        assertTrue(h.repo.plan.value!!.toDownload.isNotEmpty())
        assertEquals(0, h.repo.status.value?.status?.utc)
    }

    @Test
    fun `sync all lists again and downloads a file finalized after the previous listing`() = harnessTest { h ->
        h.repo.connect(ADDRESS, "AQ-6b40", fake = true)
        h.until { h.repo.plan.value != null }
        h.repo.syncAll()
        h.until { h.archived.rows.size == h.device.files.size && !h.repo.sync.value.running }
        h.until { h.repo.plan.value?.missingCount == 0 }
        h.events.clear()
        val previousListCount = h.factory.requests.count { it == Opcode.LIST }
        val newer = h.newFile()
        h.device.files += newer

        h.repo.syncAll()
        h.until { h.archived.rows.any { it.name == newer.name } && !h.repo.sync.value.running }

        assertEquals(previousListCount + 2, h.factory.requests.count { it == Opcode.LIST })
        assertEquals(listOf(newer.name), h.repo.sync.value.outcomes.map { it.name })
        assertFalse(h.events.contains(DeviceEvent.Info(DeviceMessage.UpToDate)))
    }

    @Test
    fun `smart sync uses its fresh listing instead of the plan from connection`() = harnessTest { h ->
        h.clockEpochOffset = 1_789_581_931_987L
        h.repo.connect(ADDRESS, "AQ-6b40", fake = true)
        h.until { h.repo.plan.value != null }
        val newer = h.newFile()
        h.device.files += newer

        h.repo.smartSync(ClockSyncMode.AUTOMATIC)
        h.until { h.archived.rows.size == h.device.files.size && !h.repo.sync.value.running }

        assertTrue(h.repo.sync.value.outcomes.any { it.name == newer.name })
        assertFalse(h.factory.requests.contains(Opcode.FLUSH))
    }

    @Test
    fun `failed pre sync listing retains old files but never declares up to date`() = harnessTest { h ->
        h.repo.connect(ADDRESS, "AQ-6b40", fake = true)
        h.until { h.repo.plan.value != null }
        h.repo.syncAll()
        h.until { h.archived.rows.size == h.device.files.size && !h.repo.sync.value.running }
        h.until { h.repo.plan.value?.missingCount == 0 }
        h.events.clear()
        val previous = h.repo.manifest.value.last
        val previousOpenCount = h.factory.requests.count { it == Opcode.OPEN }
        h.device.files += h.newFile()
        h.factory.listFailure = TransportException("SD listing unavailable")

        h.repo.syncAll()
        h.until { h.repo.manifest.value is ManifestState.Error && !h.repo.busy.value }

        assertEquals(previous, h.repo.manifest.value.last)
        assertEquals(previousOpenCount, h.factory.requests.count { it == Opcode.OPEN })
        assertTrue(h.events.contains(DeviceEvent.Error(DeviceMessage.FileListNotReady)))
        assertFalse(h.events.contains(DeviceEvent.Info(DeviceMessage.UpToDate)))
    }

    @Test
    fun `overlapping sync requests reserve preparation only once`() = harnessTest { h ->
        h.repo.connect(ADDRESS, "AQ-6b40", fake = true)
        h.until { h.repo.plan.value != null }
        val before = h.factory.requests.count { it == Opcode.LIST }

        h.repo.syncAll()
        h.repo.smartSync(ClockSyncMode.AUTOMATIC)
        h.until { h.archived.rows.size == h.device.files.size && !h.repo.sync.value.running }

        assertTrue(h.events.contains(DeviceEvent.Info(DeviceMessage.Busy)))
        assertEquals(before + 2, h.factory.requests.count { it == Opcode.LIST })
        assertFalse(h.repo.busy.value)
    }

    @Test
    fun `cancelling sync before preparation starts releases its operation slot`() = harnessTest { h ->
        h.repo.connect(ADDRESS, "AQ-6b40", fake = true)
        h.until { h.repo.plan.value != null }
        val before = h.factory.requests.count { it == Opcode.LIST }

        h.repo.syncAll()
        h.repo.cancelSync()
        runCurrent()

        assertEquals(before, h.factory.requests.count { it == Opcode.LIST })
        assertFalse(h.repo.busy.value)
        assertFalse(h.repo.sync.value.running)
        h.repo.syncAll()
        h.until { h.archived.rows.size == h.device.files.size && !h.repo.sync.value.running }
    }

    @Test
    fun `cancelling during list retains the previous snapshot without leaving refresh running`() = harnessTest { h ->
        h.repo.connect(ADDRESS, "AQ-6b40", fake = true)
        h.until { h.repo.plan.value != null }
        val previous = h.repo.manifest.value.last
        h.factory.listDelayMs = 60_000
        h.repo.syncAll()
        h.until { h.repo.manifest.value.isLoading }

        h.repo.cancelSync()
        h.until { !h.repo.busy.value }

        assertTrue(h.repo.manifest.value is ManifestState.Error)
        assertEquals(previous, h.repo.manifest.value.last)
        assertFalse(h.repo.sync.value.running)
        assertTrue(h.archived.rows.isEmpty())
        h.factory.listDelayMs = 0
        h.repo.syncAll()
        h.until { h.archived.rows.size == h.device.files.size && !h.repo.sync.value.running }
    }

    @Test
    fun `lost link reconnects and resumes the interrupted sync`() = harnessTest(chunkDelayMs = 1) { h ->
        h.repo.connect(ADDRESS, "AQ-6b40", fake = true)
        h.until { h.repo.plan.value != null }
        val wanted = h.repo.plan.value!!.toDownload.map { it.name }.toSet()

        h.repo.syncAll()
        h.until { h.repo.sync.value.filesDone >= 1 }
        h.device.disconnect()

        h.until(120_000) { h.archived.rows.map { it.name }.toSet() == wanted && !h.repo.sync.value.running }
        assertTrue(h.repo.connectionState.value.isReady)
        assertNull(h.repo.reconnecting.value)
        assertTrue(h.repo.sync.value.failures.isEmpty())
    }

    @Test
    fun `resume after reconnect uses the new session's listing, not the previous plan`() = harnessTest(chunkDelayMs = 1) { h ->
        h.repo.connect(ADDRESS, "AQ-6b40", fake = true)
        h.until { h.repo.plan.value != null }
        h.repo.syncAll()
        h.until { h.repo.sync.value.filesDone >= 1 }
        val done = h.archived.rows.map { it.name }.toSet()

        // While the link is down the card changes: every file not yet archived is gone and one new
        // file appears. A resume driven by the previous plan would OPEN names that no longer exist.
        val replacement = FakeTransport.FakeFile(
            "station=${h.device.station}/year=2026/month=09/day=17/data_0900_${h.device.bootId}_20000-20089-90.parquet",
            h.device.files.first { it.name in done }.bytes,
        )
        h.device.disconnect()
        h.device.files.removeAll { it.name !in done }
        h.device.files += replacement

        h.until(120_000) { replacement.name in h.archived.rows.map { it.name } && !h.repo.sync.value.running }
        val outcomes = h.repo.sync.value.outcomes
        assertEquals(listOf(replacement.name), outcomes.map { it.name })
        assertTrue(outcomes.single() is FileOutcome.Synced)
    }

    @Test
    fun `cancelReconnect during a reconnect stops it`() = harnessTest { h ->
        h.repo.connect(ADDRESS, "AQ-6b40", fake = true)
        h.until { h.repo.connectionState.value.isReady }
        h.device.disconnect()
        h.until { h.repo.reconnecting.value != null }

        h.repo.cancelReconnect()
        h.advance(60_000)

        assertNull(h.repo.reconnecting.value)
        assertFalse(h.repo.connectionState.value.isReady)
        assertEquals(1, h.factory.fakeConnects)
    }

    @Test
    fun `rejected LAN token is cleared and the Bluetooth session renews it`() = harnessTest { h ->
        h.knownDao.upsert(h.enrolled(token = OLD_TOKEN))
        h.lan.devices.value = mapOf(h.device.station to h.lanTarget())
        h.factory.lanFailure = LanConnectException(LanFailure.AUTH_FAILED)

        h.repo.connect(ADDRESS, "AQ-6b40")
        h.until { h.repo.connectionState.value.isReady }

        assertEquals(TransportKind.FAKE, h.repo.activeTransport.value)
        val lan = h.factory.lanTransports.single()
        assertTrue("the probe transport is closed", lan.closed)
        val renewed = h.knownDao.lanToken(ADDRESS)
        assertNotNull("a fresh token was fetched over the Bluetooth session", renewed)
        assertFalse(renewed == OLD_TOKEN)
        assertTrue(h.events.contains(DeviceEvent.Info(DeviceMessage.LanFallback(LanFailure.AUTH_FAILED))))
    }

    @Test
    fun `unexpected LAN failure closes the probe and falls back to Bluetooth`() = harnessTest { h ->
        h.knownDao.upsert(h.enrolled(token = OLD_TOKEN))
        h.lan.devices.value = mapOf(h.device.station to h.lanTarget())
        h.factory.lanFailure = IllegalStateException("socket factory exploded")

        h.repo.connect(ADDRESS, "AQ-6b40")
        h.until { h.repo.connectionState.value.isReady }

        assertTrue(h.factory.lanTransports.single().closed)
        assertEquals(TransportKind.FAKE, h.repo.activeTransport.value)
        assertEquals("a non-auth failure keeps the stored token", OLD_TOKEN, h.knownDao.lanToken(ADDRESS))
        assertTrue("the detail stays in the log", h.events.contains(DeviceEvent.Info(DeviceMessage.LanFallback(null))))
    }

    @Test
    fun `LAN only with no discovered target never constructs Bluetooth`() = harnessTest { h ->
        h.knownDao.upsert(h.enrolled(token = OLD_TOKEN))

        h.repo.connect(ADDRESS, "AQ-6b40", preference = TransportPreference.LAN_ONLY)
        runCurrent()

        assertEquals(0, h.factory.fakeConnects)
        assertTrue(h.factory.lanTransports.isEmpty())
        assertFalse(h.repo.connectionState.value.isReady)
    }

    @Test
    fun `LAN only failure closes probe without constructing Bluetooth`() = harnessTest { h ->
        h.knownDao.upsert(h.enrolled(token = OLD_TOKEN))
        h.lan.devices.value = mapOf(h.device.station to h.lanTarget())
        h.factory.lanFailure = LanConnectException(LanFailure.UNREACHABLE)

        h.repo.connect(ADDRESS, "AQ-6b40", preference = TransportPreference.LAN_ONLY)
        runCurrent()

        assertTrue(h.factory.lanTransports.single().closed)
        assertEquals(0, h.factory.fakeConnects)
        assertFalse(h.repo.connectionState.value.isReady)
        assertEquals(OLD_TOKEN, h.knownDao.lanToken(ADDRESS))
        assertFalse(h.events.any { it is DeviceEvent.Info && it.message is DeviceMessage.LanFallback })
    }

    @Test
    fun `overlapping config commands are refused instead of clearing each other's busy flag`() = harnessTest { h ->
        h.repo.connect(ADDRESS, "AQ-6b40", fake = true)
        h.until { h.repo.connectionState.value.isReady && h.repo.config.value != null }

        h.repo.applyConfig(ConfigPatch.build { lanOn(true) })
        h.repo.applyConfig(ConfigPatch.build { lanOn(false) })
        runCurrent()
        assertTrue(h.repo.busy.value)
        assertTrue(h.events.contains(DeviceEvent.Info(DeviceMessage.Busy)))
        try {
            h.repo.applySensorConfig(ConfigPatch.build { lanOn(true) })
            fail("a second command must not run while the first holds the gate")
        } catch (e: IllegalStateException) {
            // expected
        }
        h.until { !h.repo.busy.value }
    }

    @Test
    fun `a saved setting is reported as a typed code`() = harnessTest { h ->
        h.repo.connect(ADDRESS, "AQ-6b40", fake = true)
        h.until { h.repo.connectionState.value.isReady && h.repo.config.value != null }

        h.repo.applyConfig(ConfigPatch.build { lanOn(true) }, DeviceMessage.ConfigChange.LAN_ON)
        h.until { h.events.any { it is DeviceEvent.Info && it.message is DeviceMessage.ConfigSaved } }

        val saved = h.events.filterIsInstance<DeviceEvent.Info>().map { it.message }.filterIsInstance<DeviceMessage.ConfigSaved>().single()
        assertEquals(DeviceMessage.ConfigChange.LAN_ON, saved.change)
    }

    @Test
    fun `clearing bonds and a default save report which settings changed`() = harnessTest { h ->
        h.repo.connect(ADDRESS, "AQ-6b40", fake = true)
        h.until { h.repo.connectionState.value.isReady && h.repo.config.value != null }

        h.repo.clearBonds()
        h.until { h.events.any { it is DeviceEvent.Info && it.message is DeviceMessage.ConfigSaved } }
        val saved = h.events.filterIsInstance<DeviceEvent.Info>().map { it.message }.filterIsInstance<DeviceMessage.ConfigSaved>().single()
        assertEquals(DeviceMessage.ConfigChange.BONDS_CLEARED, saved.change)
        h.until { !h.repo.busy.value }

        h.repo.applyConfig(ConfigPatch.build { lanOn(false) })
        h.until { h.events.count { it is DeviceEvent.Info && it.message is DeviceMessage.ConfigSaved } == 2 }
        val second = h.events.filterIsInstance<DeviceEvent.Info>().map { it.message }.filterIsInstance<DeviceMessage.ConfigSaved>().last()
        assertEquals("the default change is a plain settings save", DeviceMessage.ConfigChange.SETTINGS, second.change)
    }

    @Test
    fun `raw command refuses REBOOT and SET_CONFIG in the data layer`() = runTest {
        val device = FakeTransport(chunkDelayMs = 0, liveIntervalMs = 3_600_000, dispatcher = StandardTestDispatcher(testScheduler))
        device.connect()
        try {
            val session = DeviceSession(device)
            for (op in listOf(Opcode.REBOOT, Opcode.SET_CONFIG)) {
                try {
                    session.rawCommand(byteArrayOf(op.toByte()))
                    fail("${Opcode.name(op)} must be refused")
                } catch (e: RawCommandRefusedException) {
                    assertEquals(op, e.op)
                }
            }
            assertTrue(session.frames.records.value.isEmpty())
            assertTrue(device.connectionState.value.isReady)
        } finally {
            device.close()
        }
    }

    // ---------------------------------------------------------------- harness

    /**
     * Runs [block] with a fresh [Harness] and always shuts it down: the fake's live loop and any
     * reconnect would otherwise keep the virtual clock busy forever after the test body.
     */
    private fun harnessTest(chunkDelayMs: Long = 0, block: suspend TestScope.(Harness) -> Unit) = runTest {
        val h = Harness(this, chunkDelayMs)
        try {
            block(h)
        } finally {
            h.shutdown()
        }
    }

    private inner class Harness(val test: TestScope, chunkDelayMs: Long = 0) {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val device = FakeTransport(address = ADDRESS, chunkDelayMs = chunkDelayMs, liveIntervalMs = 3_600_000, dispatcher = dispatcher).also {
            // The 1 MiB benchmark file only slows the tests down.
            it.files.removeAll { f -> f.name.startsWith("benchmarks/") }
        }
        val factory = TestTransports(device)
        val rawKnown = InMemoryKnownDeviceDao()
        val knownDao = SecureKnownDeviceDao(rawKnown, PrefixCipher())
        val archived = InMemoryArchivedFileDao()
        val lan = TestLanTargets()
        val events = CopyOnWriteArrayList<DeviceEvent>()
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)

        var clockEpochOffset = 0L
        val repo: DeviceConnectionRepository

        init {
            val fileArchive = FileArchive(tmp)
            repo = DeviceConnectionRepository(
                context = ContextWrapper(null),
                transportFactory = factory,
                knownDeviceDao = knownDao,
                lanCredentials = knownDao,
                archivedFileDao = archived,
                archive = fileArchive,
                syncEngine = androidSyncEngine(fileArchive, archived, windowRetryBackoffMs = 1) { test.testScheduler.currentTime },
                lanDiscoveryState = lan,
                lanWifiLock = { null },
                historyDeriver = { HistoryDeriver.Result(0, 0, 0) },
                companionPresence = { _, _ -> },
                collectionBudget = CollectionBudget(),
                identityClaims = DeviceIdentityClaims(),
                stationEpochDao = InMemoryStationEpochDao(),
                applicationScope = appScope,
                io = dispatcher,
                clock = { clockEpochOffset + test.testScheduler.currentTime },
                monotonic = test.testScheduler.timeSource,
            )
            repo.bind(ADDRESS)
            appScope.launch { repo.events.collect { events += it } }
        }

        fun shutdown() {
            appScope.cancel()
            device.close()
        }

        fun enrolled(token: String?) = KnownDevice(
            address = ADDRESS,
            name = "AQ-6b40",
            station = device.station,
            deviceId = device.deviceId,
            firmware = "fake",
            lastSeenEpochMs = 1,
            lastConnectedEpochMs = 1,
            lanToken = token,
            lanPort = 47390,
        )

        fun newFile() = FakeTransport.FakeFile(
            "station=${device.station}/year=2026/month=09/day=17/data_0900_${device.bootId}_20000-20089-90.parquet",
            device.files.first().bytes,
        )

        fun lanTarget() = LanDevice(
            serviceName = "AQ-6b40",
            host = InetAddress.getLoopbackAddress(),
            port = 47390,
            txt = mapOf(LanFraming.TXT_STATION to device.station, LanFraming.TXT_DEV to device.deviceId),
            lastSeenEpochMs = 0,
        )

        fun advance(ms: Long) {
            test.advanceTimeBy(ms)
            test.runCurrent()
        }

        /** Advance virtual time in small steps until [condition] holds, or fail after [timeoutMs]. */
        fun until(timeoutMs: Long = 30_000, condition: () -> Boolean) {
            var waited = 0L
            test.runCurrent()
            while (!condition()) {
                if (waited >= timeoutMs) fail("condition not met within $timeoutMs ms of virtual time")
                advance(STEP_MS)
                waited += STEP_MS
            }
        }
    }

    /** BLE and fake requests get the one [FakeTransport]; LAN requests get a transport that fails. */
    private class TestTransports(private val device: FakeTransport) : TransportSource {
        var lanFailure: Exception = LanConnectException(LanFailure.UNREACHABLE)
        val lanTransports = CopyOnWriteArrayList<FailingLanTransport>()
        var fakeConnects = 0
        var listFailure: Exception? = null
        var listDelayMs = 0L
        val requests = CopyOnWriteArrayList<Int>()
        private val observedDevice = object : AqTransport by device {
            override suspend fun writeControl(request: ByteArray) {
                val op = request.first().toInt() and 0xff
                requests += op
                if (op == Opcode.LIST) {
                    delay(listDelayMs)
                    listFailure?.let { throw it }
                }
                device.writeControl(request)
            }
        }

        override fun ble(address: String): AqTransport = counted()
        override fun bleUnattended(address: String, connectTimeoutMs: Long): AqTransport = counted()
        override fun lan(address: String, name: String?, host: InetAddress, port: Int, token: ByteArray): AqTransport =
            FailingLanTransport(address, lanFailure).also { lanTransports += it }
        override fun fake(address: String): AqTransport = counted()
        override fun isFake(transport: AqTransport): Boolean = transport === observedDevice

        private fun counted(): AqTransport {
            fakeConnects++
            return observedDevice
        }
    }

    private class FailingLanTransport(override val address: String, private val failure: Exception) : AqTransport {
        @Volatile var closed = false
        override val displayName: String = "AQ-6b40"
        override val kind = TransportKind.LAN
        override val endpoint = "127.0.0.1:47390"
        private val state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        override val connectionState: StateFlow<ConnectionState> = state
        override val mtu = 1027
        override val statusNotifications: Flow<ByteArray> = emptyFlow()
        override val liveNotifications: Flow<ByteArray> = emptyFlow()
        override val responseFrames: ReceiveChannel<ByteArray> = Channel()
        override suspend fun connect() {
            state.value = ConnectionState.Authenticating
            throw failure
        }
        override suspend fun disconnect() = Unit
        override suspend fun readInfo(): ByteArray = throw TransportException("not connected")
        override suspend fun readStatus(): ByteArray = throw TransportException("not connected")
        override suspend fun readLive(): ByteArray = throw TransportException("not connected")
        override suspend fun writeControl(request: ByteArray) = throw TransportException("not connected")
        override fun close() {
            closed = true
            state.value = ConnectionState.Disconnected
        }
    }

    private class TestLanTargets : LanTargets {
        override val devices = MutableStateFlow<Map<String, LanDevice>>(emptyMap())
        override val error = MutableStateFlow<String?>(null)
    }

    private class PrefixCipher : LanTokenCipherContract {
        override fun encrypt(tokenHex: String) = if (tokenHex.startsWith("enc1:")) tokenHex else "enc1:$tokenHex"
        override fun decrypt(stored: String) = stored.removePrefix("enc1:")
        override fun migrationRequired() = false
        override fun markMigrationComplete() = Unit
    }

    private class InMemoryKnownDeviceDao : KnownDeviceDao {
        private val state = MutableStateFlow<List<KnownDevice>>(emptyList())
        private val rows get() = state.value
        override fun observeAll(): Flow<List<KnownDevice>> = state
        override suspend fun get(address: String) = rows.firstOrNull { it.address == address }
        override suspend fun upsert(device: KnownDevice) = replace(device)
        override suspend fun insertIfAbsent(device: KnownDevice): Long {
            if (get(device.address) != null) return -1
            replace(device)
            return 1
        }
        override suspend fun touch(address: String, name: String?, epochMs: Long) {
            get(address)?.let { replace(it.copy(lastSeenEpochMs = epochMs, name = name ?: it.name)) }
        }
        override suspend fun setLanToken(address: String, tokenHex: String?, port: Int?) {
            get(address)?.let { replace(it.copy(lanToken = tokenHex, lanPort = port)) }
        }
        override suspend fun findByStation(station: String) = rows.firstOrNull { it.station == station }
        override suspend fun setAutoSync(address: String, enabled: Boolean) {
            get(address)?.let { replace(it.copy(autoSync = enabled)) }
        }
        override suspend fun autoSyncDevices() = rows.filter { it.autoSync }
        override suspend fun allByLastConnected() = rows
        override fun observeAutoSyncCount(): Flow<Int> = flowOf(rows.count { it.autoSync })
        override suspend fun setCompanionAssociation(address: String, associationId: Int?) {
            get(address)?.let { replace(it.copy(companionAssociationId = associationId)) }
        }
        override suspend fun autoSyncLanCount() = rows.count { it.autoSync && it.lanToken != null }
        override suspend fun delete(address: String) {
            state.value = rows.filterNot { it.address == address }
        }

        private fun replace(device: KnownDevice) {
            state.value = rows.filterNot { it.address == device.address } + device
        }
    }

    private class InMemoryArchivedFileDao : ArchivedFileDao {
        private val state = MutableStateFlow<List<ArchivedFile>>(emptyList())
        val rows: List<ArchivedFile> get() = state.value
        override fun observeAll(): Flow<List<ArchivedFile>> = state
        override fun observeForStation(station: String): Flow<List<ArchivedFile>> = flowOf(rows.filter { it.station == station })
        override suspend fun forStation(station: String) = rows.filter { it.station == station }
        override suspend fun get(station: String, name: String) = rows.firstOrNull { it.station == station && it.name == name }
        override fun observeCount(): Flow<Int> = flowOf(rows.size)
        override fun observeTotalBytes(): Flow<Long> = flowOf(rows.sumOf { it.bytes })
        override suspend fun unindexed(limit: Int) = rows.filter { it.indexedAtEpochMs == null || it.indexError != null }.take(limit)
        override fun observeUnindexedCount(): Flow<Int> = flowOf(rows.count { it.indexedAtEpochMs == null || it.indexError != null })
        override suspend fun withUtcBetween(station: String, fromNs: Long, toNs: Long) = emptyList<ArchivedFile>()
        override suspend fun indexedForStation(station: String) = rows.filter { it.station == station && it.indexed }
        override suspend fun stations() = rows.map { it.station }.distinct()
        override suspend fun upsert(file: ArchivedFile) {
            state.value = rows.filterNot { it.station == file.station && it.name == file.name } + file
        }
        override suspend fun insertIfAbsent(file: ArchivedFile): Long {
            if (rows.any { it.station == file.station && it.name == file.name }) return -1L
            state.value = rows + file
            return rows.size.toLong()
        }
        override suspend fun delete(station: String, name: String) {
            state.value = rows.filterNot { it.station == station && it.name == name }
        }
    }

    private class InMemoryStationEpochDao : StationEpochDao {
        private val rows = CopyOnWriteArrayList<StationEpoch>()
        override suspend fun recordIfAbsent(epoch: StationEpoch): Long {
            if (rows.any { it.address == epoch.address && it.station == epoch.station }) return -1
            rows += epoch
            return 1
        }
        override suspend fun forDevice(address: String) = rows.filter { it.address == address }
        override fun observeForDevice(address: String): Flow<List<StationEpoch>> = flowOf(forDeviceNow(address))
        private fun forDeviceNow(address: String) = rows.filter { it.address == address }
    }

    private companion object {
        /** Built at runtime: a 64-hex literal looks like a leaked key to the secret scan. */
        val OLD_TOKEN = "0f".repeat(32)
        const val ADDRESS = "E0:5A:1B:F6:6B:40"
        const val STEP_MS = 50L
    }
}
