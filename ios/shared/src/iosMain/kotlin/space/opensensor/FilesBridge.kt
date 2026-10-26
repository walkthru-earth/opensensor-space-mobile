package space.opensensor

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import space.opensensor.archive.ArchiveRecord
import space.opensensor.archive.PrunedFile
import space.opensensor.data.ManifestState
import space.opensensor.data.sync.ArchivedIdentity
import space.opensensor.data.sync.FileOutcome
import space.opensensor.data.sync.SyncPlanner
import space.opensensor.files.DeviceFiles
import space.opensensor.files.DeviceFilesAction
import kotlin.time.Clock
import space.opensensor.data.SyncProgress as ScreenSyncProgress

/** Native ownership stays in Swift. Kotlin takes a lease of the connection's link only for a LIST request. */
internal class DeviceFilesActions(
    /** The open connection's link to this hardware id, or null when it is not connected. */
    val openLink: (device: String) -> IosLink?,
    val syncAll: (peripheral: String) -> Unit,
    val syncOne: (peripheral: String, name: String) -> Unit,
    val cancelSync: (peripheral: String) -> Unit,
)

/** One sensor's manifest and plan, independent of the whole-phone export controller. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class IosDeviceFiles(
    private val address: String,
    private val devices: DevicesBridge,
    private val actions: DeviceFilesActions,
    private val scope: CoroutineScope,
) {
    private val listing = MutableStateFlow<ManifestState>(ManifestState.Idle)
    private val listMutex = Mutex()
    private fun currentLink(): SensorFilesLink {
        val thing = devices.thingFor(address)
        val lan = devices.state.connectedLanForThing(thing)
        val ble = devices.state.connected && devices.state.selectedId.equals(address, ignoreCase = true)
        val connected = lan != null || ble
        val device = lan?.let { devices.state.lanInfo[it]?.dev }
            ?: devices.state.info?.dev?.takeIf { ble }
        val run = lan?.let { devices.state.lanRuns[it] }
        val lanProgress = lan?.let { devices.state.lanSessions[it] }
        // A run whose Bluetooth link dropped is dialling it again, so it still counts as running.
        val reconnecting = devices.state.syncBusy && devices.state.reconnectingId.equals(address, ignoreCase = true)
        val progress = if (ble || reconnecting) devices.state.syncProgress else null
        val result = run?.result ?: devices.state.lastSyncResult.takeIf { ble }
        return SensorFilesLink(
            thing,
            device,
            connected,
            run?.running == true || (ble && devices.state.syncBusy) || reconnecting,
            progress,
            lanProgress,
            result,
        )
    }
    private data class Inputs(val link: SensorFilesLink, val manifest: ManifestState, val records: List<ArchiveRecord>, val removed: List<PrunedFile>)

    private val selected = combine(devices.known, snapshotFlow { currentLink() }) { _, _ -> currentLink() }.distinctUntilChanged()

    val state = combine(selected, listing, IosArchive.index.observeAll(), IosArchive.pruned.observeAll()) { link, manifest, records, removed ->
        Inputs(link, manifest, records, removed)
    }.mapLatest { (link, manifest, records, removed) ->
        val plan = if (link.thing != null && manifest.last != null) {
            val archived = records.map { ArchivedIdentity(it.thing, it.name, it.bytes, it.crc32) }
            SyncPlanner.plan(link.thing, manifest.last!!.files, archived, IosArchive.store.partialLengths(link.thing), removed)
        } else {
            null
        }
        val p = link.progress
        DeviceFiles(
            ready = link.connected,
            manifest = manifest,
            plan = plan,
            sync = ScreenSyncProgress(
                running = link.running,
                filesDone = p?.filesDone ?: link.lanProgress?.filesDone ?: 0,
                filesTotal = p?.filesTotal ?: link.lanProgress?.filesTotal ?: 0,
                bytesDone = p?.receivedBytes ?: link.lanProgress?.bytesDone ?: 0,
                bytesTotal = p?.bytesTotal ?: link.lanProgress?.bytesTotal ?: 0,
                bytesPerSecond = p?.bytesPerSecond ?: link.lanProgress?.bytesPerSecond ?: 0.0,
                outcomes = link.result?.failures?.map { FileOutcome.Failed(it.name, it.problem.name, it.problem.name == "STORAGE") }.orEmpty(),
            ),
        )
    }.stateIn(scope, SharingStarted.Eagerly, DeviceFiles(false, ManifestState.Idle, null, ScreenSyncProgress()))

    init {
        scope.launch {
            var priorThing: String? = null
            var priorReady = false
            var priorRunning = false
            selected.collect { link ->
                if (link.thing != priorThing) {
                    listing.value = ManifestState.Idle
                    priorThing = link.thing
                    priorReady = false
                }
                if (link.connected && !priorReady && link.thing != null) refresh()
                // The sync's lease ended before Swift heard its result, so LIST can go at once.
                if (link.connected && priorRunning && !link.running) refresh()
                priorReady = link.connected
                priorRunning = link.running
            }
        }
    }

    fun onAction(action: DeviceFilesAction) {
        when (action) {
            DeviceFilesAction.Refresh -> refresh()
            DeviceFilesAction.SyncAll -> actions.syncAll(address)
            is DeviceFilesAction.SyncOne -> actions.syncOne(address, action.name)
            DeviceFilesAction.CancelSync -> actions.cancelSync(address)
        }
    }

    fun refresh() {
        scope.launch {
            if (!listMutex.tryLock()) return@launch
            try {
                val linkState = selected.first()
                val thing = linkState.thing ?: return@launch
                val hardware = linkState.device ?: return@launch
                if (!linkState.connected || linkState.running) return@launch
                listing.value = listing.value.refreshing()
                val link = withContext(Dispatchers.Main) { actions.openLink(hardware) }
                    ?: run {
                        listing.value = listing.value.failed("LINK_BUSY")
                        return@launch
                    }
                val (files, end) = link.leased(LeaseKind.FILES) { it.list() }
                if (selected.first().thing == thing) {
                    listing.value = listing.value.loaded(files, end, Clock.System.now().toEpochMilliseconds())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: LeaseRefusedException) {
                listing.value = listing.value.failed("LINK_BUSY")
            } catch (e: Exception) {
                listing.value = listing.value.failed(e.message ?: "LIST_FAILED")
            } finally {
                listMutex.unlock()
            }
        }
    }

    private data class SensorFilesLink(
        val thing: String?,
        val device: String?,
        val connected: Boolean,
        val running: Boolean,
        val progress: SyncProgress?,
        val lanProgress: LanSessionState?,
        val result: SyncResult?,
    )
}
