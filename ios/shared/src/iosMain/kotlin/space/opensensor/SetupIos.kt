package space.opensensor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import space.opensensor.device.DeviceConfigAction
import space.opensensor.device.DeviceConfigController
import space.opensensor.device.LanAccess
import space.opensensor.device.onAction
import space.opensensor.setup.SetupCandidate
import space.opensensor.setup.SetupController
import space.opensensor.setup.SetupLink
import space.opensensor.setup.SetupPort
import space.opensensor.setup.SetupScreen

/**
 * The iOS [SetupPort]. Find runs the My devices scan, Pair opens the sensor through Swift like a tap
 * on My devices, and the known record is written by [DevicesBridge.sensorOpened] only after INFO
 * passed the identity check. iOS reports a refused pairing and an unreachable sensor the same way, so
 * both show as [SetupLink.Unreachable], as does a connection that closed before INFO passed. INFO below proto 3 closes before that check and shows as
 * [SetupLink.UpdateFirmware].
 */
private class IosSetupPort(private val devices: DevicesBridge, private val openSensor: (String) -> Unit) : SetupPort {
    private val state get() = devices.state

    override val nearby: Flow<List<SetupCandidate>> = combine(snapshotFlow { state.devices }, devices.known) { seen, known ->
        val added = known.mapTo(HashSet()) { it.peripheral.lowercase() }
        seen.sortedByDescending { it.rssi }.map { SetupCandidate(it.id, it.name, added = it.id.lowercase() in added) }
    }

    override fun startFind() = devices.startFind()

    override fun stopFind() = devices.stopFind()

    override fun connect(id: String, name: String?) {
        if (state.selectedId.equals(id, ignoreCase = true) && state.connected) return
        openSensor(id)
    }

    override fun link(id: String): Flow<SetupLink> = snapshotFlow {
        val mine = state.selectedId.equals(id, ignoreCase = true)
        val info = state.info
        when {
            state.bluetoothPoweredOff -> SetupLink.NoBluetooth
            state.outdatedId.equals(id, ignoreCase = true) -> SetupLink.UpdateFirmware
            state.failedId.equals(id, ignoreCase = true) -> SetupLink.Unreachable
            state.closedId.equals(id, ignoreCase = true) -> SetupLink.Unreachable
            mine && state.connected && info != null -> SetupLink.Ready(info.proto)
            else -> SetupLink.Connecting
        }
    }.distinctUntilChanged()

    override suspend fun nickname(id: String): String? = settingsRepository.nicknames.first()[id.lowercase()]

    override suspend fun saveNickname(id: String, nickname: String?) = settingsRepository.setNickname(id, nickname)

    override suspend fun setPending(id: String, pending: Boolean) = settingsRepository.setSetupPending(id, pending)
}

/** Holds one wizard run for its entry, cleared when the entry leaves the back stack. */
private class SetupHolder(devices: DevicesBridge, openSensor: (String) -> Unit, resume: String?, pick: SetupCandidate?) : ViewModel() {
    val controller = SetupController(
        viewModelScope,
        IosSetupPort(devices, openSensor),
        resumeId = resume,
        resumeName = resume?.let { id -> devices.known.value.firstOrNull { it.peripheral.equals(id, ignoreCase = true) }?.name },
        pick = pick,
    )
}

/** Holds the Location and Wi-Fi forms of the picked sensor, the same source the Device page uses. */
private class SetupConfigHolder(address: String, devices: DevicesBridge, links: DeviceConfigLinks) : ViewModel() {
    val controller = DeviceConfigController(
        scope = viewModelScope,
        source = IosDeviceConfigSource(address, devices.state, devices, links, viewModelScope, IosArchive.places),
        detailLevel = settingsRepository.detailLevel,
        readOnReady = true,
    )
}

/**
 * The Add sensor wizard on iOS. Bluetooth is asked for from Look for sensors. Local network is asked
 * for by browsing for sensors from the Wi-Fi page, the iOS prompt.
 */
@Composable
internal fun SetupEntry(
    resume: String?,
    pick: SetupCandidate?,
    devices: DevicesBridge,
    links: DeviceConfigLinks,
    requestBluetooth: () -> Unit,
    openSensor: (String) -> Unit,
    onClose: () -> Unit,
    onOpenSensor: (String) -> Unit,
) {
    val controller = viewModel(key = "setup-$resume-${pick?.id}") { SetupHolder(devices, openSensor, resume, pick) }.controller
    val bluetooth = rememberIosBluetoothAccess(devices.state, requestBluetooth)
    val s by controller.state.collectAsState()
    val address = s.selected?.id.takeIf { s.paired }
    val config = address?.let { a -> viewModel(key = "setup-config-$a") { SetupConfigHolder(a, devices, links) }.controller }
    SetupScreen(
        controller = controller,
        config = config,
        bluetooth = bluetooth,
        lanAccess = LanAccess(granted = !devices.state.lanAccessDenied, requestable = true),
        onConfigAction = { action ->
            when (action) {
                DeviceConfigAction.RequestLanAccess -> links.scanLan()
                DeviceConfigAction.ScanSticker -> Unit
                DeviceConfigAction.ReconnectOverBluetooth -> bluetooth.run { config?.onAction(action) }
                else -> config?.onAction(action)
            }
        },
        onClose = onClose,
        onOpenSensor = onOpenSensor,
    )
}
