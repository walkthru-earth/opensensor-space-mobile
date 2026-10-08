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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import earth.walkthru.aqsync.publicfeed.CellRow
import earth.walkthru.aqsync.publicfeed.FeedProperty
import earth.walkthru.aqsync.publicfeed.H3Index
import earth.walkthru.aqsync.publicfeed.SequentialRamp
import earth.walkthru.aqsync.units.UnitFormatter
import earth.walkthru.aqsync.units.UnitSystem
import earth.walkthru.opensensor.designsystem.FloatingControl
import earth.walkthru.opensensor.designsystem.FloatingSurface
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
 * request leaves the phone. [infoButton] lets the host reuse its own info affordance. [units] is
 * the host's resolved Settings choice (METRIC or IMPERIAL). It changes labels only, never the scale.
 */
@Composable
fun DiscoverScreen(
    controller: DiscoverController,
    strings: DiscoverStrings,
    networkAllowed: Boolean,
    onAddSensor: () -> Unit,
    modifier: Modifier = Modifier,
    infoButton: @Composable (InfoText, Modifier) -> Unit = { info, m -> DefaultInfoButton(info, m) },
    units: UnitSystem = UnitSystem.METRIC,
) {
    val formatter = remember(units) { UnitFormatter(units) }
    CompositionLocalProvider(LocalDiscoverUnits provides formatter) {
        DiscoverContent(controller, strings, networkAllowed, onAddSensor, modifier, infoButton)
    }
}

@Composable
private fun DiscoverContent(
    controller: DiscoverController,
    strings: DiscoverStrings,
    networkAllowed: Boolean,
    onAddSensor: () -> Unit,
    modifier: Modifier,
    infoButton: @Composable (InfoText, Modifier) -> Unit,
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
                onShowProperty = controller::selectProperty,
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
                val nearMe = rememberNearMe()
                val mapState = rememberDiscoverMapState(start, style, state.geoJson, selectedHex, ink, nearMe.location) { hex ->
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
                    modifier = Modifier.align(Alignment.TopStart),
                )
                WindowSlider(
                    window = state.window,
                    strings = strings,
                    onWindow = controller::selectWindow,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = FloatingControl.Margin),
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
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        NearMeButton(nearMe, mapState, strings.nearMe, Modifier.padding(end = 8.dp))
                        if (networkAllowed) Attribution(strings, attributionOpen, infoButton)
                    }
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
                shape = FloatingControl.Shape,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = onAddSensor,
                modifier = Modifier.size(52.dp),
                shape = FloatingControl.Shape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = FloatingControl.Selected, contentColor = FloatingControl.OnSelected),
            ) {
                Icon(DiscoverIcons.Add, contentDescription = strings.addSensor)
            }
        }
        if (query.isNotBlank()) {
            FloatingSurface(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth()) {
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
            val chipColors = FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                selectedContainerColor = FloatingControl.Selected,
                selectedLabelColor = FloatingControl.OnSelected,
            )
            state.chips.forEach { p ->
                FilterChip(
                    selected = p.id == state.property?.id,
                    onClick = { onProperty(p.id) },
                    label = { Text(p.label) },
                    colors = chipColors,
                    shape = FloatingControl.SmallShape,
                )
            }
            if (state.dictionary.size > state.chips.size) {
                FilterChip(selected = false, onClick = onMore, label = { Text(strings.more) }, colors = chipColors, shape = FloatingControl.SmallShape)
            }
        }
        if (state.isSample && state.available) {
            Row(Modifier.align(Alignment.End).padding(end = 12.dp)) { SamplePill(strings, infoButton) }
        }
    }
}

@Composable
private fun SamplePill(strings: DiscoverStrings, infoButton: @Composable (InfoText, Modifier) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer, shape = FloatingControl.SmallShape) {
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
    val units = LocalDiscoverUnits.current
    FloatingSurface {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
            Column(Modifier.widthIn(min = 140.dp, max = 180.dp).padding(vertical = 8.dp)) {
                Text("${property.label}  ${unitLabel(property, units)}", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                        .background(Brush.horizontalGradient(SequentialRamp.stops.map { Color(it) })),
                )
                Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text(formatValue(low, property, units), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(formatValue(high, property, units), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            infoButton(strings.scaleInfo, Modifier.size(40.dp))
        }
    }
}

@Composable
private fun Attribution(strings: DiscoverStrings, open: Boolean, infoButton: @Composable (InfoText, Modifier) -> Unit) {
    FloatingSurface(shape = FloatingControl.SmallShape) {
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
    onShowProperty: (String) -> Unit,
    onPickCell: (String) -> Unit,
    now: Long,
) {
    // Sample data is a snapshot, so its freshness is read against its own newest hour.
    val reference = if (state.isSample) (state.newestUpdateEpochMs ?: now) else now
    Column(Modifier.fillMaxWidth().heightIn(min = PEEK_HEIGHT - 24.dp).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        when {
            !state.available -> Text(strings.unavailable, style = MaterialTheme.typography.bodyLarge)
            showAll -> AllProperties(state, strings, onPickProperty)
            state.selected != null -> CellDetail(state.selected, state, strings, infoButton, reference, onShowProperty)
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
    val units = LocalDiscoverUnits.current
    val values = state.rows.mapNotNull { it.mean(property.id) }
    if (values.size >= 2) {
        Spacer(Modifier.height(16.dp))
        ChartTitle(property, strings, state)
        Spacer(Modifier.height(8.dp))
        SpreadChart(values, property, state.scale, strings)
    }
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
                Text("${formatValue(value, property, units)} ${unitLabel(property, units)}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(strings.sensors(row.devices), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Label, unit and window over a chart, with an optional [trailing] note at the end. */
@Composable
private fun ChartTitle(property: FeedProperty, strings: DiscoverStrings, state: DiscoverUiState, trailing: String? = null) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text("${property.label}  ${unitLabel(property, LocalDiscoverUnits.current)}", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.width(8.dp))
        Text(
            "${strings.avg}  ${windowLabel(state.window, strings)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CellDetail(
    row: CellRow,
    state: DiscoverUiState,
    strings: DiscoverStrings,
    infoButton: @Composable (InfoText, Modifier) -> Unit,
    reference: Long,
    onShowProperty: (String) -> Unit,
) {
    // Peek shows the headline, the device count and the freshness. Charts sit below.
    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (state.isSample) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(strings.sampleData, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.weight(1f))
                infoButton(strings.sampleInfo, Modifier.size(40.dp))
            }
        }
        val property = state.property
        val units = LocalDiscoverUnits.current
        val stat = property?.let { row.stats[it.id] }
        val headline = stat?.mean
        if (property != null && headline != null) {
            val unit = unitLabel(property, units)
            Row(Modifier.clearAndSetSemantics { contentDescription = "${property.label} ${formatValue(headline, property, units)} $unit" }) {
                Text(formatValue(headline, property, units), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.alignByBaseline())
                Spacer(Modifier.width(6.dp))
                Text(unit, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alignByBaseline())
                Spacer(Modifier.width(10.dp))
                Text(property.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alignByBaseline())
            }
        } else {
            Text(strings.notMeasured, style = MaterialTheme.typography.titleMedium)
        }
        val stale = reference - row.updatedEpochMs >= earth.walkthru.aqsync.publicfeed.CellFreshness.STALE_AFTER_MS
        val parts = listOfNotNull(strings.sensors(row.devices), if (stale) strings.stale else freshness(strings, row.updatedEpochMs, reference))
        Text(parts.joinToString("  "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (property != null && headline != null) {
            val values = state.rows.mapNotNull { it.mean(property.id) }
            Spacer(Modifier.height(16.dp))
            val min = stat.min
            val max = stat.max
            val span = if (min != null && max != null) "${strings.min} ${formatValue(min, property, units)}  ${strings.max} ${formatValue(max, property, units)}" else null
            ChartTitle(property, strings, state, span)
            Spacer(Modifier.height(8.dp))
            SpreadChart(values, property, state.scale, strings, here = headline, hereMin = min, hereMax = max)
            percentBelow(values, headline)?.let {
                Spacer(Modifier.height(4.dp))
                Text(strings.higherThan(it), style = MaterialTheme.typography.bodyMedium)
            }
        }
        val others = metricRanges(row, state.rows, state.dictionary).filter { it.property.id != property?.id || headline == null }
        if (others.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))
            // Group by the dictionary category once the list gets long.
            val groups = if (others.size > 5) others.groupBy { it.property.category } else mapOf("" to others)
            groups.forEach { (category, ranges) ->
                if (category.isNotEmpty()) {
                    Text(category, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
                }
                ranges.forEach { r -> RangeRow(r, strings) { onShowProperty(r.property.id) } }
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
