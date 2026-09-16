package earth.walkthru.aqsync.ui.device

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import earth.walkthru.aqsync.data.DeviceEvent
import earth.walkthru.aqsync.data.WifiScanState
import earth.walkthru.aqsync.data.ble.ConnectionState
import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.protocol.ConfigPatch
import earth.walkthru.aqsync.data.protocol.Frame
import earth.walkthru.aqsync.data.protocol.PairMode
import earth.walkthru.aqsync.data.protocol.WifiState
import earth.walkthru.aqsync.ui.components.ConnectionBanner
import earth.walkthru.aqsync.ui.components.DeviceBottomBar
import earth.walkthru.aqsync.ui.components.DeviceTab
import earth.walkthru.aqsync.ui.components.InfoNote
import earth.walkthru.aqsync.ui.components.SectionCard
import earth.walkthru.aqsync.ui.components.StatLine
import earth.walkthru.aqsync.ui.components.StatusDot
import earth.walkthru.aqsync.ui.components.TransportChip
import earth.walkthru.aqsync.ui.components.WarningNote
import earth.walkthru.aqsync.ui.components.formatAgo
import earth.walkthru.aqsync.ui.components.rememberNowMillis
import earth.walkthru.aqsync.ui.permissions.rememberLanPermission
import earth.walkthru.aqsync.ui.settings.SettingsSheet
import kotlinx.coroutines.delay

/**
 * Device configuration (protocol v2): pairing mode and PIN, Wi-Fi provisioning, LAN token, bonds.
 * Every write is a `SET_CONFIG` that the device validates as a whole; `ble.*` keys apply at the
 * next boot, so the card says so and offers a reboot.
 */
@Composable
fun DeviceScreen(
    address: String,
    onOpenToday: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenDebug: () -> Unit,
    onDisconnected: () -> Unit,
    viewModel: DeviceViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val now = rememberNowMillis()
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { ev ->
            when (ev) {
                is DeviceEvent.Error -> snackbar.showSnackbar(ev.message)
                is DeviceEvent.Info -> snackbar.showSnackbar(ev.message)
            }
        }
    }
    LaunchedEffect(state.address) {
        if (state.address != null && state.address != address) onDisconnected()
    }
    // While the device reports `connecting`, poll the config so the user sees the state progress.
    val wifiState = state.config?.config?.wifi?.wifiState
    LaunchedEffect(wifiState, state.ready) {
        if (wifiState == WifiState.CONNECTING && state.ready) {
            repeat(20) {
                delay(3_000)
                viewModel.refreshConfig()
            }
        }
    }

    var confirmReboot by remember { mutableStateOf(false) }
    var confirmRotate by remember { mutableStateOf(false) }
    var confirmBonds by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Device", style = MaterialTheme.typography.titleLarge)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TransportChip(state.transport)
                            Text(state.name ?: address, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshConfig() }, enabled = state.ready && state.isV2) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh configuration")
                    }
                    if (state.advancedMode) {
                        IconButton(onClick = onOpenDebug) { Icon(Icons.Rounded.BugReport, contentDescription = "Debug") }
                    }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
                    IconButton(onClick = { viewModel.disconnect(); onDisconnected() }) {
                        Icon(Icons.Rounded.LinkOff, contentDescription = "Disconnect")
                    }
                },
            )
        },
        bottomBar = {
            DeviceBottomBar(DeviceTab.DEVICE) {
                when (it) {
                    DeviceTab.TODAY -> onOpenToday()
                    DeviceTab.FILES -> onOpenFiles()
                    DeviceTab.DEVICE -> Unit
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            if (!state.ready) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    ConnectionBanner(state.connection)
                    if (state.connection is ConnectionState.Failed || state.connection is ConnectionState.Disconnected) {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { viewModel.reconnect() }, modifier = Modifier.fillMaxWidth()) { Text("Reconnect") }
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "info") { DeviceInfoCard(state, now) }
                if (state.info != null && !state.isV2) {
                    item(key = "v1") {
                        SectionCard("configuration") {
                            Text("This logger speaks protocol v1. Pairing, Wi-Fi and LAN settings need firmware with protocol v2; file sync works as before.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (state.isV2) {
                    item(key = "pairing") {
                        PairingCard(state, onSave = viewModel::setPairing, onReboot = { confirmReboot = true })
                    }
                    item(key = "wifi") {
                        WifiCard(
                            state = state,
                            onToggleWifi = viewModel::setWifiOn,
                            onScan = viewModel::scanWifi,
                            onClearScan = viewModel::clearScan,
                            onJoin = viewModel::joinWifi,
                            onRefresh = viewModel::refreshConfig,
                        )
                    }
                    item(key = "lan") {
                        LanCard(
                            state = state,
                            onToggleLan = viewModel::setLanOn,
                            onFetchToken = viewModel::fetchToken,
                            onRotateToken = { confirmRotate = true },
                            onReconnectBle = viewModel::reconnectOverBluetooth,
                        )
                    }
                    item(key = "bonds") {
                        SectionCard("bonded phones") {
                            StatLine("bonds stored on the device", state.config?.config?.ble?.bonds?.toString() ?: "—")
                            InfoNote("The device keeps up to 3 bonded phones and evicts the oldest. Forgetting all bonds also forgets this phone: the next Bluetooth connection pairs again.")
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { confirmBonds = true }, enabled = state.canEdit) { Text("Forget all bonds") }
                        }
                    }
                }
            }
        }
    }

    if (showSettings) SettingsSheet(onDismiss = { showSettings = false })

    if (confirmReboot) {
        AlertDialog(
            onDismissRequest = { confirmReboot = false },
            icon = { Icon(Icons.Rounded.RestartAlt, contentDescription = null) },
            title = { Text("Reboot device") },
            text = {
                Text(
                    "The logger finalizes the rows it holds in RAM into a short Parquet file, then restarts. " +
                        "No rows are lost. Pending Bluetooth pairing changes take effect on this boot. " +
                        "The app disconnects and reconnects automatically."
                )
            },
            confirmButton = { TextButton(onClick = { confirmReboot = false; viewModel.reboot() }) { Text("Reboot") } },
            dismissButton = { TextButton(onClick = { confirmReboot = false }) { Text("Cancel") } },
        )
    }
    if (confirmRotate) {
        AlertDialog(
            onDismissRequest = { confirmRotate = false },
            title = { Text("Rotate LAN token") },
            text = {
                Text(
                    "The device generates a new token and drops every LAN session. Every phone — including this one — must fetch " +
                        "the new token over Bluetooth before it can use the LAN link again. Do this if a phone that held the token was lost."
                )
            },
            confirmButton = { TextButton(onClick = { confirmRotate = false; viewModel.rotateToken() }) { Text("Rotate") } },
            dismissButton = { TextButton(onClick = { confirmRotate = false }) { Text("Cancel") } },
        )
    }
    if (confirmBonds) {
        AlertDialog(
            onDismissRequest = { confirmBonds = false },
            title = { Text("Forget all bonds") },
            text = { Text("The device forgets every paired phone, including this one. Your archive on this phone is untouched. You will type the passkey again on the next Bluetooth connection.") },
            confirmButton = { TextButton(onClick = { confirmBonds = false; viewModel.clearBonds() }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { confirmBonds = false }) { Text("Cancel") } },
        )
    }
}

// ------------------------------------------------------------------- cards

@Composable
private fun DeviceInfoCard(state: DeviceUiState, now: Long) {
    val info = state.info
    SectionCard("device") {
        StatLine("name", state.name ?: "—")
        StatLine("firmware", info?.fw ?: "—")
        StatLine("protocol", info?.proto?.let { "v$it" } ?: "—")
        StatLine("station", info?.station ?: "—")
        StatLine("device id", info?.dev ?: "—")
        StatLine("boot", info?.boot?.take(16)?.plus("…") ?: "—")
        StatLine("link", listOfNotNull(state.transport?.label, state.endpoint).joinToString(" · ").ifEmpty { "—" })
        state.config?.let { StatLine("config read", formatAgo(now - it.atEpochMs)) }
    }
}

@Composable
private fun PairingCard(state: DeviceUiState, onSave: (PairMode, String?) -> Unit, onReboot: () -> Unit) {
    val ble = state.config?.config?.ble
    val current = ble?.pairMode
    var selected by rememberSaveable(current) { mutableStateOf(current ?: PairMode.RANDOM) }
    var pin by rememberSaveable { mutableStateOf("") }
    var confirmNone by remember { mutableStateOf(false) }

    SectionCard("bluetooth pairing") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(if (current != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            Text(
                when (current) {
                    null -> if (state.config == null) "Configuration not read yet" else "Mode unknown (${ble?.pair ?: "absent"})"
                    else -> "Mode: ${current.label}"
                },
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (ble != null) {
            StatLine("display detected at first boot", if (ble.display == 1) "yes" else if (ble.display == 0) "no" else "—")
            if (current == PairMode.FIXED) StatLine("PIN", ble.pinText ?: "—")
        }
        if (ble?.isPinDefault == true && current == PairMode.FIXED) {
            Spacer(Modifier.height(8.dp))
            WarningNote("Default PIN ${ConfigPatch.DEFAULT_PIN} — change it. A known PIN gives encryption without protection against a determined neighbour.")
        }
        if (state.config?.rebootRequired == true) {
            Spacer(Modifier.height(8.dp))
            WarningNote("A Bluetooth pairing change is stored and waits for the next boot. Reboot the device to apply it.")
        }
        Spacer(Modifier.height(10.dp))
        PairMode.entries.forEach { mode ->
            Row(
                Modifier.fillMaxWidth().clickable(enabled = state.canEdit) { selected = mode }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == mode, onClick = { selected = mode }, enabled = state.canEdit)
                Column {
                    Text(mode.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        when (mode) {
                            PairMode.RANDOM -> "Fresh 6 digits per attempt, shown on the device screen. Proves physical access."
                            PairMode.FIXED -> "One 6-digit PIN you set. For boards without a display."
                            PairMode.NONE -> "Encrypted, unauthenticated, no prompt. Lab benches only."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (selected == PairMode.FIXED) {
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { v -> if (v.length <= 6 && v.all { it.isDigit() }) pin = v },
                label = { Text(if (current == PairMode.FIXED) "New PIN (6 digits, leave empty to keep)" else "PIN (6 digits)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = VisualTransformation.None,
                enabled = state.canEdit,
                modifier = Modifier.fillMaxWidth(),
                supportingText = if (pin.isNotEmpty() && pin.length != 6) ({ Text("Exactly six digits") }) else null,
                isError = pin.isNotEmpty() && pin.length != 6,
            )
        }
        InfoNote("Bluetooth pairing settings are applied at the next boot: the stack's security parameters are fixed when it starts.")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val pinOk = selected != PairMode.FIXED || pin.length == 6 || (pin.isEmpty() && current == PairMode.FIXED)
            val needsPin = selected == PairMode.FIXED && current != PairMode.FIXED && pin.length != 6
            FilledTonalButton(
                onClick = {
                    if (selected == PairMode.NONE) confirmNone = true else onSave(selected, pin.takeIf { it.length == 6 })
                },
                enabled = state.canEdit && pinOk && !needsPin && (selected != current || pin.length == 6),
                modifier = Modifier.weight(1f),
            ) { Text("Save pairing") }
            OutlinedButton(onClick = onReboot, enabled = state.canEdit, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.RestartAlt, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Reboot device")
            }
        }
    }

    if (confirmNone) {
        AlertDialog(
            onDismissRequest = { confirmNone = false },
            title = { Text("Disable the passkey?") },
            text = { Text("\"None\" pairs any phone in range without a prompt (Just Works). The link is still encrypted but anyone nearby could read your device. Use this on a lab bench only.") },
            confirmButton = { TextButton(onClick = { confirmNone = false; onSave(PairMode.NONE, null) }) { Text("Disable passkey") } },
            dismissButton = { TextButton(onClick = { confirmNone = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun WifiCard(
    state: DeviceUiState,
    onToggleWifi: (Boolean) -> Unit,
    onScan: () -> Unit,
    onClearScan: () -> Unit,
    onJoin: (ssid: String, psk: String, open: Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    val wifi = state.config?.config?.wifi
    val ws = wifi?.wifiState
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    var pickedOpen by rememberSaveable { mutableStateOf(false) }
    var psk by rememberSaveable { mutableStateOf("") }
    var showPsk by rememberSaveable { mutableStateOf(false) }
    val scan = state.wifiScan

    SectionCard(
        "wi-fi",
        trailing = {
            Switch(checked = wifi?.isOn == true, onCheckedChange = onToggleWifi, enabled = state.canEdit && wifi != null)
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(
                when (ws) {
                    WifiState.CONNECTED -> MaterialTheme.colorScheme.primary
                    WifiState.CONNECTING -> MaterialTheme.colorScheme.tertiary
                    WifiState.FAILED -> MaterialTheme.colorScheme.error
                    WifiState.OFF, null -> MaterialTheme.colorScheme.outline
                }
            )
            Text(
                when (ws) {
                    WifiState.CONNECTED -> "Connected to ${wifi.ssid ?: "?"}"
                    WifiState.CONNECTING -> "Connecting to ${wifi.ssid ?: "?"}…"
                    WifiState.FAILED -> "Could not join ${wifi.ssid ?: "?"} — wrong passphrase or no such network; retrying every 30 s"
                    WifiState.OFF -> if (wifi.isOn) "Radio on, not connected" else "Radio off"
                    null -> if (wifi == null) "No configuration read yet" else "State unknown (${wifi.state ?: "absent"})"
                },
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (ws == WifiState.CONNECTING) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(6.dp))
        StatLine("network", wifi?.ssid?.ifEmpty { "—" } ?: "—")
        StatLine("passphrase", when (wifi?.pskSet) { 1 -> "stored on the device (never shown)"; 0 -> "none (open network)"; else -> "—" })
        StatLine("ip", wifi?.ip?.ifEmpty { "—" } ?: "—")
        StatLine("rssi", wifi?.rssi?.takeIf { it != 0 }?.let { "$it dBm" } ?: "—")
        StatLine("mac", wifi?.mac ?: "—")

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onScan, enabled = state.canEdit && scan !is WifiScanState.Scanning, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.WifiFind, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Scan networks")
            }
            OutlinedButton(onClick = onRefresh, enabled = state.ready && state.isV2, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Refresh, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Refresh state")
            }
        }
        if (state.transport == TransportKind.LAN) {
            InfoNote("You are connected over the LAN: changing Wi-Fi settings will usually drop this session. Provision over Bluetooth when possible.")
        }

        when (scan) {
            WifiScanState.Idle -> Unit
            WifiScanState.Scanning -> {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Scanning… the device needs 2–4 s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            is WifiScanState.Failed -> {
                Spacer(Modifier.height(10.dp))
                WarningNote(scan.message)
            }
            is WifiScanState.Done -> {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${scan.accessPoints.size} network(s), strongest first", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onClearScan(); picked = null }) { Text("Hide") }
                }
                if (scan.accessPoints.isEmpty()) {
                    Text("No networks found (hidden networks are not listed).", style = MaterialTheme.typography.bodySmall)
                }
                scan.accessPoints.forEach { ap ->
                    AccessPointRow(ap, selected = picked == ap.ssid) {
                        picked = ap.ssid
                        pickedOpen = !ap.wifiAuth.secured
                        psk = ""
                    }
                }
            }
        }

        picked?.let { ssid ->
            Spacer(Modifier.height(12.dp))
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(14.dp)) {
                    Text("Join $ssid", style = MaterialTheme.typography.titleSmall)
                    if (pickedOpen) {
                        Text("Open network — no passphrase.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = psk,
                            onValueChange = { psk = it },
                            label = { Text("Passphrase (≤ 63 bytes)") },
                            singleLine = true,
                            visualTransformation = if (showPsk) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { showPsk = !showPsk }) {
                                    Icon(if (showPsk) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = if (showPsk) "Hide passphrase" else "Show passphrase")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.canEdit,
                        )
                        Text(
                            "Stored on the device in clear (its flash is not encrypted, by project rule). It is never echoed back.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onJoin(ssid, psk, pickedOpen); picked = null; psk = "" },
                            enabled = state.canEdit && (pickedOpen || psk.toByteArray().size in 8..63),
                        ) {
                            Icon(Icons.Rounded.Check, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Save and connect")
                        }
                        TextButton(onClick = { picked = null; psk = "" }) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessPointRow(ap: Frame.WifiAp, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (ap.wifiAuth.secured) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                contentDescription = if (ap.wifiAuth.secured) "secured" else "open",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(ap.ssid.ifEmpty { "(no name)" }, style = MaterialTheme.typography.bodyLarge)
                Text("${ap.wifiAuth.label} · channel ${ap.channel}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RssiBars(ap.rssi)
        }
    }
}

@Composable
private fun RssiBars(rssi: Int) {
    val bars = when {
        rssi >= -55 -> 4
        rssi >= -67 -> 3
        rssi >= -80 -> 2
        else -> 1
    }
    Column(horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (i in 1..4) {
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = if (i <= bars) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.width(4.dp).height((5 + i * 3).dp),
                ) {}
            }
        }
        Text("$rssi dBm", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LanCard(
    state: DeviceUiState,
    onToggleLan: (Boolean) -> Unit,
    onFetchToken: () -> Unit,
    onRotateToken: () -> Unit,
    onReconnectBle: () -> Unit,
) {
    val lan = state.config?.config?.lan
    val perm = rememberLanPermission()
    val hasToken = state.known?.hasLanToken == true

    SectionCard(
        "lan sync",
        trailing = {
            Switch(checked = lan?.isOn == true, onCheckedChange = onToggleLan, enabled = state.canEdit && lan != null)
        },
    ) {
        Text(
            "The same protocol over one TCP socket on your own network, discovered with mDNS. Faster than Bluetooth; " +
                "the stream is not encrypted, so it carries only what the device would show on its screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        StatLine("server", when (lan?.isOn) { true -> "on (while Wi-Fi is connected)"; false -> "off"; null -> "—" })
        StatLine("mDNS host", lan?.host?.let { "$it.local" } ?: "—")
        StatLine("port", lan?.port?.toString() ?: "—")
        StatLine("clients now", lan?.clients?.toString() ?: "—")

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(
                when {
                    !perm.granted -> "Local network access not allowed on this phone"
                    state.onLan != null -> "Seen on this network at ${state.onLan.host?.hostAddress}:${state.onLan.port}"
                    state.lanDiscoveryError != null -> "Discovery: ${state.lanDiscoveryError}"
                    else -> "Not seen on this network right now"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (!perm.granted && perm.requestable) {
            InfoNote(
                if (perm.deniedOnce) {
                    "Without it the app cannot browse for the logger or open a socket to it; Bluetooth keeps working. You can allow it later in system settings."
                } else {
                    "Android 16+ asks before an app may talk to devices on your local network. AQ Sync only ever connects to a logger it found via mDNS."
                }
            )
            Spacer(Modifier.height(6.dp))
            FilledTonalButton(onClick = perm.request) { Text("Allow local network") }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(if (hasToken) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            Text(
                if (hasToken) "This phone holds a LAN token for this logger" else "No LAN token on this phone yet",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        InfoNote(
            "The token authenticates this phone on the LAN. It can only be fetched over Bluetooth (the device answers " +
                "\"not allowed on this link\" over LAN), is stored in app-private storage and is never displayed or logged."
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.overBle) {
                FilledTonalButton(onClick = onFetchToken, enabled = state.canEdit, modifier = Modifier.weight(1f)) {
                    Text(if (hasToken) "Fetch token again" else "Fetch token")
                }
            } else {
                FilledTonalButton(onClick = onReconnectBle, enabled = state.ready && !state.busy, modifier = Modifier.weight(1f)) {
                    Text("Reconnect over Bluetooth")
                }
            }
            OutlinedButton(onClick = onRotateToken, enabled = state.canEdit, modifier = Modifier.weight(1f)) { Text("Rotate token") }
        }
        if (!state.overBle) InfoNote("Fetching the token is Bluetooth-only by contract.")
    }
}
