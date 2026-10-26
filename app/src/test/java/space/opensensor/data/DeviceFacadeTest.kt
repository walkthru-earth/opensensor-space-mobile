package space.opensensor.data

import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.flow.flow
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
import space.opensensor.archive.ArchiveKey
import space.opensensor.archive.ArchiveStore
import space.opensensor.archive.PartialMeta
import space.opensensor.archive.PartialWriter
import space.opensensor.archive.PrunedFiles
import space.opensensor.collection.CollectionFailure
import space.opensensor.collection.CollectionState
import space.opensensor.collection.KeepAlive
import space.opensensor.collection.KeepAliveKind
import space.opensensor.collection.Lease
import space.opensensor.collection.LinkPreference
import space.opensensor.collection.PrepareStep
import space.opensensor.collection.StopSource
import space.opensensor.collection.SyncEndReason
import space.opensensor.data.archive.FileArchiveStore
import space.opensensor.data.archive.RoomArchiveIndex
import space.opensensor.data.archive.asRecordIndexer
import space.opensensor.data.ble.BleConnectionFailure
import space.opensensor.data.ble.ConnectionState
import space.opensensor.data.ble.DeviceSession
import space.opensensor.data.ble.DeviceTransport
import space.opensensor.data.ble.FrameDirection
import space.opensensor.data.ble.RawCommandRefusedException
import space.opensensor.data.ble.SnapshotPoller
import space.opensensor.data.ble.TransportException
import space.opensensor.data.ble.TransportKind
import space.opensensor.data.ble.TransportUnavailableException
import space.opensensor.data.fake.FakeTransport
import space.opensensor.data.history.BoardDescriptors
import space.opensensor.data.history.HistoryDeriver
import space.opensensor.data.history.HistoryIndexer
import space.opensensor.data.history.HistoryRefresher
import space.opensensor.data.history.ServedTemplates
import space.opensensor.data.lan.LanConnectException
import space.opensensor.data.lan.LanDevice
import space.opensensor.data.lan.LanFailure
import space.opensensor.data.lan.LanTargets
import space.opensensor.data.local.database.ArchivedFile
import space.opensensor.data.local.database.DerivedArchivedFileQueries
import space.opensensor.data.local.database.KnownDevice
import space.opensensor.data.local.database.KnownDeviceDao
import space.opensensor.data.local.database.ThingEpoch
import space.opensensor.data.local.database.ThingEpochDao
import space.opensensor.data.local.security.LanTokenCipherContract
import space.opensensor.data.local.security.SecureKnownDeviceDao
import space.opensensor.data.protocol.ConfigPatch
import space.opensensor.data.protocol.Frame
import space.opensensor.data.protocol.LanFraming
import space.opensensor.data.protocol.Opcode
import space.opensensor.data.sync.ArchiveCollector
import space.opensensor.data.sync.DeviceCoordinator
import space.opensensor.data.sync.FileArchive
import space.opensensor.data.sync.FileOutcome
import space.opensensor.data.sync.SyncEngine
import space.opensensor.link.AuthKind
import space.opensensor.link.DisconnectCause
import space.opensensor.link.FrameSink
import space.opensensor.link.LinkClosedException
import space.opensensor.link.LinkInfo
import space.opensensor.link.LinkState
import space.opensensor.link.UnavailableKind
import space.opensensor.link.retryable
import space.opensensor.net.MemoryBlockStore
import space.opensensor.storage.FakeStorageBudget
import space.opensensor.storage.StorageException
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Facade and link ownership regressions on virtual time, with [FakeTransport] and in-memory DAOs.
 * Collection state, synchronous protection and fresh listings use the same controller as the app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceFacadeTest {
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
        assertArrayEquals(h.device.files.single().bytes, FileArchive(tmp).fileFor(receipt.thing, receipt.name).readBytes())
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
        h.until { h.derivedSince.isNotEmpty() }
        assertEquals(listOf(startedMs), h.derivedSince)
    }

    @Test
    fun `a slow post-sync derive never holds the link from the next collection`() = harnessTest { h ->
        val gate = CompletableDeferred<Unit>()
        h.deriveGate = gate
        h.repo.syncAll()
        // The derive waits behind a backfill, yet the collection ends and releases its link.
        h.until { h.repo.collectionState.value is CollectionState.Done && !h.repo.busy.value }
        h.until { h.derivedSince.size == 1 }
        val lists = h.factory.requests.count { it == Opcode.LIST }

        h.repo.syncAll()
        h.until { h.derivedSince.size == 2 && h.repo.collectionState.value is CollectionState.Done && !h.repo.busy.value }
        assertTrue("the next collection ran its own LIST", h.factory.requests.count { it == Opcode.LIST } > lists)
        assertFalse(gate.isCompleted)
        gate.complete(Unit)
        h.test.runCurrent()
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
    fun `MTU 247 without snapshot notifications keeps Today and status fresh by paced reads`() = harnessTest { h ->
        h.factory.smallMtu = true
        h.repo.connect(ADDRESS, "OS-6b40")
        h.until { h.repo.connectionState.value.isReady && h.repo.live.value != null && h.repo.status.value != null }
        var seq = checkNotNull(h.repo.live.value).sample.seq!!
        var up = checkNotNull(h.repo.status.value).status.upSeconds!!
        repeat(3) {
            h.advance(SnapshotPoller.SNAPSHOT_READ_INTERVAL.inWholeMilliseconds)
            val live = checkNotNull(h.repo.live.value).sample.seq!!
            val status = checkNotNull(h.repo.status.value).status.upSeconds!!
            assertTrue("Today follows the board's cached LIVE value", live > seq)
            assertTrue("STATUS follows the board's cached value", status > up)
            seq = live
            up = status
        }
        assertTrue(checkNotNull(h.repo.statusRaw.value).contains("\"up_s\":$up"))
    }

    @Test
    fun `a failed snapshot read keeps the link and the next tick reads again`() = harnessTest { h ->
        h.factory.smallMtu = true
        h.repo.connect(ADDRESS, "OS-6b40")
        h.until { h.repo.connectionState.value.isReady && h.repo.live.value != null }
        val seq = checkNotNull(h.repo.live.value).sample.seq!!
        val reads = h.factory.snapshotReads.size
        h.factory.failSnapshotReads = 2
        h.advance(SnapshotPoller.SNAPSHOT_READ_INTERVAL.inWholeMilliseconds)
        assertEquals("both reads of the failing tick ran", reads + 2, h.factory.snapshotReads.size)
        assertEquals(seq, checkNotNull(h.repo.live.value).sample.seq)
        assertTrue(h.repo.connectionState.value.isReady)
        h.advance(SnapshotPoller.SNAPSHOT_READ_INTERVAL.inWholeMilliseconds)
        assertTrue("the next tick reads a newer LIVE", checkNotNull(h.repo.live.value).sample.seq!! > seq)
        assertTrue(h.repo.connectionState.value.isReady)
    }

    @Test
    fun `snapshot reads pause for a transfer and never overlap its requests`() = harnessTest { h ->
        h.factory.smallMtu = true
        h.factory.snapshotReadMs = 2_000
        h.repo.connect(ADDRESS, "OS-6b40")
        h.until { h.repo.connectionState.value.isReady && h.repo.live.value != null }
        // Start the transfer while a paced read is in flight.
        val setupReads = h.factory.snapshotReads.size
        h.until { h.factory.snapshotReads.size > setupReads }
        // LIST answers just inside its write deadline, so the transfer spans more than one read tick.
        h.factory.listDelayMs = 8_000
        val started = h.test.testScheduler.currentTime
        h.repo.syncAll()
        h.until(120_000) { h.repo.collectionState.value is CollectionState.Done && !h.repo.busy.value }
        assertTrue(h.test.testScheduler.currentTime - started > 2 * SnapshotPoller.SNAPSHOT_READ_INTERVAL.inWholeMilliseconds)
        assertTrue(Opcode.LIST in h.factory.requests)
        assertEquals(emptyList<Int>(), h.factory.writesDuringRead.toList())
        assertEquals(emptyList<String>(), h.factory.readsDuringTransfer.toList())
        val afterTransfer = h.factory.snapshotReads.size
        h.advance(2 * SnapshotPoller.SNAPSHOT_READ_INTERVAL.inWholeMilliseconds)
        assertTrue("reads resume once the transfer ends", h.factory.snapshotReads.size > afterTransfer)
    }

    @Test
    fun `no snapshot read runs after a disconnect`() = harnessTest { h ->
        h.factory.smallMtu = true
        h.repo.connect(ADDRESS, "OS-6b40")
        h.until { h.repo.connectionState.value.isReady && h.repo.live.value != null }
        val setupReads = h.factory.snapshotReads.size
        h.advance(SnapshotPoller.SNAPSHOT_READ_INTERVAL.inWholeMilliseconds)
        assertTrue(h.factory.snapshotReads.size > setupReads)
        h.repo.disconnect()
        h.until { h.repo.session.value == null }
        val reads = h.factory.snapshotReads.size
        h.advance(6 * SnapshotPoller.SNAPSHOT_READ_INTERVAL.inWholeMilliseconds)
        assertEquals(reads, h.factory.snapshotReads.size)
    }

    @Test
    fun `no snapshot read runs after the link is lost`() = harnessTest { h ->
        h.factory.smallMtu = true
        h.repo.connect(ADDRESS, "OS-6b40")
        h.until { h.repo.connectionState.value.isReady && h.repo.live.value != null }
        h.factory.unavailable = UnavailableKind.RADIO_OFF
        h.device.disconnect()
        h.test.runCurrent()
        val reads = h.factory.snapshotReads.size
        h.advance(6 * SnapshotPoller.SNAPSHOT_READ_INTERVAL.inWholeMilliseconds)
        assertEquals(reads, h.factory.snapshotReads.size)
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
        h.repo.connect(ADDRESS, "OS-6b40", fake = true)
        h.until { h.repo.plan.value != null }
        val sent = h.repo.session.value!!.frames.records.value.filter { it.direction == FrameDirection.TX }.map { it.type }
        assertTrue(sent.indexOf(Opcode.SET_TIME) in 0 until sent.indexOf(Opcode.LIST))
        assertEquals(1, sent.count { it == Opcode.SET_TIME })
        assertEquals(1, h.repo.status.value?.status?.utc)
    }

    @Test
    fun `a slow re-dating derive after a clock receipt never holds the link`() = harnessTest { h ->
        h.clockEpochOffset = 1_789_581_931_987L
        val gate = CompletableDeferred<Unit>()
        h.thingDeriveGate = gate
        h.repo.connect(ADDRESS, "OS-6b40", fake = true)
        // The derive waits behind a History pass, yet the connection lists and a collection completes.
        h.until { h.repo.plan.value != null }
        assertEquals(listOf(h.device.thing), h.thingDerives)
        h.repo.syncAll()
        h.until { h.archived.rows.size == h.device.files.size && h.repo.collectionState.value is CollectionState.Done && !h.repo.busy.value }
        assertFalse(gate.isCompleted)
        gate.complete(Unit)
        h.test.runCurrent()
    }

    @Test
    fun `the SET_TIME on connect and a manual one each record the clock anchor`() = harnessTest { h ->
        h.clockEpochOffset = 1_789_581_931_987L
        h.repo.connect(ADDRESS, "AQ-6b40", fake = true)
        h.until { h.repo.plan.value != null && !h.repo.busy.value }
        assertEquals(1, h.factory.requests.count { it == Opcode.SET_TIME })
        assertEquals("the automatic SET_TIME on connect", 1, h.clockAnchorsBeforeList.size)

        h.repo.setTimeFromPhone()
        h.until { h.factory.requests.count { it == Opcode.SET_TIME } == 2 && !h.repo.busy.value }
        assertEquals("the manual SET_TIME", 2, h.clockAnchorsBeforeList.size)
    }

    @Test
    fun `a refused automatic clock still lists original files`() = harnessTest { h ->
        // Virtual epoch zero is deliberately refused by the firmware-compatible fake.
        h.repo.connect(ADDRESS, "OS-6b40", fake = true)
        h.until { h.repo.plan.value != null }
        assertTrue(h.repo.plan.value!!.toDownload.isNotEmpty())
        assertEquals(0, h.repo.status.value?.status?.utc)
    }

    @Test
    fun `sync all lists again and downloads a file finalized after the previous listing`() = harnessTest { h ->
        h.repo.connect(ADDRESS, "OS-6b40", fake = true)
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
        h.repo.connect(ADDRESS, "OS-6b40", fake = true)
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
        h.repo.connect(ADDRESS, "OS-6b40", fake = true)
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
        h.repo.connect(ADDRESS, "OS-6b40", fake = true)
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
        h.repo.connect(ADDRESS, "OS-6b40", fake = true)
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
        h.repo.connect(ADDRESS, "OS-6b40", fake = true)
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
        h.repo.connect(ADDRESS, "OS-6b40", fake = true)
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
        h.repo.connect(ADDRESS, "OS-6b40", fake = true)
        h.until { h.repo.plan.value != null }
        h.repo.syncAll()
        h.until { h.repo.sync.value.filesDone >= 1 }
        val done = h.archived.rows.map { it.name }.toSet()

        // While the link is down the card changes: every file not yet archived is gone and one new
        // file appears. A resume driven by the previous plan would OPEN names that no longer exist.
        val replacement = FakeTransport.FakeFile(
            "station=${h.device.thing}/year=2026/month=09/day=17/data_0900_${h.device.bootId}_20000-20089-90.parquet",
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
        h.repo.connect(ADDRESS, "OS-6b40", fake = true)
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
        h.repo.connect(ADDRESS, "OS-6b40", fake = true)
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
    fun `settings changed while a known sensor enrolls survive the enrollment`() = harnessTest { h ->
        h.knownDao.upsert(h.enrolled(token = OLD_TOKEN).copy(autoSync = true, companionAssociationId = 7))
        // The user turns Auto-sync off, the companion changes and BLE renews the credential, all
        // after enrollment read the row and before it writes (#106).
        h.thingEpochs.beforeRecord = {
            h.repo.setAutoSync(ADDRESS, false)
            h.repo.setCompanionAssociation(ADDRESS, 9)
            h.knownDao.setLanToken(ADDRESS, NEW_TOKEN, 47391)
        }

        h.repo.connect(ADDRESS, "OS-6b40", fake = true)
        h.until { h.repo.connectionState.value.isReady }

        assertNull("the hook ran", h.thingEpochs.beforeRecord)
        val row = h.knownDao.get(ADDRESS)!!
        assertFalse("Auto-sync stays off", row.autoSync)
        assertEquals(9, row.companionAssociationId)
        assertTrue("the renewed credential survives", h.knownDao.lanToken(ADDRESS) == NEW_TOKEN)
        assertEquals(47391, row.lanPort)
        assertEquals(h.device.deviceId, row.deviceId)
    }

    @Test
    fun `a new sensor enrolls with Auto-sync off and no companion`() = harnessTest { h ->
        h.repo.connect(ADDRESS, "OS-6b40", fake = true)
        h.until { h.repo.connectionState.value.isReady }

        val row = h.knownDao.get(ADDRESS)!!
        assertFalse(row.autoSync)
        assertNull(row.companionAssociationId)
        assertEquals(h.device.thing, row.thing)
        assertEquals(h.device.deviceId, row.deviceId)
        assertNotNull(row.lastConnectedEpochMs)
    }

    @Test
    fun `rejected LAN token is cleared and the Bluetooth session renews it`() = harnessTest { h ->
        h.knownDao.upsert(h.enrolled(token = OLD_TOKEN))
        h.lan.devices.value = mapOf(h.device.thing to h.lanTarget())
        h.factory.lanFailure = LanConnectException(LanFailure.AUTH_FAILED)

        h.repo.connect(ADDRESS, "OS-6b40")
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
    fun `a rotated LAN token is cleared and the Bluetooth session renews it`() = harnessTest { h ->
        h.knownDao.upsert(h.enrolled(token = OLD_TOKEN))
        h.lan.devices.value = mapOf(h.device.thing to h.lanTarget())
        h.factory.lanClose = LinkClosedException(DisconnectCause.Auth(AuthKind.TOKEN_ROTATED), LanConnectException(LanFailure.AUTH_FAILED, "BYE token-rotated"))

        h.repo.connect(ADDRESS, "OS-6b40")
        h.until { h.repo.connectionState.value.isReady }

        assertEquals(TransportKind.FAKE, h.repo.activeTransport.value)
        val renewed = h.knownDao.lanToken(ADDRESS)
        assertNotNull("a fresh token was fetched over the Bluetooth session", renewed)
        assertFalse(renewed == OLD_TOKEN)
    }

    @Test
    fun `a LAN only lend after a rotated token ends as TOKEN_ROTATED`() = harnessTest { h ->
        h.knownDao.upsert(h.enrolled(token = OLD_TOKEN))
        h.lan.devices.value = mapOf(h.device.thing to h.lanTarget())
        h.factory.lanClose = LinkClosedException(DisconnectCause.Auth(AuthKind.TOKEN_ROTATED), LanConnectException(LanFailure.AUTH_FAILED, "BYE token-rotated"))

        val e = async { runCatching { h.repo.lendLink(ADDRESS, LinkPreference.LAN) } }.also { h.until { it.isCompleted } }.await().exceptionOrNull()
        assertEquals(DisconnectCause.Auth(AuthKind.TOKEN_ROTATED), (e as LinkClosedException).disconnectCause)
        assertNull("the rotated token is cleared, not only marked stale", h.knownDao.lanToken(ADDRESS))
        assertEquals(0, h.factory.fakeConnects)
    }

    @Test
    fun `a credential renewed before a rotated token is cleared survives`() = harnessTest { h ->
        h.knownDao.upsert(h.enrolled(token = OLD_TOKEN))
        h.lan.devices.value = mapOf(h.device.thing to h.lanTarget())
        h.factory.lanClose = LinkClosedException(DisconnectCause.Auth(AuthKind.TOKEN_ROTATED), LanConnectException(LanFailure.AUTH_FAILED, "BYE token-rotated"))
        // BLE renews the credential after the LAN close proved the old one dead and before it is cleared.
        h.rawKnown.beforeClear = { h.rawKnown.renew(ADDRESS, "enc1:$NEW_TOKEN", 47391) }

        val e = async { runCatching { h.repo.lendLink(ADDRESS, LinkPreference.LAN) } }.also { h.until { it.isCompleted } }.await().exceptionOrNull()

        assertEquals(DisconnectCause.Auth(AuthKind.TOKEN_ROTATED), (e as LinkClosedException).disconnectCause)
        assertNull("the hook ran", h.rawKnown.beforeClear)
        assertTrue("the renewed credential survives", h.knownDao.lanToken(ADDRESS) == NEW_TOKEN)
        assertEquals(47391, h.knownDao.get(ADDRESS)!!.lanPort)
    }

    @Test
    fun `an open LAN session the device ends with BYE 10 clears its token`() = harnessTest { h ->
        h.knownDao.upsert(h.enrolled(token = OLD_TOKEN))
        h.lan.devices.value = mapOf(h.device.thing to h.lanTarget())
        h.factory.lanOpens = true

        val lent = async { h.repo.lendLink(ADDRESS, LinkPreference.LAN) }.also { h.until { it.isCompleted } }.await()
        assertEquals(TransportKind.LAN, lent.kind)
        assertEquals("the token survives while the session is open", OLD_TOKEN, h.knownDao.lanToken(ADDRESS))

        // BYE 10 after HELLO. Only the close watcher of the open session sees it.
        h.factory.openLanLinks.single().abort(DisconnectCause.Auth(AuthKind.TOKEN_ROTATED))
        h.advance(1_000)
        assertNull("the dead token is cleared", h.knownDao.lanToken(ADDRESS))
        assertEquals(0, h.factory.fakeConnects)
    }

    @Test
    fun `unexpected LAN failure closes the probe and falls back to Bluetooth`() = harnessTest { h ->
        h.knownDao.upsert(h.enrolled(token = OLD_TOKEN))
        h.lan.devices.value = mapOf(h.device.thing to h.lanTarget())
        h.factory.lanFailure = IllegalStateException("socket factory exploded")

        h.repo.connect(ADDRESS, "OS-6b40")
        h.until { h.repo.connectionState.value.isReady }

        assertTrue(h.factory.lanTransports.single().closed)
        assertEquals(TransportKind.FAKE, h.repo.activeTransport.value)
        assertEquals("a non-auth failure keeps the stored token", OLD_TOKEN, h.knownDao.lanToken(ADDRESS))
        assertTrue("the detail stays in the log", h.events.contains(DeviceEvent.Info(DeviceMessage.LanFallback(null))))
    }

    @Test
    fun `LAN only with no discovered target never constructs Bluetooth`() = harnessTest { h ->
        h.knownDao.upsert(h.enrolled(token = OLD_TOKEN))

        h.repo.connect(ADDRESS, "OS-6b40", preference = TransportPreference.LAN_ONLY)
        runCurrent()

        assertEquals(0, h.factory.fakeConnects)
        assertTrue(h.factory.lanTransports.isEmpty())
        assertFalse(h.repo.connectionState.value.isReady)
    }

    @Test
    fun `LAN only failure closes probe without constructing Bluetooth`() = harnessTest { h ->
        h.knownDao.upsert(h.enrolled(token = OLD_TOKEN))
        h.lan.devices.value = mapOf(h.device.thing to h.lanTarget())
        h.factory.lanFailure = LanConnectException(LanFailure.UNREACHABLE)

        h.repo.connect(ADDRESS, "OS-6b40", preference = TransportPreference.LAN_ONLY)
        runCurrent()

        assertTrue(h.factory.lanTransports.single().closed)
        assertEquals(0, h.factory.fakeConnects)
        assertFalse(h.repo.connectionState.value.isReady)
        assertEquals(OLD_TOKEN, h.knownDao.lanToken(ADDRESS))
        assertFalse(h.events.any { it is DeviceEvent.Info && it.message is DeviceMessage.LanFallback })
    }

    @Test
    fun `overlapping config commands are refused instead of clearing each other's busy flag`() = harnessTest { h ->
        h.repo.connect(ADDRESS, "OS-6b40", fake = true)
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
        h.repo.connect(ADDRESS, "OS-6b40", fake = true)
        h.until { h.repo.connectionState.value.isReady && h.repo.config.value != null && !h.repo.busy.value }

        h.repo.applyConfig(ConfigPatch.build { lanOn(true) }, DeviceMessage.ConfigChange.LAN_ON)
        h.until { h.events.any { it is DeviceEvent.Info && it.message is DeviceMessage.ConfigSaved } }

        val saved = h.events.filterIsInstance<DeviceEvent.Info>().map { it.message }.filterIsInstance<DeviceMessage.ConfigSaved>().single()
        assertEquals(DeviceMessage.ConfigChange.LAN_ON, saved.change)
    }

    @Test
    fun `clearing bonds and a default save report which settings changed`() = harnessTest { h ->
        h.repo.connect(ADDRESS, "OS-6b40", fake = true)
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
    fun `a secret refused on LAN reports the Bluetooth only code and sends nothing`() = harnessTest { h ->
        h.knownDao.upsert(h.enrolled(token = OLD_TOKEN))
        h.lan.devices.value = mapOf(h.device.thing to h.lanTarget())
        h.factory.lanOpens = true
        h.repo.connect(ADDRESS, "AQ-6b40", preference = TransportPreference.LAN_ONLY)
        h.until { h.repo.connectionState.value.isReady && h.repo.config.value != null && !h.repo.busy.value }
        assertEquals(TransportKind.LAN, h.repo.activeTransport.value)
        val configWrites = h.factory.requests.count { it == Opcode.SET_CONFIG }

        h.repo.applyConfig(ConfigPatch.build { wifiSsid("<dev ssid>").wifiPsk("<dev psk>") }, DeviceMessage.ConfigChange.WIFI_SENT)
        h.until { !h.repo.busy.value && h.events.any { it is DeviceEvent.Error } }

        assertEquals(listOf(DeviceEvent.Error(DeviceMessage.SecretNeedsBluetooth)), h.events.filterIsInstance<DeviceEvent.Error>())
        assertEquals("nothing reaches the LAN socket", configWrites, h.factory.requests.count { it == Opcode.SET_CONFIG })
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
        h.repo.connect(ADDRESS, "OS-6b40", fake = true)
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
        h.repo.connect(ADDRESS, "OS-6b40", fake = true)
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
        h.lan.devices.value = mapOf(h.device.thing to h.lanTarget())
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
        assertEquals(h.device.thing, again.thing)
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
    fun `a second collection of a thing already collecting is refused before any OPEN`() = harnessTest { h ->
        h.knownDao.upsert(h.enrolled(token = null))
        // Another collection of the same thing, on another link, holds its admission.
        assertTrue(h.coordinator.tryAdmit(h.device.thing))
        val refused = async { h.repo.syncInBackground(ADDRESS, null, owner = "synthetic-worker", protection = KeepAlive.None) }
        h.until(120_000) { refused.isCompleted }
        refused.await()
        assertFalse(h.factory.requests.contains(Opcode.OPEN))
        assertTrue(h.archived.rows.isEmpty())
        h.coordinator.release(h.device.thing)
        val next = async { h.repo.syncInBackground(ADDRESS, null, owner = "synthetic-worker", protection = KeepAlive.None) }
        h.until { next.isCompleted }
        assertEquals(h.device.files.size, next.await().newFiles)
    }

    @Test
    fun `a lend while the facade collection runs is Busy`() = harnessTest(chunkDelayMs = 1) { h ->
        h.repo.connect(ADDRESS, "OS-6b40", fake = true)
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
        // One message for one failure, the open does not add its own Bluetooth line.
        assertEquals(listOf<DeviceEvent>(DeviceEvent.Error(DeviceMessage.SyncEnded(SyncEndReason.BLUETOOTH_OFF))), h.events.filterIsInstance<DeviceEvent.Error>())
    }

    @Test
    fun `a missing Bluetooth permission is named on connect`() = harnessTest { h ->
        h.factory.unavailable = UnavailableKind.PERMISSION_MISSING
        h.repo.connect(ADDRESS, "OS-6b40")
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

    @Test
    fun `the last sync and the archive summary survive a disconnect and a reconnect`() = harnessTest { h ->
        h.repo.connect(ADDRESS, "OS-6b40", fake = true)
        h.until { h.repo.plan.value != null }
        h.repo.syncAll()
        h.until { !h.repo.collectionState.value.active && !h.repo.busy.value && h.archived.rows.size == h.device.files.size }
        h.until { h.repo.archiveTimeSummary.value.files == h.archived.rows.size }

        h.repo.disconnectNow()
        h.until { h.repo.session.value == null }
        assertEquals(h.archived.rows.size, h.repo.archiveTimeSummary.value.files)
        val last = h.owner.sync.value
        assertEquals(SyncEndReason.COMPLETED, last.endReason)
        assertEquals(h.device.files.size, last.outcomes.size)

        h.repo.connect(ADDRESS, "OS-6b40", fake = true)
        h.until { h.repo.plan.value != null }
        assertEquals(last, h.owner.sync.value)
        assertEquals(h.archived.rows.size, h.repo.archiveTimeSummary.value.files)
    }

    @Test
    fun `the archive summary counts every archived row before any connection`() = harnessTest { h ->
        h.knownDao.upsert(h.enrolled(token = null))
        val thing = h.device.thing
        h.archived.upsert(archivedRow(thing, "a.parquet", indexed = true))
        h.archived.upsert(archivedRow(thing, "b.parquet", indexed = false))
        h.archived.upsert(archivedRow("other", "c.parquet", indexed = true))
        h.until { h.repo.archiveTimeSummary.value.files == 2 }
        assertNull(h.repo.session.value)
        assertEquals(10L, h.repo.archiveTimeSummary.value.rows)
    }

    @Test
    fun `ownership names who holds the device through an interactive and a background run`() = harnessTest(chunkDelayMs = 1) { h ->
        h.repo.connect(ADDRESS, "OS-6b40", fake = true)
        h.until { h.repo.plan.value != null }
        assertEquals(DeviceOwnership.IDLE, h.repo.ownershipNow())
        h.repo.syncAll()
        h.until { h.archived.rows.isNotEmpty() }
        h.until { h.repo.ownership.value == DeviceOwnership.INTERACTIVE }
        assertTrue(h.repo.sync.value.running)
        assertNotNull(h.repo.session.value)
        h.until { !h.repo.collectionState.value.active && !h.repo.busy.value && h.archived.rows.size == h.device.files.size }
        h.until { h.repo.ownership.value == DeviceOwnership.IDLE }
        assertEquals(SyncEndReason.COMPLETED, h.repo.sync.value.endReason)

        h.repo.disconnectNow()
        assertNull(h.repo.session.value)
        val work = async { h.repo.syncInBackground(ADDRESS, null, owner = "synthetic-worker", protection = KeepAlive.None) }
        h.until { h.repo.ownership.value == DeviceOwnership.BACKGROUND || work.isCompleted }
        h.until { work.isCompleted }
        h.until { h.repo.ownership.value == DeviceOwnership.IDLE }
    }

    @Test
    fun `a link that died during a stopped sync does not stay in the slot`() = harnessTest(chunkDelayMs = 1) { h ->
        h.repo.connect(ADDRESS, "OS-6b40", fake = true)
        h.until { h.repo.plan.value != null }
        h.repo.syncAll()
        h.until { h.archived.rows.isNotEmpty() }
        h.device.disconnect()
        h.until { h.repo.collectionState.value is CollectionState.Waiting }
        h.repo.cancelSync()
        h.until { !h.repo.collectionState.value.active && h.repo.ownership.value == DeviceOwnership.IDLE }
        h.until { h.repo.session.value == null }
        assertNull(h.repo.interactiveSessionAddress)
        assertEquals(DeviceOwnership.IDLE, h.repo.ownershipNow())
    }

    @Test
    fun `a refused restart keeps the device reserved until the stopped run let go`() = harnessTest { h ->
        h.factory.listDelayMs = 60_000
        h.repo.syncAll()
        h.until { (h.repo.collectionState.value as? CollectionState.Preparing)?.step == PrepareStep.LIST }
        h.repo.stop(StopSource.USER)
        h.refuseProcess = true
        h.repo.syncAll()
        assertTrue(h.repo.collectionState.value is CollectionState.Failed)
        // The stopped run still holds its link, so nothing else may claim the device yet.
        assertEquals(DeviceOwnership.INTERACTIVE, h.repo.ownershipNow())
        assertTrue(h.repo.busy.value)
        h.until { h.processLeases == 0 && h.repo.ownershipNow() == DeviceOwnership.IDLE && !h.repo.busy.value }
        assertNull(h.repo.session.value)
    }

    private fun archivedRow(thing: String, name: String, indexed: Boolean) = ArchivedFile(
        thing = thing,
        name = name,
        bytes = 100,
        crc32 = 1,
        sha256 = "",
        syncedAtEpochMs = 1,
        sourceDevice = ADDRESS,
        rows = 10,
        utcRows = 10,
        indexedAtEpochMs = if (indexed) 1 else null,
    )

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
        val thingEpochs = InMemoryThingEpochDao()

        var clockEpochOffset = 0L
        var partialFailuresRemaining = 0
        val failedStorageNames = mutableListOf<String>()
        val committedNames = mutableListOf<String>()
        val clockAnchorsBeforeList = mutableListOf<Int>()
        val derivedSince = mutableListOf<Long>()

        /** When set, each post-sync derive waits for it, as a derive behind a slow backfill does. */
        var deriveGate: CompletableDeferred<Unit>? = null
        val thingDerives = mutableListOf<String>()

        /** When set, each re-dating derive after a clock receipt waits for it, as one behind a History pass does. */
        var thingDeriveGate: CompletableDeferred<Unit>? = null
        val owner: DeviceLinkOwner
        val repo: DeviceFacade
        var refuseProcess = false
        var processLeases = 0
        var processAcquires = 0
        val coordinator = DeviceCoordinator()

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
                        deriveGate?.await()
                        return deriveStale()
                    }
                    override suspend fun deriveThing(thing: String) {
                        thingDerives += thing
                        thingDeriveGate?.await()
                    }
                    override suspend fun persistClockAnchor(thing: String, boot: String, utcNs: Long, monotonicUs: Long) {
                        assertEquals(device.thing, thing)
                        assertEquals(device.bootId, boot)
                        assertTrue(utcNs > 0)
                        clockAnchorsBeforeList += factory.requests.count { it == Opcode.LIST }
                    }
                },
                companionPresence = { _, _ -> },
                identityClaims = identityClaims,
                thingEpochDao = thingEpochs,
                applicationScope = appScope,
                io = dispatcher,
                clock = { clockEpochOffset + test.testScheduler.currentTime },
                pruned = PrunedFiles.None,
                boardDescriptors = BoardDescriptors(MemoryBlockStore(), ServedTemplates(), io = dispatcher),
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
            val engine = SyncEngine(store, RoomArchiveIndex(archived), HistoryIndexer(fileArchive, archived, coordinator).asRecordIndexer(fileArchive), windowRetryBackoffMs = 1, clock = { test.testScheduler.currentTime }, io = dispatcher, coordinator = coordinator)
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
            factory.transferActive = { owner.collectionActive.value }
            appScope.launch { repo.events.collect { events += it } }
        }

        fun shutdown() {
            appScope.cancel()
            device.close()
        }

        fun enrolled(token: String?) = KnownDevice(
            address = endpoint,
            name = "OS-6b40",
            thing = device.thing,
            deviceId = device.deviceId,
            firmware = "fake",
            lastSeenEpochMs = 1,
            lastConnectedEpochMs = 1,
            lanToken = token,
            lanPort = 47390,
        )

        fun newFile() = FakeTransport.FakeFile(
            "station=${device.thing}/year=2026/month=09/day=17/data_0900_${device.bootId}_20000-20089-90.parquet",
            device.files.first().bytes,
        )

        fun lanTarget() = LanDevice(
            serviceName = "OS-6b40",
            host = InetAddress.getLoopbackAddress(),
            port = 47390,
            txt = mapOf(LanFraming.TXT_THING to device.thing, LanFraming.TXT_DEV to device.deviceId),
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

        /** When set, the LAN link's open fails with this typed close, as the TCP driver reports a BYE. */
        var lanClose: LinkClosedException? = null

        /** When set, LAN links open over the fake device, so a test can end an open LAN session. */
        var lanOpens = false
        val openLanLinks = CopyOnWriteArrayList<AndroidLink>()
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

        /**
         * When set, Bluetooth links negotiate MTU 247 and the board omits every LIVE and STATUS
         * notification, as it does for a snapshot over 244 bytes (#159). Each characteristic read
         * returns a newer value and takes [snapshotReadMs] of virtual time.
         */
        var smallMtu = false
        var snapshotReadMs = 0L

        /** The next this many snapshot reads fail, as a transient ATT error does. */
        var failSnapshotReads = 0

        /** Whether a transfer owned the device, checked when each LIVE read starts. */
        var transferActive: () -> Boolean = { false }
        val snapshotReads = CopyOnWriteArrayList<String>()
        val readsDuringTransfer = CopyOnWriteArrayList<String>()
        private var readsInFlight = 0

        /** Control writes issued while a snapshot read was in flight. */
        val writesDuringRead = CopyOnWriteArrayList<Int>()
        private var liveSeq = 100L
        private var statusUp = 5_000L
        private fun clockStatus(payload: ByteArray): ByteArray = if (clockMissingOnReconnect && fakeConnects > 1) payload.decodeToString().replace("\"utc\":1", "\"utc\":0").encodeToByteArray() else payload
        private val observedDevice = object : DeviceTransport by device {
            override val statusNotifications: Flow<ByteArray> = device.statusNotifications.map(::clockStatus)
            override suspend fun readStatus(): ByteArray = clockStatus(device.readStatus())
            override suspend fun readInfo(): ByteArray {
                val info = device.readInfo()
                return if (rejectReconnect && fakeConnects > 1) info.decodeToString().replace("\"proto\":3", "\"proto\":2").encodeToByteArray() else info
            }

            override suspend fun writeControl(request: ByteArray) {
                val op = request.first().toInt() and 0xff
                requests += op
                if (readsInFlight > 0) writesDuringRead += op
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

        /** A subscribed characteristic the board never notifies. It stays open and emits nothing. */
        private val silent: Flow<ByteArray> = flow { awaitCancellation() }

        /** A board at MTU 247 that caches each snapshot but never notifies it. */
        private val smallMtuDevice = object : DeviceTransport by observedDevice {
            override val kind = TransportKind.BLE
            override val mtu = 247
            override val statusNotifications: Flow<ByteArray> = silent
            override val liveNotifications: Flow<ByteArray> = silent
            override suspend fun readLive(): ByteArray = snapshotRead("live") { """{"seq":${++liveSeq},"pm25":${liveSeq % 50}}""" }
            override suspend fun readStatus(): ByteArray = snapshotRead("status") {
                observedDevice.readStatus().decodeToString().replace(Regex(""""up_s":\d+"""), """"up_s":${++statusUp}""")
            }
        }

        private suspend fun snapshotRead(name: String, value: suspend () -> String): ByteArray {
            snapshotReads += name
            // The collection's clock check reads STATUS itself. Only the snapshot poller reads LIVE.
            if (name == "live" && transferActive()) readsDuringTransfer += name
            readsInFlight++
            try {
                delay(snapshotReadMs)
                if (failSnapshotReads > 0) {
                    failSnapshotReads--
                    throw TransportException("read failed")
                }
                return value().encodeToByteArray()
            } finally {
                readsInFlight--
            }
        }

        override fun ble(address: String): AndroidLink = requireRadio().let { counted().also { interactiveConnects++ } }
        override fun bleUnattended(address: String, connectTimeoutMs: Long): AndroidLink = requireRadio().let { counted() }.also {
            unattendedConnects++
            unattendedTimeout = connectTimeoutMs
        }
        override fun lan(address: String, name: String?, host: InetAddress, port: Int, token: ByteArray): AndroidLink {
            if (lanOpens) {
                val overLan = object : DeviceTransport by observedDevice {
                    override val kind = TransportKind.LAN
                }
                return TransportLink(overLan, scope).also { openLanLinks += it }
            }
            val link = FailingLanTransport(address, lanFailure).also { lanTransports += it }.let { TransportLink(it, scope) }
            val close = lanClose ?: return link
            return object : AndroidLink by link {
                override suspend fun open(sink: FrameSink): LinkInfo = throw close
            }
        }
        override fun fake(address: String): AndroidLink = counted()
        override fun isFake(transport: DeviceTransport): Boolean = (transport as? TransportLink)?.wrapped === observedDevice

        private fun requireRadio() {
            unavailable?.let { throw TransportUnavailableException(it, "synthetic ${it.name}") }
        }

        private fun counted(): AndroidLink {
            fakeConnects++
            if (hangConnect) return TransportLink(HangingTransport(observedDevice), scope)
            val link = TransportLink(if (smallMtu) smallMtuDevice else observedDevice, scope)
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
    private class HangingTransport(device: DeviceTransport) : DeviceTransport by device {
        override suspend fun connect() = awaitCancellation()
    }

    private class FailingLanTransport(override val address: String, private val failure: Exception) : DeviceTransport {
        @Volatile var closed = false
        override val displayName: String = "OS-6b40"
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

        /** Runs once just before the next clear lands, as a concurrent renewal would. */
        var beforeClear: (() -> Unit)? = null

        fun renew(address: String, stored: String, port: Int) {
            rows.firstOrNull { it.address == address }?.let { replace(it.copy(lanToken = stored, lanPort = port)) }
        }

        override suspend fun setLanToken(address: String, tokenHex: String?, port: Int?) {
            if (tokenHex == null) beforeClear?.also { beforeClear = null }?.invoke()
            get(address)?.let { replace(it.copy(lanToken = tokenHex, lanPort = port)) }
        }
        override suspend fun clearLanTokenIfStored(address: String, expected: String): Int {
            beforeClear?.also { beforeClear = null }?.invoke()
            val row = get(address)?.takeIf { it.lanToken == expected } ?: return 0
            replace(row.copy(lanToken = null, lanPort = null))
            return 1
        }
        override suspend fun recordConnection(address: String, name: String?, thing: String?, deviceId: String?, firmware: String?, epochMs: Long) {
            get(address)?.let {
                replace(it.copy(name = name ?: it.name, thing = thing, deviceId = deviceId, firmware = firmware, lastSeenEpochMs = epochMs, lastConnectedEpochMs = epochMs))
            }
        }
        override suspend fun findByThing(thing: String) = rows.firstOrNull { it.thing == thing }
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
        override fun observeForThing(thing: String): Flow<List<ArchivedFile>> = state.map { all -> all.filter { it.thing == thing } }
        override suspend fun forThing(thing: String) = rows.filter { it.thing == thing }
        override suspend fun get(thing: String, name: String) = rows.firstOrNull { it.thing == thing && it.name == name }
        override fun observeCount(): Flow<Int> = flowOf(rows.size)
        override fun observeTotalBytes(): Flow<Long> = flowOf(rows.sumOf { it.bytes })
        override suspend fun unindexed(limit: Int) = rows.filter { it.indexedAtEpochMs == null || it.indexError != null }.take(limit)
        override fun observeUnindexedCount(): Flow<Int> = flowOf(rows.count { it.indexedAtEpochMs == null || it.indexError != null })
        override suspend fun withUtcBetween(thing: String, fromNs: Long, toNs: Long) = emptyList<ArchivedFile>()
        override suspend fun indexedForThing(thing: String) = rows.filter { it.thing == thing && it.isIndexed }
        override suspend fun things() = rows.map { it.thing }.distinct()
        override suspend fun upsert(file: ArchivedFile) {
            state.value = rows.filterNot { it.thing == file.thing && it.name == file.name } + file
        }
        override suspend fun insertIfAbsent(file: ArchivedFile): Long {
            if (rows.any { it.thing == file.thing && it.name == file.name }) return -1L
            state.value = rows + file
            return rows.size.toLong()
        }
        override suspend fun delete(thing: String, name: String) {
            state.value = rows.filterNot { it.thing == thing && it.name == name }
        }
    }

    private class InMemoryThingEpochDao : ThingEpochDao {
        private val rows = CopyOnWriteArrayList<ThingEpoch>()

        /** Runs once inside the next epoch write, the suspension between enrollment's read and its write. */
        var beforeRecord: (suspend () -> Unit)? = null

        override suspend fun recordIfAbsent(epoch: ThingEpoch): Long {
            beforeRecord?.also { beforeRecord = null }?.invoke()
            if (rows.any { it.address == epoch.address && it.thing == epoch.thing }) return -1
            rows += epoch
            return 1
        }
        override suspend fun forDevice(address: String) = rows.filter { it.address == address }
        override fun observeForDevice(address: String): Flow<List<ThingEpoch>> = flowOf(forDeviceNow(address))
        private fun forDeviceNow(address: String) = rows.filter { it.address == address }
    }

    private companion object {
        /** Built at runtime: a 64-hex literal looks like a leaked key to the secret scan. */
        val OLD_TOKEN = "0f".repeat(32)
        val NEW_TOKEN = "1e".repeat(32)
        const val ADDRESS = "E0:5A:1B:F6:6B:40"
        const val STEP_MS = 50L
    }
}
