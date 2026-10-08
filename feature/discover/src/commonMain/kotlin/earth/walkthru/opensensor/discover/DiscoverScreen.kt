package earth.walkthru.opensensor.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.publicfeed.CellRow
import earth.walkthru.aqsync.publicfeed.FeedProperty
import earth.walkthru.aqsync.publicfeed.FeedWindow
import earth.walkthru.aqsync.publicfeed.H3Index
import earth.walkthru.aqsync.publicfeed.SequentialRamp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.spatialk.geojson.Position

private val PEEK_HEIGHT = 148.dp

/**
 * The Discover map, first screen at the Simple level (docs/devices-and-discovery.md, "First screen
 * at the Simple level"). Zero taps shows the map, the metric and window chips, the legend, the
 * "Sample data" pill and the sheet at peek height. Explanations sit behind info buttons.
 *
 * [networkAllowed] is the Settings switch. Off, the basemap is a plain paper background and no
 * request leaves the phone. [infoButton] lets the host reuse its own info affordance.
 */
@Composable
fun DiscoverScreen(
    controller: DiscoverController,
    strings: DiscoverStrings,
    networkAllowed: Boolean,
    onAddSensor: () -> Unit,
    modifier: Modifier = Modifier,
    infoButton: @Composable (InfoText, Modifier) -> Unit = { info, m -> DefaultInfoButton(info, m) },
) {
    val state by controller.state.collectAsState()
    val dark = isSystemInDarkTheme()
    val paper = MaterialTheme.colorScheme.surfaceContainerLow
    val ink = MaterialTheme.colorScheme.onSurface
    val styleJson by produceState<String?>(null, networkAllowed, dark) {
        value = if (networkAllowed) runCatching { basemapStyle(dark) }.getOrNull() ?: paperStyle(SequentialRamp.hex(paperArgb(paper))) else paperStyle(SequentialRamp.hex(paperArgb(paper)))
    }
    val scope = rememberCoroutineScope()
    val sheet = rememberBottomSheetScaffoldState(rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded))
    var showAll by rememberSaveable { mutableStateOf(false) }
    fun expand() = scope.launch { runCatching { sheet.bottomSheetState.expand() } }
    fun peek() = scope.launch { runCatching { sheet.bottomSheetState.partialExpand() } }

    BottomSheetScaffold(
        modifier = modifier,
        scaffoldState = sheet,
        sheetPeekHeight = PEEK_HEIGHT,
        sheetContent = {
            SheetContent(
                state = state,
                strings = strings,
                showAll = showAll,
                infoButton = infoButton,
                onAddSensor = { controller.dismissFirstRun(); onAddSensor() },
                onExplore = { controller.dismissFirstRun() },
                onPickProperty = { id -> controller.selectProperty(id); showAll = false; peek() },
                onPickCell = { hex -> controller.selectCell(hex) },
                now = nowEpochMillis(),
            )
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            val start = state.start
            val style = styleJson
            if (start != null && style != null) {
                val selectedHex = state.selected?.let { H3Index.toHex(it.h3) }
                val mapState = rememberDiscoverMapState(start, style, state.geoJson, selectedHex, ink) { hex ->
                    controller.selectCell(hex)
                    controller.dismissFirstRun()
                    showAll = false
                    if (hex != null) expand() else peek()
                }
                var attributionOpen by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    delay(5_000)
                    attributionOpen = false
                }
                val shownAt = remember { nowEpochMillis() }
                DiscoverMap(
                    state = mapState,
                    start = start,
                    viewportInsets = PaddingValues(top = 120.dp, bottom = PEEK_HEIGHT),
                    onCamera = controller::onCamera,
                    onMapClick = {
                        controller.selectCell(null)
                        showAll = false
                        peek()
                    },
                    // The fit to the start region also moves the camera, so a move only counts
                    // as the first pan once the map has settled.
                    onFirstMove = { if (nowEpochMillis() - shownAt > 2_000) attributionOpen = false },
                )
                TopControls(
                    state = state,
                    strings = strings,
                    infoButton = infoButton,
                    onAddSensor = { controller.dismissFirstRun(); onAddSensor() },
                    onPlace = { place ->
                        scope.launch {
                            runCatching {
                                mapState.animateCamera(CameraUpdate(target = Position(longitude = place.lng, latitude = place.lat), zoom = 9.0))
                            }
                        }
                    },
                    onProperty = controller::selectProperty,
                    onMore = { showAll = true; expand() },
                    onWindow = controller::cycleWindow,
                    modifier = Modifier.align(Alignment.TopStart),
                )
                Row(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = PEEK_HEIGHT + 8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    val property = state.property
                    val scale = state.scale
                    if (property != null && scale != null) {
                        Legend(property, scale.low, scale.high, strings, infoButton)
                    }
                    Spacer(Modifier.weight(1f))
                    if (networkAllowed) Attribution(strings, attributionOpen, infoButton)
                }
            }
        }
    }
}

private fun paperArgb(color: Color): Int {
    fun c(v: Float) = (v * 255).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (c(color.red) shl 16) or (c(color.green) shl 8) or c(color.blue)
}

@Composable
private fun TopControls(
    state: DiscoverUiState,
    strings: DiscoverStrings,
    infoButton: @Composable (InfoText, Modifier) -> Unit,
    onAddSensor: () -> Unit,
    onPlace: (Place) -> Unit,
    onProperty: (String) -> Unit,
    onMore: () -> Unit,
    onWindow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val results = remember(query) { Places.search(query) }
    Column(modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(strings.searchPlaces) },
                leadingIcon = { Icon(DiscoverIcons.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(DiscoverIcons.Close, contentDescription = strings.clearSearch) }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = onAddSensor, modifier = Modifier.size(52.dp)) {
                Icon(DiscoverIcons.Add, contentDescription = strings.addSensor)
            }
        }
        if (query.isNotBlank()) {
            Surface(
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
            ) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    if (results.isEmpty()) {
                        Text(strings.noPlaces, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    results.forEach { place ->
                        Column(
                            Modifier.fillMaxWidth().clickable { query = ""; focus.clearFocus(); onPlace(place) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Text(place.name, style = MaterialTheme.typography.bodyLarge)
                            Text(place.country, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            return@Column
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val chipColors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            state.chips.forEach { p ->
                FilterChip(
                    selected = p.id == state.property?.id,
                    onClick = { onProperty(p.id) },
                    label = { Text(p.label) },
                    colors = chipColors,
                )
            }
            if (state.dictionary.size > state.chips.size) {
                FilterChip(selected = false, onClick = onMore, label = { Text(strings.more) }, colors = chipColors)
            }
            AssistChip(
                onClick = onWindow,
                label = {
                    Text(
                        when (state.window) {
                            FeedWindow.LAST_HOUR -> strings.windowLastHour
                            FeedWindow.DAY -> strings.window24Hours
                            FeedWindow.WEEK -> strings.window7Days
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            )
        }
        if (state.isSample && state.available) {
            Row(Modifier.align(Alignment.End).padding(end = 12.dp)) { SamplePill(strings, infoButton) }
        }
    }
}

@Composable
private fun SamplePill(strings: DiscoverStrings, infoButton: @Composable (InfoText, Modifier) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer, shape = CircleShape) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp)) {
            Text(strings.sampleData, style = MaterialTheme.typography.labelLarge)
            infoButton(strings.sampleInfo, Modifier.size(40.dp))
        }
    }
}

@Composable
private fun Legend(
    property: FeedProperty,
    low: Double,
    high: Double,
    strings: DiscoverStrings,
    infoButton: @Composable (InfoText, Modifier) -> Unit,
) {
    Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 2.dp, shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
            Column(Modifier.widthIn(min = 140.dp, max = 180.dp).padding(vertical = 8.dp)) {
                Text("${property.label}  ${property.unit}", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                        .background(Brush.horizontalGradient(SequentialRamp.stops.map { Color(it) })),
                )
                Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text(formatValue(low, property), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(formatValue(high, property), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            infoButton(strings.scaleInfo, Modifier.size(40.dp))
        }
    }
}

@Composable
private fun Attribution(strings: DiscoverStrings, open: Boolean, infoButton: @Composable (InfoText, Modifier) -> Unit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = if (open) 10.dp else 0.dp)) {
            if (open) Text(strings.osm, style = MaterialTheme.typography.labelSmall)
            infoButton(strings.mapInfo, Modifier.size(36.dp))
        }
    }
}

@Composable
private fun SheetContent(
    state: DiscoverUiState,
    strings: DiscoverStrings,
    showAll: Boolean,
    infoButton: @Composable (InfoText, Modifier) -> Unit,
    onAddSensor: () -> Unit,
    onExplore: () -> Unit,
    onPickProperty: (String) -> Unit,
    onPickCell: (String) -> Unit,
    now: Long,
) {
    // Sample data is a snapshot, so its freshness is read against its own newest hour.
    val reference = if (state.isSample) (state.newestUpdateEpochMs ?: now) else now
    Column(Modifier.fillMaxWidth().heightIn(min = PEEK_HEIGHT - 24.dp).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        when {
            !state.available -> Text(strings.unavailable, style = MaterialTheme.typography.bodyLarge)
            showAll -> AllProperties(state, strings, onPickProperty)
            state.selected != null -> CellDetail(state.selected, state, strings, infoButton, reference)
            state.firstRun -> FirstRunCard(strings, onAddSensor, onExplore)
            else -> InView(state, strings, reference, onPickCell)
        }
    }
}

@Composable
private fun FirstRunCard(strings: DiscoverStrings, onAddSensor: () -> Unit, onExplore: () -> Unit) {
    Text(strings.firstRunLine, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onAddSensor) { Text(strings.addSensor) }
        OutlinedButton(onClick = onExplore) { Text(strings.explore) }
    }
}

private fun freshness(strings: DiscoverStrings, updated: Long?, reference: Long): String? {
    updated ?: return null
    val hours = ((reference - updated) / 3_600_000L).toInt()
    return if (hours < 1) strings.updatedRecently else strings.updatedHoursAgo(hours)
}

@Composable
private fun InView(state: DiscoverUiState, strings: DiscoverStrings, reference: Long, onPickCell: (String) -> Unit) {
    if (state.loading && state.rows.isEmpty()) {
        Text(strings.loading, style = MaterialTheme.typography.titleMedium)
        return
    }
    if (state.rows.isEmpty()) {
        Text(strings.noAreas, style = MaterialTheme.typography.titleMedium)
        return
    }
    Text(strings.areasInView(state.rows.size), style = MaterialTheme.typography.titleMedium)
    freshness(strings, state.newestUpdateEpochMs, reference)?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val property = state.property ?: return
    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    val sorted = state.rows.filter { it.mean(property.id) != null }.sortedByDescending { it.mean(property.id) }
    LazyColumn(Modifier.heightIn(max = 360.dp)) {
        items(sorted, key = { it.h3 }) { row ->
            Row(
                Modifier.fillMaxWidth().clickable { onPickCell(H3Index.toHex(row.h3)) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val value = row.mean(property.id)!!
                Box(Modifier.size(14.dp).clip(CircleShape).background(Color(state.scale?.color(value) ?: SequentialRamp.stops.first())))
                Spacer(Modifier.width(12.dp))
                Text("${formatValue(value, property)} ${property.unit}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(strings.sensors(row.devices), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CellDetail(
    row: CellRow,
    state: DiscoverUiState,
    strings: DiscoverStrings,
    infoButton: @Composable (InfoText, Modifier) -> Unit,
    reference: Long,
) {
    if (state.isSample) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(strings.sampleData, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.weight(1f))
            infoButton(strings.sampleInfo, Modifier.size(40.dp))
        }
    }
    val property = state.property
    val headline = property?.let { row.mean(it.id) }
    if (property != null && headline != null) {
        Text(
            "${formatValue(headline, property)} ${property.unit}",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { contentDescription = "${property.label} ${formatValue(headline, property)} ${property.unit}" },
        )
    } else {
        Text(strings.notMeasured, style = MaterialTheme.typography.titleMedium)
    }
    val stale = reference - row.updatedEpochMs >= earth.walkthru.aqsync.publicfeed.CellFreshness.STALE_AFTER_MS
    val parts = listOfNotNull(strings.sensors(row.devices), if (stale) strings.stale else freshness(strings, row.updatedEpochMs, reference))
    Text(parts.joinToString("  "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.weight(1.4f))
        listOf(strings.avg, strings.min, strings.max).forEach {
            Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    state.dictionary.filter { row.stats[it.id]?.mean != null }.forEach { p ->
        val stat = row.stats.getValue(p.id)
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1.4f)) {
                Text(p.label, style = MaterialTheme.typography.bodyLarge)
                Text(p.unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            listOf(stat.mean, stat.min, stat.max).forEach { v ->
                Text(v?.let { formatValue(it, p) } ?: "", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun AllProperties(state: DiscoverUiState, strings: DiscoverStrings, onPick: (String) -> Unit) {
    Text(strings.allMeasurements, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    // Grouped by the category the dictionary gives each property (Air, Weather, and so on).
    state.dictionary.groupBy { it.category }.forEach { (category, properties) ->
        Text(category, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        properties.forEach { p ->
            val count = state.rows.count { it.mean(p.id) != null }
            Row(
                Modifier.fillMaxWidth().clickable { onPick(p.id) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(p.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), color = if (count == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                Text(strings.areasInView(count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
