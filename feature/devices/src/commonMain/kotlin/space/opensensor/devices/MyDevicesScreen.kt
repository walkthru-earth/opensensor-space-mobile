package space.opensensor.devices

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import space.opensensor.designsystem.AppIcons
import space.opensensor.designsystem.Eyebrow
import space.opensensor.designsystem.FitText
import space.opensensor.designsystem.FloatingControl
import space.opensensor.designsystem.FloatingTextButton
import space.opensensor.designsystem.InfoButton
import space.opensensor.designsystem.InfoContent
import space.opensensor.designsystem.MascotGuide
import space.opensensor.designsystem.PeacockExpression
import space.opensensor.devices.resources.Res
import space.opensensor.devices.resources.devices_on_wifi
import space.opensensor.devices.resources.info_collect_background
import space.opensensor.devices.resources.info_collect_body
import space.opensensor.devices.resources.info_collect_foreground
import space.opensensor.devices.resources.info_connect_body1
import space.opensensor.devices.resources.info_connect_body2
import space.opensensor.devices.resources.info_connect_title
import space.opensensor.devices.resources.info_privacy
import space.opensensor.devices.resources.scan_bluetooth_off
import space.opensensor.devices.resources.scan_button_scan
import space.opensensor.devices.resources.scan_button_stop
import space.opensensor.devices.resources.scan_collect_all
import space.opensensor.devices.resources.scan_collect_running
import space.opensensor.devices.resources.scan_connections
import space.opensensor.devices.resources.scan_failed
import space.opensensor.devices.resources.scan_known
import space.opensensor.devices.resources.scan_local_network_denied
import space.opensensor.devices.resources.scan_nearby
import space.opensensor.devices.resources.scan_none_nearby_body
import space.opensensor.devices.resources.scan_none_nearby_title
import space.opensensor.devices.resources.scan_unnamed
import space.opensensor.devices.resources.scan_welcome_body
import space.opensensor.devices.resources.scan_welcome_title
import space.opensensor.permissions.RuntimeAccess
import space.opensensor.settings.DetailLevel
import space.opensensor.strings.resources.perm_open_settings
import space.opensensor.strings.resources.tab_my_devices
import space.opensensor.units.UnitFormatter
import space.opensensor.strings.resources.Res as SharedRes

/** What differs between the two apps on this screen. */
data class DevicesPlatform(
    /** Android runs Collect all in a background worker with a notification, iOS while the app is open. */
    val collectInBackground: Boolean,
)

/**
 * My devices. One list of the links open now, the sensors nearby, the known sensors that are not
 * nearby and the sensors on the Wi-Fi this phone does not know yet. Stateless, the route owns the
 * controller and the permission dialogs.
 *
 * Bluetooth is asked for only from a tap on Scan or Collect from all. Opening a sensor never asks,
 * see [openThing]. Once it is allowed and the radio is on, the screen scans while it is visible
 * and stops when it leaves. [units] shows the archive day means in the owner's unit system.
 */
@Composable
fun MyDevicesScreen(
    state: DevicesUiState,
    level: DetailLevel,
    bluetooth: RuntimeAccess,
    platform: DevicesPlatform,
    onAction: (DevicesAction) -> Unit,
    modifier: Modifier = Modifier,
    topActions: @Composable RowScope.() -> Unit = {},
    onScanTap: (() -> Unit)? = null,
    onOpenLocalNetworkSettings: (() -> Unit)? = null,
    units: UnitFormatter = UnitFormatter.Metric,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var explicitScan by remember { mutableStateOf(false) }
    LaunchedEffect(state.scanning) { if (!state.scanning) explicitScan = false }
    val showStop = state.scanning && (onScanTap == null || explicitScan)

    LifecycleStartEffect(bluetooth.ready) {
        if (bluetooth.ready) onAction(DevicesAction.StartScan)
        onStopOrDispose { onAction(DevicesAction.StopScan) }
    }

    fun open(id: String, name: String?) = openThing(id, name, bluetooth, onAction)

    CompositionLocalProvider(LocalArchiveUnits provides units) {
        Scaffold(
            modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                LargeTopAppBar(
                    // One line, shrunk to fit, so "أجهزتي" never leaves its last letter behind (#144).
                    title = { FitText(stringResource(SharedRes.string.tab_my_devices), Modifier.semantics { heading() }) },
                    scrollBehavior = scrollBehavior,
                    actions = {
                        InfoButton(
                            InfoContent(
                                stringResource(Res.string.info_connect_title),
                                stringResource(Res.string.info_connect_body1),
                                stringResource(Res.string.info_connect_body2),
                                stringResource(Res.string.info_privacy),
                            ),
                        )
                        topActions()
                    },
                )
            },
            // Scan floats at the end below the list, not over it. The list stops above it, so no row is
            // ever under it, at rest or scrolled, at any font size (#146).
            bottomBar = {
                val onClick = {
                    if (showStop) {
                        explicitScan = false
                        onAction(DevicesAction.StopScan)
                    } else {
                        explicitScan = true
                        // A declined Bluetooth request still allows a local Wi-Fi browse.
                        val start = { if (onScanTap == null) onAction(DevicesAction.StartScan) else onScanTap() }
                        bluetooth.run(onDeclined = start, action = start)
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                        .padding(horizontal = 16.dp, vertical = FloatingControl.Margin),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    FloatingTextButton(
                        text = stringResource(if (showStop) Res.string.scan_button_stop else Res.string.scan_button_scan),
                        onClick = onClick,
                        modifier = Modifier.testTag(SCAN_BUTTON_TAG),
                        primary = !showStop,
                        icon = { Icon(if (showStop) AppIcons.Stop else AppIcons.Radar, contentDescription = null) },
                    )
                }
            },
        ) { padding ->
            val list = state.list
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding()),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (list.active.isNotEmpty()) {
                    item(key = "active-header") { Eyebrow(stringResource(Res.string.scan_connections), heading = true) }
                    items(list.active, key = { "a:" + it.id }) { link ->
                        ActiveRow(link, onClick = { open(link.id, link.name) })
                    }
                }
                item(key = "progress") {
                    AnimatedVisibility(visible = state.scanning) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                }
                state.scanProblem?.let { problem ->
                    item(key = "problem") {
                        // Appears while the user waits on a scan, so screen readers hear it without moving focus.
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    stringResource(
                                        when (problem) {
                                            ScanProblem.BLUETOOTH_OFF -> Res.string.scan_bluetooth_off
                                            ScanProblem.LOCAL_NETWORK_DENIED -> Res.string.scan_local_network_denied
                                            ScanProblem.FAILED -> Res.string.scan_failed
                                        },
                                    ),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                if (problem == ScanProblem.LOCAL_NETWORK_DENIED && onOpenLocalNetworkSettings != null) {
                                    TextButton(onClick = onOpenLocalNetworkSettings) {
                                        Text(stringResource(SharedRes.string.perm_open_settings))
                                    }
                                }
                            }
                        }
                    }
                }

                if (list.nearby.isEmpty()) {
                    // The peacock guides the empty moments, a first run or nothing nearby.
                    item(key = "guide") {
                        if (list.knownCount == 0) {
                            MascotGuide(stringResource(Res.string.scan_welcome_title), stringResource(Res.string.scan_welcome_body), expression = PeacockExpression.WELCOME)
                        } else {
                            MascotGuide(stringResource(Res.string.scan_none_nearby_title), stringResource(Res.string.scan_none_nearby_body), expression = PeacockExpression.CONFUSED)
                        }
                    }
                } else {
                    item(key = "nearby-header") { Eyebrow(stringResource(Res.string.scan_nearby), heading = true) }
                }
                items(list.nearby, key = { "n:" + it.id }) { row ->
                    NearbyRow(
                        row = row,
                        level = level,
                        onClick = { openNearby(row, bluetooth, onAction) },
                    )
                }

                if (list.known.isNotEmpty()) {
                    item(key = "known-header") {
                        Spacer(Modifier.height(8.dp))
                        Eyebrow(stringResource(Res.string.scan_known), heading = true)
                    }
                    items(list.known, key = { "k:" + it.id }) { row ->
                        KnownRow(
                            row = row,
                            level = level,
                            onClick = { open(row.id, row.name) },
                        )
                    }
                }

                if (list.wifiOnly.isNotEmpty()) {
                    item(key = "wifi-header") {
                        Spacer(Modifier.height(8.dp))
                        Eyebrow(stringResource(Res.string.devices_on_wifi), heading = true)
                    }
                    items(list.wifiOnly, key = { "w:" + it.name }) { sensor -> WifiOnlyRow(sensor, level) }
                }

                // Several sensors, one pass over all of them, Wi-Fi first, then Bluetooth, one link per thing.
                if (state.showCollectAll) {
                    item(key = "collect") {
                        CollectAllRow(
                            count = list.knownCount,
                            running = state.collecting,
                            inBackground = platform.collectInBackground,
                            onClick = {
                                // Bluetooth steps need the permission. Declining still runs the Wi-Fi steps.
                                bluetooth.run(onDeclined = { onAction(DevicesAction.CollectAll) }) { onAction(DevicesAction.CollectAll) }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Test tag of the Scan button. */
internal const val SCAN_BUTTON_TAG = "devices-scan"

/**
 * Opens a thing from My devices. It never asks for Bluetooth, so a Deny is never asked again on
 * the next tap (#147). The thing opens on the phone archive and connects only over a link that is
 * ready now, Bluetooth when it is allowed and on, else Wi-Fi when the platform has one. Reconnect in
 * the thing header is the tap that asks for Bluetooth.
 */
internal fun openThing(id: String, name: String?, bluetooth: RuntimeAccess, onAction: (DevicesAction) -> Unit) {
    onAction(DevicesAction.Open(id, name, connect = bluetooth.ready))
}

/** A nearby row. A new sensor goes through the Add sensor wizard, so setup always comes before Today. */
internal fun openNearby(row: DeviceRow, bluetooth: RuntimeAccess, onAction: (DevicesAction) -> Unit) {
    if (row.known) openThing(row.id, row.name, bluetooth, onAction) else onAction(DevicesAction.Add(row.id, row.name))
}

@Composable
private fun CollectAllRow(count: Int, running: Boolean, inBackground: Boolean, onClick: () -> Unit) {
    val title = pluralStringResource(Res.plurals.scan_collect_all, count, count)
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(
            onClick = onClick,
            enabled = !running,
            shape = FloatingControl.SmallShape,
            modifier = Modifier.weight(1f),
        ) {
            Icon(AppIcons.CloudSync, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            // Polite, so the switch to "Collecting" is read out after the tap.
            Text(
                if (running) stringResource(Res.string.scan_collect_running) else title,
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        InfoButton(
            InfoContent(
                title,
                stringResource(Res.string.info_collect_body),
                stringResource(if (inBackground) Res.string.info_collect_background else Res.string.info_collect_foreground),
            ),
        )
    }
}
