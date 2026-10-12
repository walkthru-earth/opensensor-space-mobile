package earth.walkthru.aqsync.data

import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.archive.PartialMeta
import earth.walkthru.aqsync.archive.PartialWriter
import earth.walkthru.aqsync.collection.CollectionFailure
import earth.walkthru.aqsync.collection.CollectionState
import earth.walkthru.aqsync.collection.KeepAlive
import earth.walkthru.aqsync.collection.KeepAliveKind
import earth.walkthru.aqsync.collection.Lease
import earth.walkthru.aqsync.collection.LinkPreference
import earth.walkthru.aqsync.collection.PrepareStep
import earth.walkthru.aqsync.collection.StopSource
import earth.walkthru.aqsync.collection.SyncEndReason
import earth.walkthru.aqsync.data.archive.FileArchiveStore
import earth.walkthru.aqsync.data.archive.RoomArchiveIndex
import earth.walkthru.aqsync.data.archive.asRecordIndexer
import earth.walkthru.aqsync.data.ble.AqTransport
import earth.walkthru.aqsync.data.ble.BleConnectionFailure
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.DeviceSession
import earth.walkthru.aqsync.data.ble.FrameDirection
import earth.walkthru.aqsync.data.ble.RawCommandRefusedException
import earth.walkthru.aqsync.data.ble.TransportException
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.ble.TransportUnavailableException
import earth.walkthru.aqsync.data.fake.FakeTransport
import earth.walkthru.aqsync.data.history.HistoryDeriver
import earth.walkthru.aqsync.data.history.HistoryIndexer
import earth.walkthru.aqsync.data.history.HistoryRefresher
import earth.walkthru.aqsync.data.lan.LanConnectException
import earth.walkthru.aqsync.data.lan.LanDevice
import earth.walkthru.aqsync.data.lan.LanFailure
import earth.walkthru.aqsync.data.lan.LanTargets
import earth.walkthru.aqsync.data.local.database.ArchivedFile
import earth.walkthru.aqsync.data.local.database.ArchivedFileDao
import earth.walkthru.aqsync.data.local.database.DerivedArchivedFileQueries
import earth.walkthru.aqsync.data.local.database.KnownDevice
import earth.walkthru.aqsync.data.local.database.KnownDeviceDao
import earth.walkthru.aqsync.data.local.database.StationEpoch
import earth.walkthru.aqsync.data.local.database.StationEpochDao
import earth.walkthru.aqsync.data.local.security.LanTokenCipherContract
import earth.walkthru.aqsync.data.local.security.SecureKnownDeviceDao
import earth.walkthru.aqsync.data.protocol.ConfigPatch
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.LanFraming
import earth.walkthru.aqsync.data.protocol.Opcode
import earth.walkthru.aqsync.data.sync.ArchiveCollector
import earth.walkthru.aqsync.data.sync.FileArchive
import earth.walkthru.aqsync.data.sync.FileOutcome
import earth.walkthru.aqsync.data.sync.SyncEngine
import earth.walkthru.aqsync.link.AuthKind
import earth.walkthru.aqsync.link.DisconnectCause
import earth.walkthru.aqsync.link.FrameSink
import earth.walkthru.aqsync.link.LinkClosedException
import earth.walkthru.aqsync.link.LinkInfo
import earth.walkthru.aqsync.link.LinkState
import earth.walkthru.aqsync.link.UnavailableKind
import earth.walkthru.aqsync.link.retryable
import earth.walkthru.aqsync.storage.FakeStorageBudget
import earth.walkthru.aqsync.storage.StorageException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
 * Facade and link ownership regressions on virtual time, with [FakeTransport] and in-memory DAOs.
 * Collection state, synchronous protection and fresh listings use the same controller as the app.
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
    fun `a refused process lease ends the request before any device operation`() = harnessTest { h ->
        h.refuseProcess = true
        h.repo.syncAll()
        assertEquals(1, h.processAcquires)
        val failed = h.repo.collectionState.value as CollectionState.Failed
        assertEquals(CollectionFailure.KeepAliveRefused(KeepAliveKind.PROCESS), failed.failure)
        runCurrent()
        assertEquals(0, h.factory.fakeConnects)
        assertTrue(h.factory.requests.isEmpty())
        assertFalse(h.repo.foregroundNeeded.value)
        assertFalse(h.repo.busy.value)
    }

    @Test
    fun `preparation owns process protection and foreground timeout retains its stop reason`() = harnessTest { h ->
        h.factory.listDelayMs = 60_000
        h.repo.syncAll()
        assertEquals("protection is acquired on the tap", 1, h.processLeases)
        assertTrue(h.repo.foregroundNeeded.value)
        h.until { (h.repo.collectionState.value as? CollectionState.Preparing)?.step == PrepareStep.LIST }
        assertEquals(1, h.processLeases)
        h.repo.stop(StopSource.FGS_TIMEOUT)
        val stopped = h.repo.collectionState.value as CollectionState.Stopped
        assertEquals(StopSource.FGS_TIMEOUT, stopped.by)
        h.until { h.processLeases == 0 && !h.repo.busy.value }
        assertTrue(h.archived.rows.isEmpty())
        assertNull(h.repo.session.value)
        assertEquals(1, h.factory.fakeConnects)
    }

    @Test
    fun `worker Bluetooth uses the unattended factory and its own process protection`() = harnessTest { h ->
        h.knownDao.upsert(h.enrolled(token = null))
        val work = async { h.repo.syncInBackground(ADDRESS, null, owner = "synthetic-worker", protection = KeepAlive.None) }
        h.until { work.isCompleted }
        val result = work.await()
        assertEquals(h.device.files.size, result.newFiles)
        assertEquals(h.device.files.sumOf { it.bytes.size.toLong() }, result.newBytes)
        assertEquals(1, h.factory.unattendedConnects)
        assertEquals(0, h.factory.interactiveConnects)
        assertEquals(15_000L, h.factory.unattendedTimeout)
        assertEquals(0, h.processAcquires)
        assertNull(h.repo.backgroundOwner.value)
    }

    @Test
    fun `worker failure retains files and bytes already archived before link loss`() = harnessTest(chunkDelayMs = 1) { h ->
        h.knownDao.upsert(h.enrolled(token = null))
        val work = async { h.repo.syncInBackground(ADDRESS, null, owner = "synthetic-worker", protection = KeepAlive.None) }
        h.until { h.archived.rows.isNotEmpty() }
        val saved = h.archived.rows.toList()
        assertTrue(saved.size < h.device.files.size)
        h.factory.rejectReconnect = true
        h.device.disconnect()
        h.until(120_000) { work.isCompleted }
        val result = work.await()
        assertEquals(saved.size, result.newFiles)
        assertEquals(saved.sumOf { it.bytes }, result.newBytes)
        assertEquals(1, result.failures)
        assertFalse(result.incomplete)
        assertNull(h.repo.session.value)
    }

    @Test
    fun `worker counts settled storage failures and a terminal authentication failure`() = harnessTest(chunkDelayMs = 1) { h ->
        h.knownDao.upsert(h.enrolled(token = null))
        h.partialFailuresRemaining = 2
        val work = async { h.repo.syncInBackground(ADDRESS, null, owner = "synthetic-worker", protection = KeepAlive.None) }
        h.until { h.repo.sync.value.filesDone >= 2 }
        assertEquals(0, h.partialFailuresRemaining)
        h.factory.reconnectFailure = DisconnectCause.Auth(AuthKind.BOND_MISSING)
        h.device.disconnect()
        h.until(120_000) { work.isCompleted }
        val result = work.await()
        assertEquals(3, result.failures)
        assertNull(result.skipped)
        assertEquals(0, result.newFiles)
        assertFalse(result.incomplete)
        // The repair banner outlives the closed session and the reason is said once.
        assertTrue(BleConnectionFailure.needsPairingRepair(h.repo.connectionState.value))
        assertEquals(1, h.events.count { it == DeviceEvent.Error(DeviceMessage.PairingRepair) })
        assertFalse(h.events.any { (it as? DeviceEvent.Error)?.message is DeviceMessage.SyncEnded })
    }

    @Test
    fun `worker unavailable reopen after settled failures is attempted and counted`() = harnessTest(chunkDelayMs = 1) { h ->
        h.knownDao.upsert(h.enrolled(token = null))
        h.partialFailuresRemaining = 2
        val work = async { h.repo.syncInBackground(ADDRESS, null, owner = "synthetic-worker", protection = KeepAlive.None) }
        h.until { h.repo.sync.value.filesDone >= 2 }
        h.factory.reconnectFailure = DisconnectCause.Unreachable
        h.device.disconnect()
        h.until(120_000) { work.isCompleted }
        val result = work.await()
        assertEquals(3, result.failures)
        assertNull(result.skipped)
        assertEquals(0, result.newFiles)
        assertTrue(result.incomplete)
    }

    @Test
    fun `worker Done retains prior storage failures when reconnect LIST omits those names`() = harnessTest(chunkDelayMs = 1) { h ->
        h.knownDao.upsert(h.enrolled(token = null))
        h.partialFailuresRemaining = 2
        val work = async { h.repo.syncInBackground(ADDRESS, null, owner = "synthetic-worker", protection = KeepAlive.None) }
        h.until { h.repo.sync.value.filesDone >= 2 }
        assertEquals(2, h.failedStorageNames.size)
        assertTrue(h.archived.rows.isEmpty())
        h.device.disconnect()
        h.device.files.removeAll { it.name in h.failedStorageNames }
        assertEquals(1, h.device.files.size)
        h.until(120_000) { work.isCompleted }
        val result = work.await()
        assertTrue(h.repo.collectionState.value is CollectionState.Done)
        assertEquals(1, result.newFiles)
        assertEquals(h.device.files.single().bytes.size.toLong(), result.newBytes)
        assertEquals(2, result.failures)
        assertNull(result.skipped)
        assertFalse(result.incomplete)
        assertEquals(listOf(h.device.files.single().name), h.archived.rows.map { it.name })
        assertEquals(1, h.archived.rows.size)
        val receipt = h.archived.rows.single()
        val again = async { h.repo.syncInBackground(ADDRESS, null, owner = "synthetic-worker", protection = KeepAlive.None) }
        h.until { again.isCompleted }
        assertEquals(0, again.await().newFiles)
        assertEquals(listOf(receipt.name), h.committedNames)
        assertEquals(receipt, h.archived.rows.single())
        assertArrayEquals(h.device.files.single().bytes, FileArchive(tmp).fileFor(receipt.station, receipt.name).readBytes())
    }

    @Test
    fun `timeout during delayed CLOSED retains the committed worker receipt`() = harnessTest { h ->
        h.knownDao.upsert(h.enrolled(token = null))
        h.device.closedDelayMs = 60_000
        val work = async { h.repo.syncInBackground(ADDRESS, null, owner = "synthetic-worker", protection = KeepAlive.None) }
        h.until { h.archived.rows.isNotEmpty() }
        val saved = h.archived.rows.toList()
        h.repo.stop(StopSource.FGS_TIMEOUT)
        h.until(120_000) { work.isCompleted }
        val result = work.await()
        assertEquals(saved.size, result.newFiles)
        assertEquals(saved.sumOf { it.bytes }, result.newBytes)
        assertEquals(StopSource.FGS_TIMEOUT, (h.repo.collectionState.value as CollectionState.Stopped).by)
        assertTrue(result.incomplete)
        assertNull(h.repo.session.value)
        assertFalse(h.repo.busy.value)
    }

    @Test
    fun `acknowledged clock uses verified session identity after the link closes`() = harnessTest { h ->
        val pending = async { h.repo.lendLink(ADDRESS, LinkPreference.ANY) }
        h.until { pending.isCompleted }
        val lent = pending.await()
        lent.session.link.close("synthetic post-ack disconnect")
        assertTrue(lent.session.link.state.value is LinkState.Closed)
        h.owner.collectionClockSet(lent.session, Frame.TimeSet(1_789_581_931L, 1234L))
        assertEquals(listOf(0), h.clockAnchorsBeforeList)
        lent.release()
        h.owner.closeReturnedLend()
        val replacement = async { h.repo.lendLink(ADDRESS, LinkPreference.ANY) }
        h.until { replacement.isCompleted }
        val newLink = replacement.await()
        val oldReceipt = runCatching { h.owner.collectionClockSet(lent.session, Frame.TimeSet(1_789_581_932L, 5678L)) }
        assertTrue(oldReceipt.exceptionOrNull() is IllegalStateException)
        assertEquals(listOf(0), h.clockAnchorsBeforeList)
        newLink.release()
        h.owner.closeReturnedLend()
    }

    @Test
    fun `direct collection records its clock anchor before its first LIST`() = harnessTest { h ->
        h.clockEpochOffset = 1_789_581_931_987L
        val startedMs = h.clockEpochOffset + h.test.testScheduler.currentTime
        h.repo.syncAll()
        h.until { h.repo.collectionState.value is CollectionState.Done && !h.repo.busy.value }
        assertEquals(listOf(0), h.clockAnchorsBeforeList)
        assertEquals(1, h.factory.requests.count { it == Opcode.SET_TIME })
        assertEquals(listOf(startedMs), h.derivedSince)
    }

    @Test
    fun `worker collection records its clock anchor before its first LIST`() = harnessTest { h ->
        h.clockEpochOffset = 1_789_581_931_987L
        h.knownDao.upsert(h.enrolled(token = null))
        val work = async { h.repo.syncInBackground(ADDRESS, null, owner = "synthetic-worker", protection = KeepAlive.None) }
        h.until { work.isCompleted }
        assertTrue(work.await().newFiles > 0)
        assertEquals(listOf(0), h.clockAnchorsBeforeList)
        assertEquals(1, h.factory.requests.count { it == Opcode.SET_TIME })
        assertTrue(h.derivedSince.isEmpty())
    }

    @Test
    fun `reconnected collection persists each accepted clock anchor before the new LIST`() = harnessTest(chunkDelayMs = 1) { h ->
        h.clockEpochOffset = 1_789_581_931_987L
        h.repo.syncAll()
        h.until { h.archived.rows.isNotEmpty() }
        assertEquals(listOf(0), h.clockAnchorsBeforeList)
        h.factory.clockMissingOnReconnect = true
        h.device.disconnect()
        h.until(120_000) { h.repo.collectionState.value is CollectionState.Done && !h.repo.busy.value }
        assertEquals(listOf(0, 1), h.clockAnchorsBeforeList)
        assertEquals(2, h.factory.requests.count { it == Opcode.SET_TIME })
        assertEquals(2, h.factory.fakeConnects)
    }

    @Test
    fun `new Connect survives a disconnect claim delayed by cleanup`() = harnessTest { h ->
        h.repo.connect(ADDRESS, "Synthetic sensor", fake = true)
        h.until { h.repo.plan.value != null }
        val delayedDisconnect = h.owner.claimDisconnectIntent()
        h.repo.connect(ADDRESS, "Synthetic sensor", fake = true)
        h.until { h.repo.connectionState.value.isReady }
        h.test.runCurrent()
        h.owner.disconnectNow(delayedDisconnect)
        assertTrue(h.repo.connectionState.value.isReady)
        assertNotNull(h.repo.session.value)
        assertEquals(1, h.factory.fakeConnects)
    }

    @Test
    fun `owner refuses forgetting credentials during collection preparation`() = harnessTest { h ->
        h.knownDao.upsert(h.enrolled(token = null))
        h.factory.listDelayMs = 60_000
        h.repo.syncAll()
        h.until { (h.repo.collectionState.value as? CollectionState.Preparing)?.step == PrepareStep.LIST }
        h.owner.forget(ADDRESS)
        assertNotNull(h.knownDao.get(ADDRESS))
        assertTrue(h.repo.collectionState.value.active)
        h.repo.stop(StopSource.USER)
        h.until { !h.repo.busy.value }
    }

    @Test
    fun `collection takes idle reconnect backoff and Stop prevents later idle opens`() = harnessTest { h ->
        h.repo.connect(ADDRESS, "Synthetic sensor", fake = true)
        h.until { h.repo.plan.value != null }
        h.device.disconnect()
        h.until { h.owner.reconnecting.value != null }
        h.factory.listDelayMs = 60_000
        h.repo.syncAll()
        assertNull(h.owner.reconnecting.value)
        h.until { h.factory.fakeConnects == 2 && (h.repo.collectionState.value as? CollectionState.Preparing)?.step == PrepareStep.LIST }
        h.repo.stop(StopSource.USER)
        h.until { !h.repo.busy.value }
        h.advance(120_000)
        assertEquals(2, h.factory.fakeConnects)
        assertEquals(StopSource.USER, (h.repo.collectionState.value as CollectionState.Stopped).by)
        assertTrue(h.archived.rows.isEmpty())
    }

    @Test
    fun `a worker never lends an interactive device session`() = harnessTest { h ->
        h.repo.connect(ADDRESS, "Synthetic sensor", fake = true)
        h.until { h.repo.connectionState.value.isReady }
        val result = h.repo.syncInBackground(ADDRESS, null, owner = "synthetic-worker", protection = KeepAlive.None)
        assertNotNull(result.skipped)
        assertEquals(0, h.factory.unattendedConnects)
        assertTrue(h.repo.connectionState.value.isReady)
    }

    @Test
    fun `an interactive collection blocks worker collection on another device`() = runTest {
        val admission = CollectionAdmission()
        val interactive = Harness(this, sharedAdmission = admission)
        val other = Harness(this, endpoint = "FA:KE:00:00:00:02", sharedAdmission = admission)
        try {
            interactive.factory.listDelayMs = 60_000
            interactive.repo.syncAll()
            assertTrue(interactive.repo.collectionState.value.active)
            val skipped = other.repo.syncInBackground(other.endpoint, null, owner = "synthetic-worker", protection = KeepAlive.None)
            assertNotNull(skipped.skipped)
            assertEquals(0, other.factory.fakeConnects)
            interactive.repo.cancelSync()
            interactive.until { interactive.processLeases == 0 }
            val next = async { other.repo.syncInBackground(other.endpoint, null, owner = "synthetic-worker", protection = KeepAlive.None) }
            other.until { next.isCompleted }
            assertEquals(other.device.files.size, next.await().newFiles)
        } finally {
            interactive.shutdown()
            other.shutdown()
        }
    }

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

        h.repo.smartSync()
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
        h.repo.smartSync()
        h.until { h.archived.rows.size == h.device.files.size && !h.repo.sync.value.running }

        assertTrue(h.events.any { it == DeviceEvent.Info(DeviceMessage.Busy) || it == DeviceEvent.Info(DeviceMessage.BusyBeforeSync) })
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
        h.until { (h.repo.collectionState.value as? CollectionState.Preparing)?.step == PrepareStep.LIST }

        h.repo.cancelSync()
        h.until { !h.repo.busy.value }

        assertEquals(CollectionState.Stopped(h.repo.collectionState.value.intentId!!, StopSource.USER), h.repo.collectionState.value)
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
        assertEquals(done + replacement.name, outcomes.map { it.name }.toSet())
        assertTrue(outcomes.all { it is FileOutcome.Synced })
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
    fun `idle reconnect stops at the first open that needs pairing repair`() = harnessTest { h ->
        h.repo.connect(ADDRESS, "AQ-6b40", fake = true)
        h.until { h.repo.connectionState.value.isReady }
        h.factory.reconnectFailure = DisconnectCause.Auth(AuthKind.BOND_MISSING)
        h.device.disconnect()
        h.until { h.repo.reconnecting.value != null }
        h.until { h.factory.fakeConnects == 2 }
        h.advance(120_000)

        assertEquals(2, h.factory.fakeConnects)
        assertNull(h.repo.reconnecting.value)
        assertTrue(BleConnectionFailure.needsPairingRepair(h.repo.connectionState.value))
        assertEquals(1, h.events.count { it == DeviceEvent.Error(DeviceMessage.PairingRepair) })
        assertFalse(h.events.any { it is DeviceEvent.Error && it.message is DeviceMessage.ReconnectFailed })
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
        h.until { h.repo.connectionState.value.isReady && h.repo.config.value != null && !h.repo.busy.value }

        h.factory.configDelayMs = 100
        h.repo.applyConfig(ConfigPatch.build { lanOn(true) })
        h.repo.applyConfig(ConfigPatch.build { lanOn(false) })
        // Hold the real request open so the public Busy flow observes the admitted command.
        runCurrent()
        assertTrue(h.repo.busy.value)
        try {
            h.repo.applySensorConfig(ConfigPatch.build { lanOn(true) })
            fail("a second command must not run while the first holds the gate")
        } catch (e: IllegalStateException) {
            // expected
        }
        assertTrue(h.repo.busy.value)
        runCurrent()
        assertTrue(h.events.any { it == DeviceEvent.Info(DeviceMessage.Busy) || it == DeviceEvent.Info(DeviceMessage.BusyBeforeSync) })
        h.until { !h.repo.busy.value }
        assertEquals(1, h.events.count { it is DeviceEvent.Info && it.message is DeviceMessage.ConfigSaved })
    }

    @Test
    fun `a saved setting is reported as a typed code`() = harnessTest { h ->
        h.repo.connect(ADDRESS, "AQ-6b40", fake = true)
        h.until { h.repo.connectionState.value.isReady && h.repo.config.value != null && !h.repo.busy.value }

        h.repo.applyConfig(ConfigPatch.build { lanOn(true) }, DeviceMessage.ConfigChange.LAN_ON)
        h.until { h.events.any { it is DeviceEvent.Info && it.message is DeviceMessage.ConfigSaved } }

        val saved = h.events.filterIsInstance<DeviceEvent.Info>().map { it.message }.filterIsInstance<DeviceMessage.ConfigSaved>().single()
        assertEquals(DeviceMessage.ConfigChange.LAN_ON, saved.change)
    }

    @Test
    fun `clearing bonds and a default save report which settings changed`() = harnessTest { h ->
        h.repo.connect(ADDRESS, "AQ-6b40", fake = true)
        h.until { h.repo.connectionState.value.isReady && h.repo.config.value != null && !h.repo.busy.value }

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
        val session = DeviceSession.over(device, backgroundScope)
        session.link.open()
        try {
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
    @Test
    fun `pending manual OPEN refuses collection before process acquisition and cancellation releases it`() = harnessTest { h ->
        h.factory.hangConnect = true
        val pending = launch { h.repo.lendLink(ADDRESS, LinkPreference.ANY) }
        h.until { h.factory.fakeConnects == 1 }
        h.repo.syncAll()
        assertEquals(0, h.processAcquires)
        assertFalse(h.owner.collectionActive.value)
        assertEquals(CollectionState.Idle, h.repo.collectionState.value)
        pending.cancel()
        h.until { pending.isCompleted }
        h.factory.hangConnect = false
        h.repo.syncAll()
        h.until { h.repo.collectionState.value is CollectionState.Done && !h.repo.busy.value }
        assertEquals(1, h.processAcquires)
    }

    @Test
    fun `an outstanding lend refuses a second transfer and closes exactly once`() = harnessTest { h ->
        h.knownDao.upsert(h.enrolled(token = null))
        val first = async { h.repo.lendLink(ADDRESS, LinkPreference.ANY) }
        h.until { first.isCompleted }
        val a = first.await()
        val refused = runCatching { h.repo.lendLink(ADDRESS, LinkPreference.ANY) }.exceptionOrNull()
        assertEquals(DisconnectCause.Busy, (refused as LinkClosedException).disconnectCause)
        h.repo.syncAll()
        runCurrent()
        assertEquals(CollectionState.Idle, h.repo.collectionState.value)
        assertEquals(0, h.processAcquires)
        a.release()
        a.release()
        runCurrent()
        assertNull(h.repo.interactiveSessionAddress)
        assertEquals(1, h.factory.fakeConnects)
    }

    @Test
    fun `a lent UI link stays open after the collection returns it`() = harnessTest { h ->
        h.knownDao.upsert(h.enrolled(token = null))
        h.repo.connect(ADDRESS, "AQ-6b40", fake = true)
        h.until { h.repo.connectionState.value.isReady }
        val lent = async { h.repo.lendLink(ADDRESS, LinkPreference.ANY) }.also { h.until { it.isCompleted } }.await()
        assertEquals(1, h.factory.fakeConnects)
        lent.release()
        runCurrent()
        assertTrue(h.repo.isConnectedTo(ADDRESS))
    }

    @Test
    fun `the UI connecting during a collection keeps the link after release`() = harnessTest { h ->
        h.knownDao.upsert(h.enrolled(token = null))
        val lent = async { h.repo.lendLink(ADDRESS, LinkPreference.ANY) }.also { h.until { it.isCompleted } }.await()
        h.repo.connect(ADDRESS, "AQ-6b40", fake = true)
        runCurrent()
        assertEquals("the UI reuses the lent link", 1, h.factory.fakeConnects)
        lent.release()
        runCurrent()
        assertTrue(h.repo.isConnectedTo(ADDRESS))
    }

    @Test
    fun `a cancelled lend leaves no link and no count behind`() = harnessTest { h ->
        h.knownDao.upsert(h.enrolled(token = null))
        val cancelled = launch { h.repo.lendLink(ADDRESS, LinkPreference.ANY) }
        runCurrent()
        cancelled.cancel()
        h.until { cancelled.isCompleted }
        runCurrent()
        assertNull(h.repo.interactiveSessionAddress)

        val again = async { h.repo.lendLink(ADDRESS, LinkPreference.ANY) }.also { h.until { it.isCompleted } }.await()
        again.release()
        runCurrent()
        assertNull("one release closes it, so the cancelled lend was never counted", h.repo.interactiveSessionAddress)
    }

    @Test
    fun `LAN only lend without a LAN target is Unreachable`() = harnessTest { h ->
        val e = runCatching { h.repo.lendLink(ADDRESS, LinkPreference.LAN) }.exceptionOrNull()
        assertEquals(DisconnectCause.Unreachable, (e as LinkClosedException).disconnectCause)
        assertEquals(0, h.factory.fakeConnects)
    }

    @Test
    fun `a lend whose connect hangs ends at once on cancel`() = harnessTest { h ->
        h.knownDao.upsert(h.enrolled(token = null))
        h.factory.hangConnect = true
        val stuck = launch { h.repo.lendLink(ADDRESS, LinkPreference.ANY) }
        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(stuck.isCompleted)
        stuck.cancel()
        runCurrent()
        assertTrue("cancel ends the connect without waiting for the device", stuck.isCompleted)
        runCurrent()
        assertNull(h.repo.interactiveSessionAddress)

        h.factory.hangConnect = false
        val again = async { h.repo.lendLink(ADDRESS, LinkPreference.ANY) }.also { h.until { it.isCompleted } }.await()
        again.release()
        runCurrent()
        assertNull("the cancelled lend was never counted", h.repo.interactiveSessionAddress)
    }

    @Test
    fun `a lend whose connect hangs fails within the lend timeout`() = harnessTest { h ->
        h.knownDao.upsert(h.enrolled(token = null))
        h.factory.hangConnect = true
        val stuck = async { runCatching { h.repo.lendLink(ADDRESS, LinkPreference.ANY) } }
        advanceTimeBy(DeviceLinkOwner.LEND_TIMEOUT.inWholeMilliseconds + 1)
        runCurrent()
        assertTrue(stuck.isCompleted)
        val e = stuck.await().exceptionOrNull()
        assertTrue("a typed close, not a cancel: $e", e is LinkClosedException)
        runCurrent()
        assertNull(h.repo.interactiveSessionAddress)
    }

    @Test
    fun `repeated LAN only lends retain a rejected token cause without retrying Bluetooth`() = harnessTest { h ->
        h.knownDao.upsert(h.enrolled(token = OLD_TOKEN))
        h.lan.devices.value = mapOf(h.device.station to h.lanTarget())
        h.factory.lanFailure = LanConnectException(LanFailure.AUTH_FAILED)
        repeat(2) {
            val e = async { runCatching { h.repo.lendLink(ADDRESS, LinkPreference.LAN) } }.also { h.until { it.isCompleted } }.await().exceptionOrNull()
            assertEquals(DisconnectCause.Auth(AuthKind.TOKEN_REJECTED), (e as LinkClosedException).disconnectCause)
        }
        assertEquals(1, h.factory.lanTransports.size)
        assertEquals(0, h.factory.fakeConnects)
    }

    @Test
    fun `a lend refused after verification gets a typed cause that is not retried`() = harnessTest { h ->
        h.knownDao.upsert(h.enrolled(token = null))
        val other = h.identityClaims.claim(h.device.deviceId, Any())
        val e = async { runCatching { h.repo.lendLink(ADDRESS, LinkPreference.ANY) } }.also { h.until { it.isCompleted } }.await().exceptionOrNull()
        val cause = (e as LinkClosedException).disconnectCause
        assertEquals(DisconnectCause.Rejected("hardware in use"), cause)
        assertFalse(cause.retryable)
        assertNull(h.repo.interactiveSessionAddress)
        other?.close()
    }

    @Test
    fun `a link that drops while a lend opens it is closed, so the next lend is not Busy`() = harnessTest { h ->
        h.knownDao.upsert(h.enrolled(token = null))
        h.factory.dropOn = Opcode.SET_TIME
        val first = async { runCatching { h.repo.lendLink(ADDRESS, LinkPreference.ANY) } }.also { h.until { it.isCompleted } }.await()
        assertTrue("the lend fails with a typed close: ${first.exceptionOrNull()}", first.exceptionOrNull() is LinkClosedException)
        runCurrent()
        assertNull("the uncounted link is not left in the slot", h.repo.interactiveSessionAddress)

        val again = async { h.repo.lendLink(ADDRESS, LinkPreference.ANY) }.also { h.until { it.isCompleted } }.await()
        assertEquals(h.device.station, again.station)
        again.release()
    }

    @Test
    fun `a closed collection owned link with no outstanding lend is cleared before the next lend`() = harnessTest { h ->
        h.knownDao.upsert(h.enrolled(token = null))
        val first = async { h.repo.lendLink(ADDRESS, LinkPreference.ANY) }.also { h.until { it.isCompleted } }.await()
        first.session.link.close("synthetic loss")
        first.release()
        // Enter before the asynchronous release cleanup. The lender itself must clear the stale slot.
        val next = async(start = CoroutineStart.UNDISPATCHED) { h.repo.lendLink(ADDRESS, LinkPreference.ANY) }
        h.until { next.isCompleted }
        val second = next.await()
        assertTrue(first.session !== second.session)
        assertEquals(2, h.factory.fakeConnects)
        second.release()
        runCurrent()
        assertNull(h.repo.session.value)
    }

    @Test
    fun `a lend while the facade collection runs is Busy`() = harnessTest(chunkDelayMs = 1) { h ->
        h.repo.connect(ADDRESS, "AQ-6b40", fake = true)
        h.until { h.repo.plan.value != null }
        h.repo.syncAll()
        h.until { h.repo.sync.value.running }
        val e = async { runCatching { h.repo.lendLink(ADDRESS, LinkPreference.ANY) } }.also { h.until { it.isCompleted } }.await().exceptionOrNull()
        assertEquals(DisconnectCause.Busy, (e as LinkClosedException).disconnectCause)
    }

    @Test
    fun `Bluetooth off ends a sync once with its own reason and no retry`() = harnessTest { h ->
        h.factory.unavailable = UnavailableKind.RADIO_OFF
        h.repo.syncAll()
        h.until { !h.repo.collectionState.value.active && !h.repo.busy.value }
        assertEquals(SyncEndReason.BLUETOOTH_OFF, h.repo.sync.value.endReason)
        assertEquals(0, h.factory.fakeConnects)
        assertEquals(1, h.events.count { it == DeviceEvent.Error(DeviceMessage.SyncEnded(SyncEndReason.BLUETOOTH_OFF)) })
    }

    @Test
    fun `a missing Bluetooth permission is named on connect`() = harnessTest { h ->
        h.factory.unavailable = UnavailableKind.PERMISSION_MISSING
        h.repo.connect(ADDRESS, "AQ-6b40")
        h.until { h.events.isNotEmpty() && !h.repo.busy.value }
        assertTrue(h.events.contains(DeviceEvent.Error(DeviceMessage.BluetoothUnavailable(UnavailableKind.PERMISSION_MISSING))))
        assertNull(h.repo.session.value)
    }

    @Test
    fun `each ending carries its reason and a new run clears it`() = harnessTest { h ->
        h.repo.syncAll()
        h.until { !h.repo.collectionState.value.active && !h.repo.busy.value }
        assertEquals(SyncEndReason.COMPLETED, h.repo.sync.value.endReason)

        h.device.files += h.newFile()
        h.factory.listDelayMs = 60_000
        h.repo.syncAll()
        h.until { h.repo.sync.value.running }
        assertNull(h.repo.sync.value.endReason)
        h.repo.stop(StopSource.FGS_TIMEOUT)
        h.until { !h.repo.busy.value }
        assertEquals(SyncEndReason.INTERRUPTED, h.repo.sync.value.endReason)
        assertTrue(h.events.contains(DeviceEvent.Info(DeviceMessage.SyncEnded(SyncEndReason.INTERRUPTED))))

        h.repo.syncAll()
        h.until { h.repo.sync.value.running }
        h.repo.cancelSync()
        h.until { !h.repo.busy.value }
        assertEquals(SyncEndReason.STOPPED, h.repo.sync.value.endReason)
        assertTrue(h.events.contains(DeviceEvent.Info(DeviceMessage.SyncStopped)))
    }

    private fun harnessTest(chunkDelayMs: Long = 0, block: suspend TestScope.(Harness) -> Unit) = runTest {
        val h = Harness(this, chunkDelayMs)
        try {
            block(h)
        } finally {
            h.shutdown()
        }
    }

    private inner class Harness(val test: TestScope, chunkDelayMs: Long = 0, val endpoint: String = ADDRESS, sharedAdmission: CollectionAdmission = CollectionAdmission()) {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val device = FakeTransport(address = endpoint, chunkDelayMs = chunkDelayMs, liveIntervalMs = 3_600_000, dispatcher = dispatcher).also {
            // The 1 MiB benchmark file only slows the tests down.
            it.files.removeAll { f -> f.name.startsWith("benchmarks/") }
        }
        val rawKnown = InMemoryKnownDeviceDao()
        val knownDao = SecureKnownDeviceDao(rawKnown, PrefixCipher())
        val archived = InMemoryArchivedFileDao()
        val lan = TestLanTargets()
        val events = CopyOnWriteArrayList<DeviceEvent>()
        val appScope = CoroutineScope(SupervisorJob() + dispatcher)
        val factory = TestTransports(device, appScope)
        val identityClaims = DeviceIdentityClaims()

        var clockEpochOffset = 0L
        var partialFailuresRemaining = 0
        val failedStorageNames = mutableListOf<String>()
        val committedNames = mutableListOf<String>()
        val clockAnchorsBeforeList = mutableListOf<Int>()
        val derivedSince = mutableListOf<Long>()
        val owner: DeviceLinkOwner
        val repo: DeviceFacade
        var refuseProcess = false
        var processLeases = 0
        var processAcquires = 0

        init {
            val fileArchive = FileArchive(tmp)
            owner = DeviceLinkOwner(
                transportFactory = factory,
                knownDeviceDao = knownDao,
                lanCredentials = knownDao,
                archivedFileDao = archived,
                archive = fileArchive,
                lanDiscoveryState = lan,
                historyDeriver = object : HistoryRefresher {
                    override suspend fun deriveStale() = HistoryDeriver.Result(0, 0, 0)
                    override suspend fun deriveAfterSync(sinceEpochMs: Long): HistoryDeriver.Result {
                        derivedSince += sinceEpochMs
                        return deriveStale()
                    }
                    override suspend fun recordClockAnchor(station: String, boot: String, utcNs: Long, monotonicUs: Long) = persistClockAnchor(station, boot, utcNs, monotonicUs)
                    override suspend fun persistClockAnchor(station: String, boot: String, utcNs: Long, monotonicUs: Long) {
                        assertEquals(device.station, station)
                        assertEquals(device.bootId, boot)
                        assertTrue(utcNs > 0)
                        clockAnchorsBeforeList += factory.requests.count { it == Opcode.LIST }
                    }
                },
                companionPresence = { _, _ -> },
                identityClaims = identityClaims,
                stationEpochDao = InMemoryStationEpochDao(),
                applicationScope = appScope,
                io = dispatcher,
                clock = { clockEpochOffset + test.testScheduler.currentTime },
            )
            val delegateStore = FileArchiveStore(fileArchive, dispatcher)
            val store = object : ArchiveStore by delegateStore {
                override suspend fun openPartial(key: ArchiveKey, meta: PartialMeta): PartialWriter {
                    if (partialFailuresRemaining > 0) {
                        partialFailuresRemaining--
                        failedStorageNames += key.name
                        throw StorageException("synthetic storage failure")
                    }
                    return delegateStore.openPartial(key, meta)
                }
                override suspend fun commit(key: ArchiveKey) {
                    delegateStore.commit(key)
                    committedNames += key.name
                }
            }
            val engine = SyncEngine(store, RoomArchiveIndex(archived), HistoryIndexer(fileArchive, archived).asRecordIndexer(fileArchive), windowRetryBackoffMs = 1, clock = { test.testScheduler.currentTime }, io = dispatcher)
            repo = DeviceFacade(
                linkOwner = owner,
                collector = ArchiveCollector(store, RoomArchiveIndex(archived), engine, FakeStorageBudget(), test.testScheduler.timeSource),
                keepAlive = KeepAlive { kind, _ ->
                    if (kind == KeepAliveKind.PROCESS) {
                        processAcquires++
                        if (refuseProcess) {
                            null
                        } else {
                            processLeases++
                            Lease { processLeases-- }
                        }
                    } else {
                        Lease {}
                    }
                },
                collectionBudget = CollectionBudget(),
                admission = sharedAdmission,
                applicationScope = appScope,
                io = dispatcher,
                clock = { clockEpochOffset + test.testScheduler.currentTime },
                monotonic = test.testScheduler.timeSource,
            )
            repo.bind(endpoint)
            appScope.launch { repo.events.collect { events += it } }
        }

        fun shutdown() {
            appScope.cancel()
            device.close()
        }

        fun enrolled(token: String?) = KnownDevice(
            address = endpoint,
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

    /**
     * BLE and fake requests get a new [TransportLink] over the one [FakeTransport], as the debug
     * fake does. LAN requests get a link whose open fails.
     */
    private class TestTransports(private val device: FakeTransport, private val scope: CoroutineScope) : TransportSource {
        var lanFailure: Exception = LanConnectException(LanFailure.UNREACHABLE)
        val lanTransports = CopyOnWriteArrayList<FailingLanTransport>()
        var fakeConnects = 0
        var interactiveConnects = 0
        var unattendedConnects = 0
        var unattendedTimeout: Long? = null
        var rejectReconnect = false
        var reconnectFailure: DisconnectCause? = null
        var clockMissingOnReconnect = false

        /** When set, Bluetooth links cannot be built, as with the radio off or the permission missing. */
        var unavailable: UnavailableKind? = null

        /** When set, Bluetooth links never finish connecting and ignore everything but cancel. */
        var hangConnect = false
        var listFailure: Exception? = null
        var listDelayMs = 0L
        var configDelayMs = 0L
        val requests = CopyOnWriteArrayList<Int>()

        /** The device link drops once, when this opcode is written. */
        var dropOn: Int? = null
        private fun clockStatus(payload: ByteArray): ByteArray = if (clockMissingOnReconnect && fakeConnects > 1) payload.decodeToString().replace("\"utc\":1", "\"utc\":0").encodeToByteArray() else payload
        private val observedDevice = object : AqTransport by device {
            override val statusNotifications: Flow<ByteArray> = device.statusNotifications.map(::clockStatus)
            override suspend fun readStatus(): ByteArray = clockStatus(device.readStatus())
            override suspend fun readInfo(): ByteArray {
                val info = device.readInfo()
                return if (rejectReconnect && fakeConnects > 1) info.decodeToString().replace("\"proto\":3", "\"proto\":2").encodeToByteArray() else info
            }

            override suspend fun writeControl(request: ByteArray) {
                val op = request.first().toInt() and 0xff
                requests += op
                if (op == Opcode.SET_TIME && fakeConnects > 1) clockMissingOnReconnect = false
                if (op == dropOn) {
                    dropOn = null
                    device.disconnect()
                    throw TransportException("link dropped")
                }
                if (op == Opcode.LIST) {
                    delay(listDelayMs)
                    listFailure?.let { throw it }
                }
                if (op == Opcode.SET_CONFIG) delay(configDelayMs)
                device.writeControl(request)
            }
        }

        override fun ble(address: String): AndroidLink = requireRadio().let { counted().also { interactiveConnects++ } }
        override fun bleUnattended(address: String, connectTimeoutMs: Long): AndroidLink = requireRadio().let { counted() }.also {
            unattendedConnects++
            unattendedTimeout = connectTimeoutMs
        }
        override fun lan(address: String, name: String?, host: InetAddress, port: Int, token: ByteArray): AndroidLink =
            FailingLanTransport(address, lanFailure).also { lanTransports += it }.let { TransportLink(it, scope) }
        override fun fake(address: String): AndroidLink = counted()
        override fun isFake(transport: AqTransport): Boolean = (transport as? TransportLink)?.wrapped === observedDevice

        private fun requireRadio() {
            unavailable?.let { throw TransportUnavailableException(it, "synthetic ${it.name}") }
        }

        private fun counted(): AndroidLink {
            fakeConnects++
            if (hangConnect) return TransportLink(HangingTransport(observedDevice), scope)
            val link = TransportLink(observedDevice, scope)
            val failure = reconnectFailure
            return if (fakeConnects > 1 && failure != null) {
                object : AndroidLink by link {
                    override suspend fun open(sink: FrameSink): LinkInfo = throw LinkClosedException(failure)
                }
            } else {
                link
            }
        }
    }

    /** A GATT connect with no timeout of its own. Only cancellation ends it. */
    private class HangingTransport(device: AqTransport) : AqTransport by device {
        override suspend fun connect() = awaitCancellation()
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

    private class InMemoryArchivedFileDao : DerivedArchivedFileQueries {
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
