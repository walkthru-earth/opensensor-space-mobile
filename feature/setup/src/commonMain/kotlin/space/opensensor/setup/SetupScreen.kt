package space.opensensor.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import space.opensensor.designsystem.AppIcons
import space.opensensor.designsystem.BodyText
import space.opensensor.designsystem.BrandCard
import space.opensensor.designsystem.CardHeader
import space.opensensor.designsystem.FitText
import space.opensensor.designsystem.InfoContent
import space.opensensor.designsystem.InfoNote
import space.opensensor.designsystem.PillButton
import space.opensensor.designsystem.WarningNote
import space.opensensor.device.DeviceConfigAction
import space.opensensor.device.DeviceConfigController
import space.opensensor.device.DeviceConfigEffects
import space.opensensor.device.DeviceConfigUiState
import space.opensensor.device.DeviceLocationForm
import space.opensensor.device.LanAccess
import space.opensensor.device.LanCard
import space.opensensor.device.WifiCard
import space.opensensor.permissions.RuntimeAccess
import space.opensensor.setup.resources.Res
import space.opensensor.setup.resources.setup_added
import space.opensensor.setup.resources.setup_back
import space.opensensor.setup.resources.setup_close
import space.opensensor.setup.resources.setup_done_open
import space.opensensor.setup.resources.setup_done_title
import space.opensensor.setup.resources.setup_fail_bluetooth
import space.opensensor.setup.resources.setup_fail_pairing
import space.opensensor.setup.resources.setup_fail_unreachable
import space.opensensor.setup.resources.setup_fail_update
import space.opensensor.setup.resources.setup_find_empty
import space.opensensor.setup.resources.setup_find_info
import space.opensensor.setup.resources.setup_find_searching
import space.opensensor.setup.resources.setup_find_start
import space.opensensor.setup.resources.setup_find_title
import space.opensensor.setup.resources.setup_leave
import space.opensensor.setup.resources.setup_leave_body
import space.opensensor.setup.resources.setup_leave_title
import space.opensensor.setup.resources.setup_location_needed
import space.opensensor.setup.resources.setup_location_title
import space.opensensor.setup.resources.setup_name_info
import space.opensensor.setup.resources.setup_name_label
import space.opensensor.setup.resources.setup_name_title
import space.opensensor.setup.resources.setup_name_too_long
import space.opensensor.setup.resources.setup_next
import space.opensensor.setup.resources.setup_pairing
import space.opensensor.setup.resources.setup_retry
import space.opensensor.setup.resources.setup_skip
import space.opensensor.setup.resources.setup_stay
import space.opensensor.setup.resources.setup_title
import space.opensensor.setup.resources.setup_waiting
import space.opensensor.setup.resources.setup_wifi_title

/**
 * The setup wizard, full screen with no bottom bar. [config] is the Device settings controller of the
 * picked sensor, so Location and Wi-Fi send exactly what the settings cards send. [bluetooth] is the
 * access [SetupFlows] binds to Find, asked for from Look for sensors. [onConfigAction] runs a card
 * action the way the platform's Device settings does (local network for Turn on Wi-Fi sync).
 */
@Composable
fun SetupScreen(
    controller: SetupController,
    config: DeviceConfigController?,
    bluetooth: RuntimeAccess?,
    lanAccess: LanAccess,
    onConfigAction: (DeviceConfigAction) -> Unit,
    onClose: () -> Unit,
    onOpenSensor: (String) -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val s by controller.state.collectAsState()
    LaunchedEffect(s.closed) { if (s.closed) onClose() }
    // System back, the Android gesture or the iOS edge swipe, does what the toolbar arrow does. After
    // pairing it asks before leaving. It wins over the shell's NavDisplay, the newest handler runs first.
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = !s.closed,
        onBackCompleted = controller::requestClose,
    )
    if (config != null) DeviceConfigEffects(config, snackbar)
    val configState = config?.state?.collectAsState()?.value
    // Only the board's confirmed answer counts, never an unsaved draft.
    val located = configState?.location?.saved == true
    LaunchedEffect(located) { controller.onLocated(located) }
    if (s.askClose) {
        AlertDialog(
            onDismissRequest = controller::dismissClose,
            title = { Text(stringResource(Res.string.setup_leave_title)) },
            text = { BodyText(stringResource(Res.string.setup_leave_body)) },
            confirmButton = { TextButton(onClick = controller::finish) { Text(stringResource(Res.string.setup_leave)) } },
            dismissButton = { TextButton(onClick = controller::dismissClose) { Text(stringResource(Res.string.setup_stay)) } },
        )
    }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    IconButton(onClick = controller::requestClose) {
                        Icon(AppIcons.ArrowBack, contentDescription = stringResource(Res.string.setup_close))
                    }
                },
                title = {
                    FitText(stringResource(pageTitle(s.page)), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                },
            )
        },
        bottomBar = { SetupNav(s, configState, controller) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (val page = s.page) {
                SetupStep.Find -> findItems(s, controller, bluetooth)
                SetupStep.Name -> item(key = "name") { NameCard(s, controller) }
                is SetupStep.Location -> {
                    configItem(configState) { state -> DeviceLocationForm(state, onConfigAction) }
                    if (page.required && !s.located) item(key = "location-needed") { InfoNote(stringResource(Res.string.setup_location_needed)) }
                }
                is SetupStep.Fields -> configItem(configState) { state -> WifiForm(state, lanAccess, onConfigAction) }
                SetupStep.Done -> item(key = "done") { DoneCard(s, onOpenSensor) }
                is SetupStep.Permission, SetupStep.Pair -> Unit
            }
        }
    }
}

private fun pageTitle(page: SetupStep): StringResource = when (page) {
    SetupStep.Find -> Res.string.setup_title
    SetupStep.Name -> Res.string.setup_name_title
    is SetupStep.Location -> Res.string.setup_location_title
    is SetupStep.Fields -> Res.string.setup_wifi_title
    SetupStep.Done -> Res.string.setup_done_title
    is SetupStep.Permission, SetupStep.Pair -> Res.string.setup_title
}

private fun LazyListScope.findItems(s: SetupUiState, controller: SetupController, bluetooth: RuntimeAccess?) {
    item(key = "find") {
        val title = stringResource(Res.string.setup_find_title)
        BrandCard {
            CardHeader(title, info = InfoContent(title, stringResource(Res.string.setup_find_info)))
            when {
                s.finding && s.candidates.isEmpty() -> BodyText(stringResource(Res.string.setup_find_searching))
                !s.finding && s.selected == null -> PillButton(
                    stringResource(Res.string.setup_find_start),
                    // Bluetooth is asked for from this tap, the FIND_SENSORS trigger, never before.
                    onClick = { if (bluetooth == null) controller.find() else bluetooth.run { controller.find() } },
                )
            }
            s.failure?.let { failure ->
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    WarningNote(stringResource(failureText(failure)))
                }
                TextButton(onClick = controller::retry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(Res.string.setup_retry)) }
            }
        }
    }
    if (s.finding && s.candidates.isEmpty()) {
        item(key = "empty") { InfoNote(stringResource(Res.string.setup_find_empty)) }
    }
    items(s.candidates, key = { "c-" + it.id }) { candidate ->
        CandidateRow(candidate, pairing = s.pairing && s.selected?.id == candidate.id, enabled = !s.pairing) { controller.choose(candidate) }
    }
}

private fun failureText(failure: SetupFailure): StringResource = when (failure) {
    SetupFailure.UPDATE_FIRMWARE -> Res.string.setup_fail_update
    SetupFailure.PAIRING -> Res.string.setup_fail_pairing
    SetupFailure.UNREACHABLE -> Res.string.setup_fail_unreachable
    SetupFailure.BLUETOOTH -> Res.string.setup_fail_bluetooth
}

@Composable
private fun CandidateRow(candidate: SetupCandidate, pairing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    BrandCard(
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            FitText(candidate.name ?: candidate.id, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            when {
                pairing -> {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    FitText(stringResource(Res.string.setup_pairing), style = MaterialTheme.typography.labelLarge)
                }
                candidate.added -> FitText(stringResource(Res.string.setup_added), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NameCard(s: SetupUiState, controller: SetupController) {
    val title = stringResource(Res.string.setup_name_title)
    BrandCard {
        CardHeader(title, info = InfoContent(title, stringResource(Res.string.setup_name_info)))
        OutlinedTextField(
            value = s.nickname,
            onValueChange = controller::setNickname,
            label = { Text(stringResource(Res.string.setup_name_label)) },
            placeholder = s.selected?.name?.let { name -> { Text(name) } },
            singleLine = true,
            isError = !s.nicknameValid,
            supportingText = if (s.nicknameValid) {
                null
            } else {
                (
                    {
                        Text(stringResource(Res.string.setup_name_too_long), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    )
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A form that needs the sensor's config. Until the link is ready it says so. */
private fun LazyListScope.configItem(state: DeviceConfigUiState?, content: @Composable (DeviceConfigUiState) -> Unit) {
    item(key = "config") {
        if (state == null || !state.ready) {
            InfoNote(stringResource(Res.string.setup_waiting))
        } else {
            BrandCard { content(state) }
        }
    }
}

/** Join over Bluetooth, then Turn on Wi-Fi sync, the same cards as Device settings. */
@Composable
private fun WifiForm(state: DeviceConfigUiState, lanAccess: LanAccess, onAction: (DeviceConfigAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        WifiCard(
            state = state,
            onToggleWifi = { onAction(DeviceConfigAction.SetWifiOn(it)) },
            onScan = { onAction(DeviceConfigAction.ScanWifi) },
            onClearScan = { onAction(DeviceConfigAction.ClearWifiScan) },
            onJoin = { onAction(DeviceConfigAction.JoinWifi(it)) },
        )
        LanCard(
            state = state,
            access = lanAccess,
            onRequestAccess = { onAction(DeviceConfigAction.RequestLanAccess) },
            onToggleLan = { onAction(DeviceConfigAction.SetLanOn(it)) },
            onFetchToken = { onAction(DeviceConfigAction.FetchToken) },
            onRotateToken = { onAction(DeviceConfigAction.RequestRotateToken) },
            onReconnectBle = { onAction(DeviceConfigAction.ReconnectOverBluetooth) },
        )
    }
}

@Composable
private fun DoneCard(s: SetupUiState, onOpenSensor: (String) -> Unit) {
    BrandCard {
        FitText(s.displayName.orEmpty(), style = MaterialTheme.typography.headlineSmall)
        s.selected?.let { sensor -> PillButton(stringResource(Res.string.setup_done_open), onClick = { onOpenSensor(sensor.id) }) }
    }
}

@Composable
private fun SetupNav(s: SetupUiState, config: DeviceConfigUiState?, controller: SetupController) {
    if (s.page == SetupStep.Find) return
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (s.page != SetupStep.Done && !(s.page == SetupStep.Name && s.paired)) {
            TextButton(onClick = controller::back, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(Res.string.setup_back)) }
        }
        Spacer(Modifier.weight(1f))
        if (s.page.skippable) {
            TextButton(onClick = controller::skip, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(Res.string.setup_skip)) }
        }
        val busy = config?.location?.working == true || config?.snapshot?.busy == true
        PillButton(
            stringResource(if (s.page == SetupStep.Done) Res.string.setup_close else Res.string.setup_next),
            onClick = controller::next,
            enabled = s.canAdvance && !busy,
        )
    }
}
