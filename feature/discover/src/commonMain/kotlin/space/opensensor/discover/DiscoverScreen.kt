package space.opensensor.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.spatialk.geojson.Position
import space.opensensor.airquality.HealthScale
import space.opensensor.designsystem.FitText
import space.opensensor.designsystem.FloatingControl
import space.opensensor.designsystem.FloatingSurface
import space.opensensor.designsystem.MoodLegend
import space.opensensor.designsystem.MoodStrip
import space.opensensor.designsystem.Peacock
import space.opensensor.designsystem.PeacockExpression
import space.opensensor.designsystem.PeacockIcon
import space.opensensor.designsystem.listSeparator
import space.opensensor.designsystem.moodLine
import space.opensensor.designsystem.moodWord
import space.opensensor.designsystem.notAvailableText
import space.opensensor.designsystem.peacockExpressionFor
import space.opensensor.designsystem.spokenList
import space.opensensor.publicfeed.CellRow
import space.opensensor.publicfeed.CellScale
import space.opensensor.publicfeed.FeedProperty
import space.opensensor.publicfeed.FeedWindow
import space.opensensor.publicfeed.H3Index
import space.opensensor.publicfeed.PublicSummary
import space.opensensor.publicfeed.SequentialRamp
import space.opensensor.publicfeed.StepScale
import space.opensensor.settings.DetailLevel
import space.opensensor.settings.DiscoverSource
import space.opensensor.units.UnitFormatter
import space.opensensor.units.UnitSystem

// Tall enough for the chart, so it stays in sight while the sheet is down and the map is explored.
private val PEEK_HEIGHT = 200.dp

/**
 * The Discover map, first screen at the Simple level (docs/devices-and-discovery.md, "First screen
 * at the Simple level"). Zero taps shows the map, the metric and window chips, the legend, the
 * "Sample data" pill and the sheet at peek height. Explanations sit behind info buttons.
 *
 * [networkAllowed] is the Settings switch. Off, the basemap is a plain paper background and no
 * request leaves the phone. The copy comes from this module's Compose resources. [infoButton] lets the host reuse its own info affordance. [units] is
 * the host's resolved Settings choice (METRIC or IMPERIAL). It changes labels only, never the scale.
 * [level] is the host's Settings detail level. Simple speaks in moods, Detailed and Developer show the WHO scale.
 * [savedSource] is the host's saved Discover source. Real reads OpenAQ only while [networkAllowed].
 */
@Composable
fun DiscoverScreen(
    controller: DiscoverController,
    networkAllowed: Boolean,
    level: DetailLevel,
    onAddSensor: () -> Unit,
    modifier: Modifier = Modifier,
    infoButton: @Composable (InfoText, Modifier) -> Unit = { info, m -> DefaultInfoButton(info, m) },
    units: UnitSystem = UnitSystem.METRIC,
    savedSource: DiscoverSource = DiscoverSource.SAMPLE,
) {
    // The feed on screen follows the saved source and the public sensors switch, on every change.
    LaunchedEffect(controller, savedSource, networkAllowed) { controller.onSourceSettings(savedSource, networkAllowed) }
    // One catalogue for both apps (composeResources/values/strings.xml).
    val strings = discoverStrings()
    val formatter = remember(units) { UnitFormatter(units) }
    CompositionLocalProvider(LocalDiscoverUnits provides formatter) {
        DiscoverContent(controller, strings, networkAllowed, level == DetailLevel.SIMPLE, onAddSensor, modifier, infoButton)
    }
}

@Composable
private fun DiscoverContent(
    controller: DiscoverController,
    strings: DiscoverStrings,
    networkAllowed: Boolean,
    simple: Boolean,
    onAddSensor: () -> Unit,
    modifier: Modifier,
    infoButton: @Composable (InfoText, Modifier) -> Unit,
) {
    val state by controller.state.collectAsState()
    val dark = isSystemInDarkTheme()
    val paper = MaterialTheme.colorScheme.surfaceContainerLow
    val ink = MaterialTheme.colorScheme.onSurface
    val styleJson by produceState<String?>(null, networkAllowed, dark) {
        value = if (networkAllowed) catchingNonCancel { basemapStyle(dark) }.getOrNull() ?: paperStyle(SequentialRamp.hex(paperArgb(paper))) else paperStyle(SequentialRamp.hex(paperArgb(paper)))
    }
    val scope = rememberCoroutineScope()
    val sheet = rememberBottomSheetScaffoldState(rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded))
    var showAll by rememberSaveable { mutableStateOf(false) }
    // The chart bar being touched or pinned. It filters the map and the list (a cross filter).
    var highlight by remember { mutableStateOf<ValueBand?>(null) }
    // Bumped to let go of a pinned bar, when the map is tapped outside any area.
    var clearSignal by remember { mutableStateOf(0) }
    fun expand() = scope.launch { catchingNonCancel { sheet.bottomSheetState.expand() } }
    fun peek() = scope.launch { catchingNonCancel { sheet.bottomSheetState.partialExpand() } }

    // Back, the Android predictive back or the iOS edge swipe, first returns an open sheet to peek.
    // Navigation Event runs the newest handler first, so this wins over the shell's NavDisplay.
    // A sheet dragged up or showing the full list also counts as open, even with nothing selected.
    val sheetOpen = sheet.bottomSheetState.targetValue == SheetValue.Expanded
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = state.selected != null || showAll || sheetOpen,
        onBackCompleted = {
            controller.selectCell(null)
            showAll = false
            peek()
        },
    )

    BottomSheetScaffold(
        modifier = modifier,
        scaffoldState = sheet,
        sheetPeekHeight = PEEK_HEIGHT,
        sheetContent = {
            SheetContent(
                state = state,
                strings = strings,
                networkAllowed = networkAllowed,
                simple = simple,
                showAll = showAll,
                infoButton = infoButton,
                onAddSensor = { controller.dismissFirstRun(); onAddSensor() },
                onExplore = { controller.dismissFirstRun() },
                onPickProperty = { id -> controller.selectProperty(id); showAll = false; peek() },
                onShowProperty = controller::selectProperty,
                onRetry = controller::retryWindows,
                onRetryLoad = controller::retry,
                onPickCell = { hex -> controller.selectCell(hex) },
                highlight = highlight,
                clearSignal = clearSignal,
                onHighlight = { highlight = it },
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
                // A rotation rebuilds the map. It resumes at the last camera, not the region fit.
                val mapStart = remember(start) { controller.resumeStart() ?: start }
                val mapState = rememberDiscoverMapState(mapStart, style, state.geoJson, selectedHex, ink, nearMe.location, highlight) { hex ->
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
                // Screen readers name the map and the measurement it colours. Its gestures stay as
                // they are, the sheet's list reads each area.
                val mapLabel = spokenList(listOfNotNull(strings.mapLabel, state.property?.shownLabel()), listSeparator())
                DiscoverMap(
                    modifier = Modifier.semantics { contentDescription = mapLabel },
                    state = mapState,
                    start = mapStart,
                    viewportInsets = PaddingValues(top = 120.dp, bottom = PEEK_HEIGHT),
                    onCamera = controller::onCamera,
                    onMapClick = {
                        controller.selectCell(null)
                        clearSignal++
                        highlight = null
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
                            catchingNonCancel {
                                mapState.animateCamera(CameraUpdate(center = Position(longitude = place.lng, latitude = place.lat), zoom = 9.0))
                            }
                        }
                    },
                    onProperty = controller::selectProperty,
                    onSource = controller::chooseSource,
                    onMore = { showAll = true; expand() },
                    modifier = Modifier.align(Alignment.TopStart),
                )
                WindowSlider(
                    window = state.window,
                    windows = state.windows,
                    strings = strings,
                    onWindow = controller::selectWindow,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = FloatingControl.Margin),
                    daily = state.daily,
                )
                Row(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = PEEK_HEIGHT + 8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    val property = state.property
                    val scale = state.scale
                    if (property != null && scale != null) {
                        // Simple shows mood words for the WHO scale. Any other property keeps its steps or gradient.
                        if (simple && scale.who != null) SimpleLegend(property, strings, infoButton) else Legend(property, scale, state.window, strings, infoButton, state.daily)
                    }
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        NearMeButton(nearMe, mapState, controller::nearMeTarget, strings.nearMe, strings.nearMeAccess, Modifier.padding(end = 8.dp))
                        if (networkAllowed) Attribution(strings, attributionOpen, real = !state.isSample, infoButton)
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
    onSource: (DiscoverSource) -> Unit,
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
                        DiscoverStateMessage(strings.noPlaces, PeacockExpression.CONFUSED, Modifier.padding(16.dp))
                    }
                    results.forEach { place ->
                        Column(
                            Modifier.fillMaxWidth().clickable(role = Role.Button) { query = ""; focus.clearFocus(); onPlace(place) }.padding(horizontal = 16.dp, vertical = 10.dp),
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
                    label = { FitText(p.shownLabel()) },
                    colors = chipColors,
                    shape = FloatingControl.SmallShape,
                )
            }
            if (state.dictionary.size > state.chips.size) {
                FilterChip(selected = false, onClick = onMore, label = { Text(strings.more) }, colors = chipColors, shape = FloatingControl.SmallShape)
            }
        }
        if (state.available) {
            Row(Modifier.align(Alignment.End).padding(end = FloatingControl.Margin)) { SourceToggle(state, strings, infoButton, onSource) }
        }
    }
}

/**
 * The Sample and Real toggle. Real reads OpenAQ and is labelled with the day its data runs to.
 * While public sensors is off it is disabled and shows Sample. Only the chosen half takes the
 * brand blue. The info button explains the feed on screen.
 */
@Composable
private fun SourceToggle(
    state: DiscoverUiState,
    strings: DiscoverStrings,
    infoButton: @Composable (InfoText, Modifier) -> Unit,
    onSource: (DiscoverSource) -> Unit,
) {
    val real = state.source == DiscoverSource.OPENAQ
    val enabled = state.sourceEnabled
    val date = state.dataDate
    val realLabel = if (real && date != null) strings.realDataTo(date) else strings.sourceReal
    FloatingSurface(shape = FloatingControl.SmallShape) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = FloatingControl.Inset)) {
            // On a narrow phone the longer Real label ends in an ellipsis, the info button keeps its place.
            Row(Modifier.weight(1f, fill = false).selectableGroup().semantics { contentDescription = strings.sourceLabel }) {
                SourceSegment(strings.sourceSample, selected = !real, enabled = enabled, disabledNote = null) { onSource(DiscoverSource.SAMPLE) }
                SourceSegment(realLabel, selected = real, enabled = enabled, disabledNote = strings.sourceOff, Modifier.weight(1f, fill = false)) { onSource(DiscoverSource.OPENAQ) }
            }
            // A sighted user sees Real greyed out, so the info sheet says why (public sensors are off).
            val info = when {
                real -> strings.realInfo
                enabled -> strings.sampleInfo
                else -> strings.sampleInfo.copy(body = strings.sampleInfo.body + strings.sourceOff)
            }
            infoButton(info, Modifier.minimumInteractiveComponentSize().size(40.dp))
        }
    }
}

@Composable
private fun SourceSegment(label: String, selected: Boolean, enabled: Boolean, disabledNote: String?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = FloatingControl.BadgeShape
    val color = when {
        selected -> FloatingControl.OnSelected
        enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    // The touch target is at least 48 dp. The drawn pill stays 36 dp inside it.
    Box(
        modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { if (!enabled && disabledNote != null) stateDescription = disabledNote }
            .minimumInteractiveComponentSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.heightIn(min = 36.dp)
                .clip(shape)
                .background(if (selected) FloatingControl.Selected else Color.Transparent, shape)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            FitText(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = color)
        }
    }
}

/**
 * The map legend. For a property with a published scale, its fixed step swatches with their edges,
 * the guideline marked and a caption. The WHO scale names the averaging. For the rest, the viridis
 * gradient with the 5th and 95th percentile in view and a note that no health scale is published.
 */
@Composable
private fun Legend(
    property: FeedProperty,
    scale: CellScale,
    window: FeedWindow,
    strings: DiscoverStrings,
    infoButton: @Composable (InfoText, Modifier) -> Unit,
    daily: Boolean,
) {
    val units = LocalDiscoverUnits.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val unit = unitLabel(property, units)
    FloatingSurface {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
            when (scale) {
                is CellScale.Steps -> {
                    val steps = scale.scale
                    val who = steps.who != null
                    val caption = when {
                        !who -> strings.stepsTitle
                        window == FeedWindow.DAY -> strings.whoCaptionDay
                        else -> strings.whoCaptionCompared
                    }
                    val spoken = buildString {
                        append("${property.shownLabel()} $unit. $caption. ")
                        for (i in 0 until steps.size) {
                            val upTo = steps.rangeOf(i).second?.let { edgeLabel(steps, it, property, units) }
                            append(strings.whoStepSpoken(strings.stepName(steps, i), upTo, unit)).append(". ")
                        }
                    }
                    Column(Modifier.width(212.dp).padding(vertical = 8.dp).clearAndSetSemantics { contentDescription = spoken }) {
                        FitText("${property.shownLabel()}  $unit", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(2.dp))
                        StepBar(steps, property, strings)
                        Spacer(Modifier.height(2.dp))
                        Text(caption, style = MaterialTheme.typography.labelSmall, color = muted)
                    }
                    val info = when {
                        who && daily -> strings.whoInfoReal
                        who -> strings.whoInfo
                        daily -> strings.stepsInfoReal
                        else -> strings.stepsInfo
                    }
                    infoButton(info, Modifier.minimumInteractiveComponentSize().size(40.dp))
                }
                is CellScale.Relative -> {
                    val low = scale.percentile.low
                    val high = scale.percentile.high
                    // Read as one item, label and unit, low, high and the note. The gradient is decorative.
                    Column(Modifier.widthIn(min = 140.dp, max = 180.dp).padding(vertical = 8.dp).semantics(mergeDescendants = true) {}) {
                        FitText("${property.shownLabel()}  $unit", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(4.dp))
                        Box(
                            Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                                .background(Brush.horizontalGradient(SequentialRamp.stops.map { Color(it) })),
                        )
                        Spacer(Modifier.height(2.dp))
                        Row(Modifier.fillMaxWidth()) {
                            Text(formatValue(low, property, units), style = MaterialTheme.typography.labelSmall, color = muted)
                            Spacer(Modifier.weight(1f))
                            Text(formatValue(high, property, units), style = MaterialTheme.typography.labelSmall, color = muted)
                        }
                        Text(strings.noHealthScale, style = MaterialTheme.typography.labelSmall, color = muted)
                    }
                    infoButton(if (daily) strings.scaleInfoReal else strings.scaleInfo, Modifier.minimumInteractiveComponentSize().size(40.dp))
                }
            }
        }
    }
}

/** The Simple legend. The six moods with their colours, the property as title, no numbers. */
@Composable
private fun SimpleLegend(property: FeedProperty, strings: DiscoverStrings, infoButton: @Composable (InfoText, Modifier) -> Unit) {
    FloatingSurface {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
            MoodLegend(Modifier.width(212.dp).padding(vertical = 8.dp), title = property.shownLabel())
            infoButton(strings.moodInfo, Modifier.minimumInteractiveComponentSize().size(40.dp))
        }
    }
}

/** A small swatch in step [i]'s colour and its name, so the step never rests on colour alone. */
@Composable
private fun StepLabel(scale: StepScale, i: Int, strings: DiscoverStrings, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(Color(scale.colors[i]))
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f), RoundedCornerShape(3.dp)),
        )
        Spacer(Modifier.width(6.dp))
        Text(strings.stepName(scale, i), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Attribution(strings: DiscoverStrings, open: Boolean, real: Boolean, infoButton: @Composable (InfoText, Modifier) -> Unit) {
    val uri = LocalUriHandler.current
    FloatingSurface(shape = FloatingControl.SmallShape) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = if (open || real) 10.dp else 0.dp)) {
            if (open) Text(strings.osm, style = MaterialTheme.typography.labelSmall)
            if (open && real) Spacer(Modifier.width(8.dp))
            // Real data stays credited while it is on screen, and opens the OpenAQ licenses.
            if (real) {
                Text(
                    strings.dataOpenAq,
                    style = MaterialTheme.typography.labelSmall,
                    textDecoration = TextDecoration.Underline,
                    // A 48 dp touch target around the small label.
                    modifier = Modifier.clickable(role = Role.Button) { runCatching { uri.openUri(OPENAQ_LICENSES) } }
                        .minimumInteractiveComponentSize()
                        .wrapContentSize(),
                )
            }
            infoButton(strings.mapInfo, Modifier.minimumInteractiveComponentSize().size(36.dp))
        }
    }
}

/** Where the Real attribution leads, the licenses of the OpenAQ data. */
private const val OPENAQ_LICENSES = "https://docs.openaq.org/resources/licenses"

@Composable
private fun SheetContent(
    state: DiscoverUiState,
    strings: DiscoverStrings,
    networkAllowed: Boolean,
    simple: Boolean,
    showAll: Boolean,
    infoButton: @Composable (InfoText, Modifier) -> Unit,
    onAddSensor: () -> Unit,
    onExplore: () -> Unit,
    onPickProperty: (String) -> Unit,
    onShowProperty: (String) -> Unit,
    onRetry: () -> Unit,
    onRetryLoad: () -> Unit,
    onPickCell: (String) -> Unit,
    highlight: ValueBand?,
    clearSignal: Int,
    onHighlight: (ValueBand?) -> Unit,
    now: Long,
) {
    // Sample data is a snapshot, so its freshness is read against its own newest hour.
    val reference = if (state.isSample) (state.newestUpdateEpochMs ?: now) else now
    Column(Modifier.fillMaxWidth().heightIn(min = PEEK_HEIGHT - 24.dp).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        when (state.sheetBody(showAll, simple)) {
            SheetBody.UNAVAILABLE -> DiscoverStateMessage(strings.unavailable, PeacockExpression.CONCERNED)
            SheetBody.ALL_PROPERTIES -> AllProperties(state, strings, onPickProperty)
            SheetBody.SIMPLE_CELL -> SimpleCellDetail(state, strings, infoButton, reference, onShowProperty, onRetry)
            SheetBody.CELL -> CellDetail(state.selected!!, state, strings, infoButton, reference, clearSignal, onHighlight, onShowProperty)
            SheetBody.LOAD_FAILED -> LoadFailed(strings, onRetryLoad)
            SheetBody.FIRST_RUN -> FirstRunCard(strings, onAddSensor, onExplore)
            SheetBody.IN_VIEW -> InView(state, strings, networkAllowed, simple, reference, highlight, clearSignal, onHighlight, onPickCell)
        }
    }
}

internal enum class SheetBody { UNAVAILABLE, ALL_PROPERTIES, SIMPLE_CELL, CELL, LOAD_FAILED, FIRST_RUN, IN_VIEW }

/**
 * What the sheet shows. A failed load comes before the first run card, so a user who picks Real
 * before dismissing the card still sees the error and its Retry.
 */
internal fun DiscoverUiState.sheetBody(showAll: Boolean, simple: Boolean): SheetBody = when {
    !available -> SheetBody.UNAVAILABLE
    showAll -> SheetBody.ALL_PROPERTIES
    selected != null && simple -> SheetBody.SIMPLE_CELL
    selected != null -> SheetBody.CELL
    loadFailed -> SheetBody.LOAD_FAILED
    firstRun -> SheetBody.FIRST_RUN
    else -> SheetBody.IN_VIEW
}

/** The real feed could not be read. An error with Retry, never the sample in its place. */
@Composable
private fun LoadFailed(strings: DiscoverStrings, onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        DiscoverStateMessage(strings.loadFailed, PeacockExpression.CONCERNED, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onRetry) { Text(strings.retry) }
    }
}

@Composable
private fun FirstRunCard(strings: DiscoverStrings, onAddSensor: () -> Unit, onExplore: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Peacock(size = 80.dp, expression = PeacockExpression.WELCOME)
        Text(strings.firstRunLine, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onAddSensor) { Text(strings.addSensor) }
        OutlinedButton(onClick = onExplore) { Text(strings.explore) }
    }
}

@Composable
private fun freshness(strings: DiscoverStrings, freshness: Freshness?): String? = when (freshness) {
    null -> null
    Freshness.Stale -> strings.stale
    Freshness.Recent -> strings.updatedRecently
    is Freshness.HoursAgo -> strings.updatedHoursAgo(freshness.hours)
    is Freshness.DataTo -> strings.dataTo(freshness.day)
}

@Composable
private fun InView(
    state: DiscoverUiState,
    strings: DiscoverStrings,
    networkAllowed: Boolean,
    simple: Boolean,
    reference: Long,
    highlight: ValueBand?,
    clearSignal: Int,
    onHighlight: (ValueBand?) -> Unit,
    onPickCell: (String) -> Unit,
) {
    if (state.loading && state.rows.isEmpty()) {
        // Read out when the areas arrive or fail to, while the user waits.
        DiscoverStateMessage(strings.loading, PeacockExpression.WORKING, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        return
    }
    if (state.rows.isEmpty()) {
        DiscoverStateMessage(
            strings.noAreas,
            if (networkAllowed) PeacockExpression.CONFUSED else PeacockExpression.SLEEPING,
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        return
    }
    // The chart comes first, so the peeking sheet shows it while the map is explored. Text follows.
    val property = state.property
    val units = LocalDiscoverUnits.current
    val values = property?.let { p -> state.rows.mapNotNull { it.mean(p.id) } }.orEmpty()
    val health = state.scale.who
    if (property != null && values.size >= 2) {
        if (!simple) {
            ChartTitle(property, strings, state)
            Spacer(Modifier.height(8.dp))
            SpreadChart(values, property, state.scale, strings, clearSignal = clearSignal, onHighlight = onHighlight)
            Spacer(Modifier.height(12.dp))
        } else if (health != null) {
            // Simple. Areas per mood, no axis. A property without the WHO scale shows no chart, no mood is invented.
            ChartTitle(property, strings, state)
            Spacer(Modifier.height(8.dp))
            SimpleSpread(values, health, strings, highlight, onHighlight)
            Spacer(Modifier.height(12.dp))
        }
    }
    Text(strings.areasInView(state.rows.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    freshness(strings, state.inViewFreshness(reference))?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    property ?: return
    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    // The list follows the chart's touched or pinned bar, like the map.
    val sorted = state.rows
        .filter { r -> r.mean(property.id)?.let { highlight == null || it in highlight } == true }
        .sortedByDescending { it.mean(property.id) }
    LazyColumn(Modifier.heightIn(max = 360.dp)) {
        items(sorted, key = { it.h3 }) { row ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onPickCell(H3Index.toHex(row.h3)) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val value = row.mean(property.id)!!
                val steps = (state.scale as? CellScale.Steps)?.scale
                val step = steps?.stepOf(value)
                val mood = if (simple) steps?.who?.stepOf(value) else null
                Box(Modifier.size(14.dp).clip(CircleShape).background(Color(state.scale?.color(value) ?: SequentialRamp.stops.first())))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("${formatValue(value, property, units)} ${unitLabel(property, units)}", style = MaterialTheme.typography.bodyLarge)
                    val stepText = if (mood != null) moodWord(mood) else step?.let { strings.stepName(steps, it) }
                    if (stepText != null) Text(stepText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(strings.sensors(row.devices), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Areas in view per mood, as wide as their count. Tapping a block filters the map and the list to
 * that mood, tapping it again lets go. Leaving the list, another measurement, or a pan that empties
 * the chosen mood lets go too, like the spread chart.
 */
@Composable
private fun SimpleSpread(values: List<Double>, health: HealthScale, strings: DiscoverStrings, highlight: ValueBand?, onHighlight: (ValueBand?) -> Unit) {
    val shown = stepBlocks(values, health).filter { it.weight > 0f }
    val captions = shown.map { strings.areaCount(it.weight.toInt()) }
    val bands = shown.map { b -> b.step?.let { stepBand(health, it) } }
    val selected = spreadSelection(bands, highlight)
    val release = rememberUpdatedState(onHighlight)
    DisposableEffect(health) { onDispose { release.value(null) } }
    // A chosen mood with no area left in view has no block to tap again, so it lets go by itself.
    LaunchedEffect(highlight, selected) { if (highlight != null && selected == null) release.value(null) }
    MoodStrip(
        blocks = shown,
        captions = captions,
        selected = selected,
        onBlock = { i -> onHighlight(spreadTap(bands, i, highlight)) },
    )
}

@Composable
private fun DiscoverStateMessage(text: String, expression: PeacockExpression, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PeacockIcon(expression, Modifier.size(48.dp))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Label, unit and window over a chart, with an optional [trailing] note at the end. */
@Composable
private fun ChartTitle(property: FeedProperty, strings: DiscoverStrings, state: DiscoverUiState, trailing: String? = null) {
    // One heading for the chart below, read as label, unit, window and the trailing note.
    Row(Modifier.semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.Bottom) {
        FitText("${property.shownLabel()}  ${unitLabel(property, LocalDiscoverUnits.current)}", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.width(8.dp))
        Text(
            "${strings.avg}  ${windowLabel(state.window, strings, state.daily)}",
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
    clearSignal: Int,
    onHighlight: (ValueBand?) -> Unit,
    onShowProperty: (String) -> Unit,
) {
    // Peek shows the chart with this area marked, so it stays in sight while the map is explored.
    // The headline, the device count and the other measurements follow.
    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (state.isSample) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(strings.sampleData, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.weight(1f))
                infoButton(strings.sampleInfo, Modifier.minimumInteractiveComponentSize().size(40.dp))
            }
        }
        val property = state.property
        val units = LocalDiscoverUnits.current
        val stat = property?.let { row.stats[it.id] }
        val headline = stat?.mean
        val headlineSteps = (state.scale as? CellScale.Steps)?.scale
        val headlineStep = headline?.let { headlineSteps?.stepOf(it) }
        if (property != null && headline != null) {
            val min = stat.min
            val max = stat.max
            val span = if (min != null && max != null) "${strings.min} ${formatValue(min, property, units)}  ${strings.max} ${formatValue(max, property, units)}" else null
            ChartTitle(property, strings, state, span)
            Spacer(Modifier.height(8.dp))
            SpreadChart(
                state.rows.mapNotNull { it.mean(property.id) },
                property,
                state.scale,
                strings,
                here = headline,
                hereMin = min,
                hereMax = max,
                clearSignal = clearSignal,
                onHighlight = onHighlight,
            )
            Spacer(Modifier.height(12.dp))
        }
        if (property != null && headline != null) {
            val unit = unitLabel(property, units)
            val label = property.shownLabel()
            Row(
                Modifier.clearAndSetSemantics {
                    contentDescription = "$label ${formatValue(headline, property, units)} $unit"
                    heading()
                },
            ) {
                Text(formatValue(headline, property, units), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.alignByBaseline())
                Spacer(Modifier.width(6.dp))
                Text(unit, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alignByBaseline())
                Spacer(Modifier.width(10.dp))
                Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alignByBaseline())
            }
            // The step in words, beside its colour.
            if (headlineSteps != null && headlineStep != null) StepLabel(headlineSteps, headlineStep, strings, Modifier.padding(vertical = 2.dp))
        } else {
            Text(strings.notMeasured, style = MaterialTheme.typography.titleMedium)
        }
        val parts = listOfNotNull(strings.sensors(row.devices), freshness(strings, state.cellFreshness(row, reference)))
        Text(spokenList(parts, listSeparator()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (property != null && headline != null) {
            percentBelow(state.rows.mapNotNull { it.mean(property.id) }, headline)?.let {
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
                    FitText(shownCategory(category), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp).semantics { heading() })
                }
                ranges.forEach { r -> RangeRow(r, strings) { onShowProperty(r.property.id) } }
            }
        }
    }
}

/**
 * The cell at the Simple level. Peacock and mood word beside the 24 hour value, or "n/a", one plain
 * line, then the mood of the last 7 days, 24 hours and hour. A property without a health scale
 * shows its number only. Other measurements are plain value rows.
 */
@Composable
private fun SimpleCellDetail(
    state: DiscoverUiState,
    strings: DiscoverStrings,
    infoButton: @Composable (InfoText, Modifier) -> Unit,
    reference: Long,
    onShowProperty: (String) -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (state.isSample) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(strings.sampleData, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.weight(1f))
                infoButton(strings.sampleInfo, Modifier.minimumInteractiveComponentSize().size(40.dp))
            }
        }
        val property = state.property
        val units = LocalDiscoverUnits.current
        val summary = property?.let { state.selectedSummary(it) }
        val headline = state.simpleHeadline()
        val separator = listSeparator()
        if (headline == SimpleHeadline.SHOWN && property != null && summary != null) {
            val unit = unitLabel(property, units)
            val value = summary.value?.let { formatValue(it, property, units) }
            val shown = value ?: notAvailableText()
            val word = if (summary.scaled) moodWord(summary.step) else null
            val spoken = spokenList(listOfNotNull(word, property.shownLabel(), if (value != null) "$value $unit" else shown), separator)
            Row(
                Modifier.clearAndSetSemantics {
                    contentDescription = spoken
                    heading()
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (summary.scaled) {
                    PeacockIcon(peacockExpressionFor(summary.step), Modifier.size(56.dp))
                    Spacer(Modifier.width(12.dp))
                }
                Column {
                    if (word != null) Text(word, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Row {
                        Text(shown, style = MaterialTheme.typography.titleLarge, modifier = Modifier.alignByBaseline())
                        if (value != null) {
                            Spacer(Modifier.width(6.dp))
                            Text(unit, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alignByBaseline())
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(property.shownLabel(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alignByBaseline())
                    }
                }
            }
            if (summary.scaled) {
                Spacer(Modifier.height(4.dp))
                Text(moodLine(summary.step), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                // One block per window the feed offers, oldest first, so Real shows 7 days and 24 hours.
                MoodStrip(summary.blocks, captions = PublicSummary.ORDER.filter { it in state.windows }.map { windowLabel(it, strings, state.daily) })
            }
        } else if (headline == SimpleHeadline.LOADING) {
            // The 24 hour row is still on its way. Neutral, no mood and no verdict yet.
            Text(
                strings.loading,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        } else if (headline == SimpleHeadline.FAILED) {
            // A read failed after its retries. Never "Not measured", since nothing says so.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    strings.readFailed,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false).semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onRetry) { Text(strings.retry) }
            }
        } else {
            Text(strings.notMeasured, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        // Sensors, freshness and the other values come from the 24 hour row, like the headline.
        val facts = state.simpleCellFacts()
        val updated = facts.updatedEpochMs
        val parts = listOfNotNull(facts.devices?.let { strings.sensors(it) }, freshness(strings, freshnessOf(updated, reference, state.dailyAsOf)))
        if (parts.isNotEmpty()) Text(spokenList(parts, listSeparator()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val others = facts.others
        if (others.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            others.forEach { (p, mean) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onShowProperty(p.id) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(p.shownLabel(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(mean?.let { "${formatValue(it, p, units)} ${unitLabel(p, units)}" } ?: notAvailableText(), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun AllProperties(state: DiscoverUiState, strings: DiscoverStrings, onPick: (String) -> Unit) {
    Text(strings.allMeasurements, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(8.dp))
    // Grouped by the category the dictionary gives each property (Air, Weather, and so on).
    state.dictionary.groupBy { it.category }.forEach { (category, properties) ->
        FitText(shownCategory(category), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() })
        properties.forEach { p ->
            val count = state.rows.count { it.mean(p.id) != null }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onPick(p.id) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(p.shownLabel(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), color = if (count == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                Text(strings.areasInView(count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
