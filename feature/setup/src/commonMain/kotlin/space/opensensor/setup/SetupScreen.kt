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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import space.opensensor.data.sync.PlanStepKind
import space.opensensor.designsystem.AppIcons
import space.opensensor.designsystem.BodyText
import space.opensensor.designsystem.BrandCard
import space.opensensor.designsystem.CardHeader
import space.opensensor.designsystem.FitText
import space.opensensor.designsystem.InfoContent
import space.opensensor.designsystem.InfoNote
import space.opensensor.designsystem.PillButton
import space.opensensor.designsystem.WarningNote
import space.opensensor.device.BondsCard
import space.opensensor.device.DeviceConfigAction
import space.opensensor.device.DeviceConfigController
import space.opensensor.device.DeviceConfigEffects
import space.opensensor.device.DeviceConfigUiState
import space.opensensor.device.DeviceLocationForm
import space.opensensor.device.DeviceLocationResult
import space.opensensor.device.DeviceSiteForm
import space.opensensor.device.DeviceSiteResult
import space.opensensor.device.GenericFieldsForm
import space.opensensor.device.PairingCard
import space.opensensor.device.ReconnectOverBluetooth
import space.opensensor.device.WifiCard
import space.opensensor.device.canEditBoard
import space.opensensor.device.canSaveSite
import space.opensensor.model.StepId
import space.opensensor.permissions.RuntimeAccess
import space.opensensor.settings.DetailLevel
import space.opensensor.setup.resources.Res
import space.opensensor.setup.resources.setup_ack_failed
import space.opensensor.setup.resources.setup_added
import space.opensensor.setup.resources.setup_back
import space.opensensor.setup.resources.setup_board_title
import space.opensensor.setup.resources.setup_bonds_title
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
import space.opensensor.setup.resources.setup_site_title
import space.opensensor.setup.resources.setup_skip
import space.opensensor.setup.resources.setup_stay
import space.opensensor.setup.resources.setup_title
import space.opensensor.setup.resources.setup_unsupported
import space.opensensor.setup.resources.setup_waiting
import space.opensensor.setup.resources.setup_wifi_title

/**
 * The setup wizard, full screen with no bottom bar. It walks the sensor's [SetupPlan], read from its
 * board template and its page 1 setup record by [config], the Device settings controller of the
 * picked sensor, so every page sends exactly what the settings cards send. [bluetooth] is asked for
 * from Look for sensors. [onConfigAction] runs a card action the way the platform's Device settings
 * does. Setup never asks for local network. Next and Skip set the step's bit in the device's setup
 * record through [DeviceConfigController.ackSetup], and the page moves on only once the board kept it.
 */
@Composable
fun SetupScreen(
    controller: SetupController,
    config: DeviceConfigController?,
    bluetooth: RuntimeAccess?,
    onConfigAction: (DeviceConfigAction) -> Unit,
    onClose: () -> Unit,
    onOpenSensor: (String) -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val s by controller.state.collectAsState()
    val latestOnClose by rememberUpdatedState(onClose)
    LaunchedEffect(s.closed) { if (s.closed) latestOnClose() }
    // System back, the Android gesture or the iOS edge swipe, does what the toolbar arrow does. After
    // pairing it asks before leaving. It wins over the shell's NavDisplay, the newest handler runs first.
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = !s.closed,
        onBackCompleted = controller::requestClose,
    )
    // The snackbar's Bluetooth reconnect goes through the platform route too, which asks for Bluetooth.
    if (config != null) DeviceConfigEffects(config, snackbar) { onConfigAction(DeviceConfigAction.ReconnectOverBluetooth) }
    val configState = config?.state?.collectAsState()?.value
    // The plan follows the board's confirmed page 1 and its template, never an unsaved draft. Page 1
    // can be read before pairing ends, so pairing hands the plan over again.
    val plan = configState?.setupPlan
    val level = configState?.level ?: DetailLevel.SIMPLE
    LaunchedEffect(plan, level, s.paired) { controller.onPlan(plan, level) }
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
                        Icon(AppIcons.ArrowBack, contentDescription = stringResource(Res.string.setup_back))
                    }
                },
                title = {
                    FitText(pageTitle(s), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                },
            )
        },
        bottomBar = { SetupNav(s, configState, controller, config) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (val page = s.page) {
                SetupPage.Find -> findItems(s, controller, bluetooth)
                SetupPage.Loading -> item(key = "loading") { LoadingCard(configState, onConfigAction) }
                is SetupPage.Step -> stepItems(page.id, s, controller, configState, onConfigAction)
                SetupPage.Done -> item(key = "done") { DoneCard(s, onOpenSensor) }
            }
        }
    }
}

/** A board step's template label in the app language, else the page's own title. */
@Composable
private fun pageTitle(s: SetupUiState): String = s.title(Locale.current.language) ?: stringResource(pageTitleRes(s))

private fun pageTitleRes(s: SetupUiState): StringResource = when (val page = s.page) {
    SetupPage.Find, SetupPage.Loading -> Res.string.setup_title
    SetupPage.Done -> Res.string.setup_done_title
    is SetupPage.Step -> when {
        s.step?.kind == PlanStepKind.UNSUPPORTED -> Res.string.setup_title
        s.step?.kind == PlanStepKind.GENERIC -> Res.string.setup_board_title
        else -> when (page.id) {
            StepId.NAME -> Res.string.setup_name_title
            StepId.LOCATION, StepId.MOBILITY -> Res.string.setup_location_title
            StepId.SITE -> Res.string.setup_site_title
            StepId.WIFI -> Res.string.setup_wifi_title
            StepId.PAIRING -> Res.string.setup_pairing
            StepId.BONDS -> Res.string.setup_bonds_title
            else -> Res.string.setup_title
        }
    }
}

/** The page of one plan step, the same card or form Device settings shows for it. */
private fun LazyListScope.stepItems(
    id: StepId,
    s: SetupUiState,
    controller: SetupController,
    config: DeviceConfigUiState?,
    onConfigAction: (DeviceConfigAction) -> Unit,
) {
    val step = s.step
    when {
        step?.kind == PlanStepKind.UNSUPPORTED -> item(key = "unsupported") { WarningNote(stringResource(Res.string.setup_unsupported)) }
        step?.kind == PlanStepKind.GENERIC -> configItem(config) { state -> GenericFieldsForm(state, step.fields, onConfigAction) }
        id == StepId.NAME -> item(key = "name") { NameCard(s, controller) }
        id == StepId.LOCATION || id == StepId.MOBILITY -> {
            configItem(config) { state -> DeviceLocationForm(state, onConfigAction) }
            if (step?.required == true && !step.satisfied) item(key = "location-needed") { InfoNote(stringResource(Res.string.setup_location_needed)) }
        }
        id == StepId.SITE -> configItem(config) { state -> DeviceSiteForm(state, onConfigAction, showSave = false) }
        id == StepId.WIFI -> configItem(config) { state -> WifiForm(state, onConfigAction) }
        id == StepId.PAIRING -> readyItem(config) { state ->
            PairingCard(state, onSave = { onConfigAction(DeviceConfigAction.SavePairing(it)) }, onReboot = { onConfigAction(DeviceConfigAction.RequestReboot) })
        }
        id == StepId.BONDS -> readyItem(config) { state -> BondsCard(state, onForgetAll = { onConfigAction(DeviceConfigAction.RequestForgetBonds) }) }
        // A catalog step this screen draws nothing for. Next still sets its bit.
        else -> Unit
    }
}

/**
 * Pairing done, the plan not read yet. Page 1 is read once the link is ready. When that read failed
 * it offers a new one, which sends nothing to the board.
 */
@Composable
private fun LoadingCard(config: DeviceConfigUiState?, onConfigAction: (DeviceConfigAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoNote(stringResource(Res.string.setup_waiting))
        if (config?.location?.result == DeviceLocationResult.FAILED && config.location.working.not()) {
            TextButton(onClick = { onConfigAction(DeviceConfigAction.Refresh) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(Res.string.setup_retry))
            }
        }
    }
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
        CandidateRow(candidate, pairing = s.pairingWith(candidate), enabled = !s.pairing) { controller.choose(candidate) }
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
    readyItem(state) { ready -> BrandCard { content(ready) } }
}

/** A card that draws its own frame, once the link is ready. */
private fun LazyListScope.readyItem(state: DeviceConfigUiState?, content: @Composable (DeviceConfigUiState) -> Unit) {
    item(key = "config") {
        if (state == null || !state.ready) InfoNote(stringResource(Res.string.setup_waiting)) else content(state)
    }
}

/**
 * Join over Bluetooth, the same card as Device settings. Wi-Fi sync stays on by itself. A setup
 * whose link runs on Wi-Fi gets a way back to Bluetooth, since the passphrase goes over Bluetooth only.
 */
@Composable
private fun WifiForm(state: DeviceConfigUiState, onAction: (DeviceConfigAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        WifiCard(
            state = state,
            onToggleWifi = { onAction(DeviceConfigAction.SetWifiOn(it)) },
            onScan = { onAction(DeviceConfigAction.ScanWifi) },
            onClearScan = { onAction(DeviceConfigAction.ClearWifiScan) },
            onJoin = { onAction(DeviceConfigAction.JoinWifi(it)) },
        )
        ReconnectOverBluetooth(state, onReconnectBle = { onAction(DeviceConfigAction.ReconnectOverBluetooth) })
    }
}

@Composable
private fun DoneCard(s: SetupUiState, onOpenSensor: (String) -> Unit) {
    BrandCard {
        FitText(s.displayName.orEmpty(), style = MaterialTheme.typography.headlineSmall)
        s.selected?.let { sensor -> PillButton(stringResource(Res.string.setup_done_open), onClick = { onOpenSensor(sensor.id) }) }
    }
}

/**
 * Back, Skip and Next. Next on a page with unsaved values is the explicit save. Site sends its keys
 * in their own `SET_CONFIG` and a board step its declared keys, and the page moves on once the board
 * confirms them and keeps the step's bit. Skip sends only the bit, and is off while a save runs. A bit
 * the sensor did not confirm says so above the buttons, and Retry sends only the bit again. An
 * unsupported required step offers Leave, setup stays pending until the app is updated.
 */
@Composable
private fun SetupNav(s: SetupUiState, config: DeviceConfigUiState?, controller: SetupController, device: DeviceConfigController?) {
    if (s.page == SetupPage.Find) return
    val ack: suspend (Collection<StepId>) -> Boolean = { ids -> device?.ackSetup(ids) ?: false }
    val step = s.step
    val site = config?.site
    val savesSite = step?.id == StepId.SITE && site?.dirty == true
    val boardFields = step?.takeIf { it.kind == PlanStepKind.GENERIC }?.fields.orEmpty()
    val savesBoard = config != null && config.board.changes(boardFields).isNotEmpty()
    val working = config?.location?.working == true || site?.working == true || config?.board?.working == true
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s.ackFailed) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                    WarningNote(stringResource(Res.string.setup_ack_failed))
                }
                TextButton(onClick = { controller.retryAck(ack) }, enabled = !s.saving && !working, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(Res.string.setup_retry))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.canGoBack) {
                TextButton(onClick = controller::back, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(Res.string.setup_back)) }
            }
            Spacer(Modifier.weight(1f))
            if (step?.skippable == true) {
                TextButton(onClick = { controller.skip(ack) }, enabled = s.canSkip && !working, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(Res.string.setup_skip))
                }
            }
            if (step?.kind == PlanStepKind.UNSUPPORTED) {
                PillButton(stringResource(Res.string.setup_leave), onClick = controller::finish)
                return@Row
            }
            val busy = working || config?.snapshot?.busy == true
            val canSave = when {
                savesSite -> config?.canSaveSite == true
                savesBoard -> config?.canEditBoard == true
                else -> true
            }
            PillButton(
                stringResource(if (s.page == SetupPage.Done) Res.string.setup_close else Res.string.setup_next),
                onClick = {
                    when {
                        savesSite -> controller.next(save = { device?.saveSiteAndWait() == DeviceSiteResult.SAVED }, ack = ack)
                        savesBoard -> controller.next(save = { device?.saveBoardAndWait(boardFields) == true }, ack = ack)
                        else -> controller.next(ack = ack)
                    }
                },
                enabled = s.canAdvance && !busy && canSave,
            )
        }
    }
}
