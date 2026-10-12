package earth.walkthru.opensensor.discover

import earth.walkthru.aqsync.airquality.StationSummary
import earth.walkthru.aqsync.publicfeed.CellFreshness
import earth.walkthru.aqsync.publicfeed.CellGeoJson
import earth.walkthru.aqsync.publicfeed.CellRow
import earth.walkthru.aqsync.publicfeed.CellScale
import earth.walkthru.aqsync.publicfeed.FeedPartition
import earth.walkthru.aqsync.publicfeed.FeedProperty
import earth.walkthru.aqsync.publicfeed.FeedWindow
import earth.walkthru.aqsync.publicfeed.GeoBounds
import earth.walkthru.aqsync.publicfeed.H3Grid
import earth.walkthru.aqsync.publicfeed.H3Index
import earth.walkthru.aqsync.publicfeed.LatLng
import earth.walkthru.aqsync.publicfeed.PropertyRanking
import earth.walkthru.aqsync.publicfeed.PublicFeed
import earth.walkthru.aqsync.publicfeed.PublicSummary
import earth.walkthru.aqsync.publicfeed.TimeZoneRegions
import earth.walkthru.aqsync.publicfeed.ViewportCover
import earth.walkthru.aqsync.settings.DiscoverSource
import earth.walkthru.aqsync.settings.effectiveDiscoverSource
import earth.walkthru.aqsync.storage.MemoryTrimmable
import earth.walkthru.aqsync.storage.trimOnMemoryPressure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

/** A camera worth restoring: where the map was last left. */
data class SavedCamera(val lat: Double, val lng: Double, val zoom: Double)

/** What Discover remembers between launches. Never a location fix, only the map camera. */
data class DiscoverSaved(
    val camera: SavedCamera? = null,
    val property: String? = null,
    val window: FeedWindow = FeedWindow.DAY,
    val firstRunDone: Boolean = false,
)

/** Persistence for [DiscoverSaved]. Android uses DataStore, iOS uses NSUserDefaults. */
interface DiscoverPrefs {
    suspend fun load(): DiscoverSaved

    suspend fun save(saved: DiscoverSaved)
}

/** Where the map opens. A saved camera wins, then the time zone region. */
sealed interface StartView {
    data class Camera(val camera: SavedCamera) : StartView

    data class Region(val bounds: GeoBounds) : StartView
}

/** The camera after it rested for 400 ms. */
data class CameraRest(val bounds: GeoBounds, val zoom: Double, val lat: Double, val lng: Double)

/** Everything the Discover screen draws. */
data class DiscoverUiState(
    val start: StartView? = null,
    /** False on a platform that cannot read the feed or compute H3 yet (iOS until W8). */
    val available: Boolean = true,
    val loading: Boolean = true,
    val isSample: Boolean = true,
    val window: FeedWindow = FeedWindow.DAY,
    val dictionary: List<FeedProperty> = emptyList(),
    /** Properties reported in view, most reported first. */
    val ranked: List<FeedProperty> = emptyList(),
    val chips: List<FeedProperty> = emptyList(),
    val property: FeedProperty? = null,
    /** WHO steps for PM2.5 and PM10, the percentile ramp for the rest. */
    val scale: CellScale? = null,
    val rows: List<CellRow> = emptyList(),
    val geoJson: String = EMPTY_GEOJSON,
    val selected: CellRow? = null,
    /**
     * The selected cell in each window that has been read. The current window comes with the
     * selection, the other two follow from one read each, for that one cell.
     */
    val selectedWindows: Map<FeedWindow, CellRow?> = emptyMap(),
    /**
     * Windows of the selected cell whose read failed after its retries. Never in [selectedWindows].
     * Cleared by a new cell, window or resolution, and by a later read that succeeds.
     */
    val failedWindows: Set<FeedWindow> = emptySet(),
    /** The feed on screen. [DiscoverSource.OPENAQ] only while public sensors is on. */
    val source: DiscoverSource = DiscoverSource.SAMPLE,
    /** True when the Sample and Real toggle can switch, public sensors on and a live feed built. */
    val sourceEnabled: Boolean = false,
    /** The windows the feed on screen offers. The real feed has no last hour. */
    val windows: List<FeedWindow> = FeedWindow.entries,
    /** The local day the real data runs to, for the toggle label. Null for the sample. */
    val dataDate: LocalDate? = null,
    /** True when each row is a daily mean. Freshness is then read against [dataDate], never the clock. */
    val daily: Boolean = false,
    /** True when the real feed could not be read. The map shows an error with Retry, never the sample. */
    val loadFailed: Boolean = false,
    val newestUpdateEpochMs: Long? = null,
    val firstRun: Boolean = false,
    val res: Int = ViewportCover.MIN_RES,
) {
    companion object {
        const val EMPTY_GEOJSON = "{\"type\":\"FeatureCollection\",\"features\":[]}"
    }
}

/** The selected cell's summary for [property], three windows, headline the 24 hour mean. Null with no selection. */
fun DiscoverUiState.selectedSummary(property: FeedProperty): StationSummary? {
    selected ?: return null
    return PublicSummary.of(property.id, property.unit, selectedWindows, offered = windows)
}

/**
 * What the Simple cell sheet shows under the headline, all from the 24 hour row like the headline.
 * The row of the window on screen can still be the previous window's during a switch, so it is
 * never read for values. Null [devices] and [updatedEpochMs] until the 24 hour row lands.
 */
internal data class SimpleCellFacts(
    val devices: Int?,
    val updatedEpochMs: Long?,
    val others: List<Pair<FeedProperty, Double?>>,
    /** True until the 24 hour row is read. The sheet then shows a neutral loading line, no values. */
    val loading: Boolean,
)

/** What the Simple cell sheet leads with. */
internal enum class SimpleHeadline {
    /** The 24 hour row is not read yet. No mood, no "No reading", no "Not measured". */
    LOADING,

    /** A read failed after its retries, the 24 hour one or one that could still hold the property. */
    FAILED,

    /** Read, and no window read so far reports the property. */
    NOT_MEASURED,

    /** Read. The headline shows the 24 hour mean, or "n/a" when that row lacks it. */
    SHOWN,
}

/**
 * A window key that is absent was not read yet, or is in [DiscoverUiState.failedWindows] when its
 * read failed after the retries. A key that is present was read, its row null when the cell has
 * nothing in that window. A failure is never taken as "not measured".
 */
internal fun DiscoverUiState.simpleHeadline(): SimpleHeadline {
    val p = property ?: return SimpleHeadline.NOT_MEASURED
    if (selected == null) return SimpleHeadline.NOT_MEASURED
    if (FeedWindow.DAY !in selectedWindows) {
        return if (FeedWindow.DAY in failedWindows) SimpleHeadline.FAILED else SimpleHeadline.LOADING
    }
    val measured = selectedWindows.values.any { it?.stats?.containsKey(p.id) == true }
    return when {
        measured -> SimpleHeadline.SHOWN
        failedWindows.isNotEmpty() -> SimpleHeadline.FAILED
        else -> SimpleHeadline.NOT_MEASURED
    }
}

/**
 * The selected cell's 24 hour facts. [SimpleCellFacts.others] lists every other measurement the
 * cell reports, its 24 hour mean or null ("n/a") while that row is missing or lacks it.
 */
internal fun DiscoverUiState.simpleCellFacts(): SimpleCellFacts {
    if (selected != null && FeedWindow.DAY !in selectedWindows) return SimpleCellFacts(null, null, emptyList(), loading = true)
    val day = if (selected == null) null else selectedWindows[FeedWindow.DAY]
    val others = dictionary
        .filter { p -> p.id != property?.id && (day?.mean(p.id) != null || selected?.mean(p.id) != null) }
        .map { p -> p to day?.mean(p.id) }
    return SimpleCellFacts(day?.devices, day?.updatedEpochMs, others, loading = false)
}

/**
 * State holder of the Discover screen. Android keeps it in a ViewModel, iOS in the composition.
 * Reads run on [io], never on the main thread, and only after the camera rests for 400 ms.
 * [feed] is the bundled sample. [liveFeed] is the real feed, read only while the host reports public
 * sensors on and the saved source is [DiscoverSource.OPENAQ] ([onSourceSettings]).
 */
class DiscoverController(
    private val scope: CoroutineScope,
    private val feed: PublicFeed,
    private val h3: H3Grid,
    private val prefs: DiscoverPrefs,
    private val timeZoneId: String,
    private val now: () -> Long,
    private val io: CoroutineDispatcher = Dispatchers.Default,
    private val liveFeed: PublicFeed? = null,
    /** Persists the toggle's choice. The host's settings flow then reports it back. */
    private val saveSource: suspend (DiscoverSource) -> Unit = {},
) {
    private val _state = MutableStateFlow(DiscoverUiState(available = feed.available && h3.available, isSample = feed.isSample, daily = feed.isDaily, windows = feed.windows))
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    private val rest = MutableStateFlow<CameraRest?>(null)
    private val reload = MutableStateFlow(0)
    private var saved = DiscoverSaved()

    /** The feed every read goes to. Swapped only by [switchTo]. */
    private var active: PublicFeed = feed
    private var publicSensors = false
    private var savedSource = DiscoverSource.SAMPLE
    private var partition: FeedPartition? = null
    private var restyleJob: Job? = null
    private var windowsJob: Job? = null

    /** The cell, window and resolution [windowsJob] read for, so a pan does not read them again. */
    private var windowsFor: Triple<Long, FeedWindow, Int>? = null

    /** The window the rows on screen were read for. It lags `state.window` until the reload lands. */
    private var rowsWindow: FeedWindow? = null

    init {
        // A feed that caches bytes in memory gives them back under memory pressure (#61).
        if (feed is MemoryTrimmable) scope.trimOnMemoryPressure(feed)
        if (liveFeed is MemoryTrimmable) scope.trimOnMemoryPressure(liveFeed)
        scope.launch {
            saved = catching { prefs.load() }.getOrDefault(DiscoverSaved())
            val start = saved.camera?.let { StartView.Camera(it) } ?: StartView.Region(TimeZoneRegions.regionFor(timeZoneId))
            _state.update { it.copy(start = start, window = offered(saved.window, it.windows), firstRun = !saved.firstRunDone) }
            // One loader. A newer camera rest or a chip change cancels the read in flight.
            combine(rest.filterNotNull(), reload) { r, _ -> r }.collectLatest { r ->
                delay(REST_MS)
                load(r)
            }
        }
    }

    /**
     * Where a map built again should start, the last camera that rested, else [DiscoverUiState.start].
     * A rotation rebuilds the map, and it should not fit the region again over the user's view.
     */
    fun resumeStart(): StartView? = saved.camera?.let { StartView.Camera(it) } ?: _state.value.start

    /** Called on every camera move. The read starts once the camera has rested for 400 ms. */
    fun onCamera(rest: CameraRest) {
        this.rest.value = rest
    }

    /** The coarse cell centre "Near me" moves the camera to, so the saved camera never holds the fix. */
    fun nearMeTarget(lat: Double, lng: Double): LatLng? = nearMeTarget(h3, lat, lng)

    fun selectProperty(id: String) {
        if (_state.value.property?.id == id) return
        saved = saved.copy(property = id)
        persist()
        restyle(id)
    }

    /**
     * The host's saved source and public sensors switch, on every change. The real feed is on screen
     * only for [DiscoverSource.OPENAQ] with public sensors on. A change of feed clears and reloads.
     */
    fun onSourceSettings(saved: DiscoverSource, publicSensors: Boolean) {
        savedSource = saved
        this.publicSensors = publicSensors
        val enabled = publicSensors && liveFeed != null
        if (_state.value.sourceEnabled != enabled) _state.update { it.copy(sourceEnabled = enabled) }
        val next = if (liveFeed != null && effectiveDiscoverSource(saved, publicSensors) == DiscoverSource.OPENAQ) DiscoverSource.OPENAQ else DiscoverSource.SAMPLE
        if (next != _state.value.source) switchTo(next)
    }

    /** The Sample and Real toggle. Ignored while it is disabled. Saves the choice and switches now. */
    fun chooseSource(source: DiscoverSource) {
        if (!_state.value.sourceEnabled || source == savedSource) return
        savedSource = source
        scope.launch { catching { saveSource(source) } }
        onSourceSettings(source, publicSensors)
    }

    /** Reads the feed again after [DiscoverUiState.loadFailed], from the map's Retry. */
    fun retry() {
        if (!_state.value.loadFailed) return
        _state.update { it.copy(loadFailed = false, loading = true) }
        reload.value++
    }

    private fun switchTo(next: DiscoverSource) {
        active = if (next == DiscoverSource.OPENAQ) liveFeed ?: feed else feed
        windowsJob?.cancel()
        windowsJob = null
        windowsFor = null
        rowsWindow = null
        partition = null
        restyleJob?.cancel()
        val windows = active.windows
        _state.update {
            it.copy(
                source = next,
                isSample = active.isSample,
                daily = active.isDaily,
                available = active.available && h3.available,
                windows = windows,
                // Ruling: the last hour moves to the 24 hours on screen only. The saved window stays.
                window = offered(it.window, windows),
                dataDate = null,
                loadFailed = false,
                loading = true,
                ranked = emptyList(),
                chips = emptyList(),
                scale = null,
                rows = emptyList(),
                geoJson = DiscoverUiState.EMPTY_GEOJSON,
                selected = null,
                selectedWindows = emptyMap(),
                failedWindows = emptySet(),
                newestUpdateEpochMs = null,
            )
        }
        reload.value++
    }

    fun selectWindow(next: FeedWindow) {
        if (next !in _state.value.windows) return
        if (_state.value.window == next) {
            // The window on screen may be a coerced one (the last hour shown as the 24 hours in
            // Real). Choosing it is still the user's choice, so it is saved.
            if (saved.window != next) {
                saved = saved.copy(window = next)
                persist()
            }
            return
        }
        saved = saved.copy(window = next)
        persist()
        _state.update { it.copy(window = next, loading = true, failedWindows = emptySet()) }
        reload.value++
    }

    fun selectCell(hex: String?) {
        val row = hex?.let { h -> _state.value.rows.firstOrNull { H3Index.toHex(it.h3) == h } }
        if (row?.h3 == _state.value.selected?.h3) return
        // Rows still from the previous window are not this window's values. The reload fills them.
        val current = row != null && rowsWindow == _state.value.window
        // The selected outline is a filter on its own layer, so the GeoJSON is not rebuilt.
        _state.update { s -> s.copy(selected = row, selectedWindows = if (row != null && current) mapOf(s.window to row) else emptyMap(), failedWindows = emptySet()) }
        readWindows(row.takeIf { current })
    }

    /** Reads the selected cell's windows that are still missing, from the sheet's Retry. */
    fun retryWindows() {
        val s = _state.value
        val row = s.selected ?: return
        if (windowsJob?.isActive == true) return
        val missing = s.windows.filter { it !in s.selectedWindows }
        if (missing.isEmpty()) return
        _state.update { it.copy(failedWindows = emptySet()) }
        readWindows(row, missing)
    }

    fun dismissFirstRun() {
        if (!_state.value.firstRun) return
        saved = saved.copy(firstRunDone = true)
        persist()
        _state.update { it.copy(firstRun = false) }
    }

    private fun persist() {
        val snapshot = saved
        scope.launch { catching { prefs.save(snapshot) } }
    }

    private suspend fun load(r: CameraRest) {
        saved = saved.copy(camera = SavedCamera(r.lat, r.lng, r.zoom))
        persist()
        val res = ViewportCover.resolutionForZoom(r.zoom)
        val source = active
        val real = !source.isSample
        if (!_state.value.available) {
            _state.update { it.copy(loading = false, res = res) }
            return
        }
        // The sample's partition never changes. The real one is asked again on every load, its
        // manifest is cached by the feed, so a new day is picked up and a failed read is retried.
        if (partition == null || real) {
            val asked = withContext(io) { catching { source.latest() }.getOrNull() }
            // A switch made while the manifest answered owns the partition now. Never write this
            // feed's partition over it, or the other feed reads files it does not have.
            if (source !== active) return
            partition = asked ?: partition
        }
        if (source !== active) return
        val p = partition
        if (p == null) {
            _state.update { it.copy(loading = false, res = res, loadFailed = real) }
            return
        }
        val current = _state.value
        val window = current.window
        val read = withContext(io) {
            catching {
                val cover = ViewportCover.cover(h3, r.bounds, res)
                source.cells(p, window, res, cover.ranges)
            }
        }
        if (source !== active) return
        val table = read.getOrNull()
        if (real && table == null) {
            // An error with Retry. Rows of an earlier view are not left on the map as if current.
            windowsJob?.cancel()
            windowsFor = null
            rowsWindow = null
            _state.update {
                it.copy(
                    loading = false,
                    res = res,
                    loadFailed = true,
                    dataDate = partitionDate(p),
                    ranked = emptyList(),
                    chips = emptyList(),
                    scale = null,
                    rows = emptyList(),
                    geoJson = DiscoverUiState.EMPTY_GEOJSON,
                    selected = null,
                    selectedWindows = emptyMap(),
                    failedWindows = emptySet(),
                    newestUpdateEpochMs = null,
                )
            }
            return
        }
        val rows = table?.rows.orEmpty()
        val dictionary = table?.info?.properties.orEmpty().ifEmpty { current.dictionary }
        val ranked = PropertyRanking.rank(rows, dictionary)
        val wanted = current.property?.id ?: saved.property
        val property = ranked.firstOrNull { it.id == wanted } ?: dictionary.firstOrNull { it.id == wanted && ranked.isEmpty() } ?: ranked.firstOrNull()
        // Read after the suspended feed read, so a tap made while it ran is not reverted.
        val selected = _state.value.selected?.let { s -> rows.firstOrNull { it.h3 == s.h3 } }
        val sameWindows = selected != null && windowsFor == Triple(selected.h3, window, res)
        rowsWindow = window
        _state.update {
            it.copy(
                loading = false,
                loadFailed = false,
                isSample = table?.info?.sample ?: source.isSample,
                dataDate = if (real) partitionDate(p) else null,
                dictionary = dictionary,
                ranked = ranked,
                rows = rows,
                selected = selected,
                selectedWindows = when {
                    selected == null -> emptyMap()
                    sameWindows -> it.selectedWindows + (window to selected)
                    else -> mapOf(window to selected)
                },
                failedWindows = if (sameWindows) it.failedWindows - window else emptySet(),
                res = res,
                newestUpdateEpochMs = rows.maxOfOrNull { row -> row.updatedEpochMs },
            )
        }
        if (!sameWindows) {
            readWindows(selected)
        } else if (windowsJob?.isActive != true) {
            // A window whose read failed has no key. The pan reads it again, only that one. It stays
            // failed on screen until the read lands, so the sheet never flips back to loading.
            val missing = _state.value.windows.filter { it !in _state.value.selectedWindows }
            if (missing.isNotEmpty()) readWindows(selected, missing)
        }
        restyle(property?.id)
    }

    /**
     * Reads [row]'s cell in the other windows its feed offers, the 24 hours first, one small read each, the same anonymous
     * feed the map uses. A newer selection cancels it, and a result for an older cell, window or
     * resolution is dropped, so it never lands on the cell selected since. Each window lands as soon
     * as it is read. A read that fails is tried again after 1 s and then 2 s. One that still fails
     * stays out of the map and goes into [DiscoverUiState.failedWindows], and the next pan or the
     * sheet's Retry reads [only] the missing windows again.
     */
    private fun readWindows(row: CellRow?, only: List<FeedWindow>? = null) {
        windowsJob?.cancel()
        windowsJob = null
        windowsFor = null
        row ?: return
        val current = _state.value
        val window = current.window
        val res = current.res
        // The 24 hours first. It is the headline, so a window that fails never delays it.
        val wanted = (only ?: current.windows.filter { it != window }).sortedBy { if (it == FeedWindow.DAY) 0 else 1 }
        val p = partition
        if (p == null) {
            // Nothing to read from. Fail now, so the sheet shows Retry instead of loading forever.
            _state.update { it.copy(failedWindows = it.failedWindows + wanted) }
            return
        }
        val source = active
        windowsFor = Triple(row.h3, window, res)
        windowsJob = scope.launch {
            for (w in wanted) {
                val read = withContext(io) {
                    var result = catching { readCell(source, p, w, res, row.h3) }
                    for (wait in RETRY_DELAYS_MS) {
                        if (result.isSuccess) break
                        delay(wait)
                        result = catching { readCell(source, p, w, res, row.h3) }
                    }
                    result
                }
                ensureActive()
                _state.update {
                    if (source !== active || it.selected?.h3 != row.h3 || it.window != window || it.res != res) return@update it
                    read.fold(
                        onSuccess = { r -> it.copy(selectedWindows = it.selectedWindows + (w to r), failedWindows = it.failedWindows - w) },
                        onFailure = { _ -> it.copy(failedWindows = it.failedWindows + w) },
                    )
                }
            }
        }
    }

    private suspend fun readCell(source: PublicFeed, p: FeedPartition, w: FeedWindow, res: Int, cell: Long): CellRow? =
        source.cells(p, w, res, listOf(cell..cell)).rows.firstOrNull { it.h3 == cell }

    /** Recomputes chips, scale and GeoJSON for [propertyId] from the rows already loaded. */
    private fun restyle(propertyId: String?) {
        val current = _state.value
        val property = current.ranked.firstOrNull { it.id == propertyId } ?: current.dictionary.firstOrNull { it.id == propertyId }
        val rows = current.rows
        restyleJob?.cancel()
        restyleJob = scope.launch {
            // Sample data is a snapshot, so freshness is read against its own newest hour.
            val nowMs = if (current.isSample) rows.maxOfOrNull { it.updatedEpochMs } ?: now() else now()
            // A daily feed lags by days on purpose. Its cells are judged against the partition date.
            val asOf = current.dataDate?.takeIf { current.daily }
            val stale: (CellRow) -> Boolean = if (asOf != null) {
                { CellFreshness.isStaleDaily(it, asOf) }
            } else {
                { CellFreshness.isStale(it, nowMs) }
            }
            val (scale, json) = withContext(io) {
                val scale = property?.let { p -> CellScale.of(p.id, rows.mapNotNull { it.mean(p.id) }) }
                val json = if (current.available) {
                    CellGeoJson.build(h3, rows, property?.id, scale, stale)
                } else {
                    DiscoverUiState.EMPTY_GEOJSON
                }
                scale to json
            }
            _state.update {
                // A newer load may have replaced the rows meanwhile. Keep its result.
                if (it.rows !== rows) return@update it
                it.copy(
                    property = property,
                    chips = PropertyRanking.chips(it.ranked, property?.id),
                    scale = scale,
                    geoJson = json,
                )
            }
        }
    }

    private companion object {
        const val REST_MS = 400L

        /** The waits before the second and third try of a failed window read. */
        val RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L)

        /** [window] when [windows] offers it, else the 24 hours. */
        fun offered(window: FeedWindow, windows: List<FeedWindow>): FeedWindow = if (window in windows) window else FeedWindow.DAY

        /** The partition's local day. Null when the date does not parse. */
        fun partitionDate(p: FeedPartition): LocalDate? = try {
            LocalDate.parse(p.date)
        } catch (e: IllegalArgumentException) {
            null
        }

        /** [runCatching] that lets cancellation through, so a cancelled read stops instead of reading on. */
        inline fun <T> catching(block: () -> T): Result<T> = try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }
}

/** [runCatching] for suspend calls in composables. Cancellation still ends the coroutine. */
internal inline fun <T> catchingNonCancel(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
