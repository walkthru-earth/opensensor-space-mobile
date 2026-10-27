package space.opensensor.ui.setup

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import space.opensensor.data.DeviceConnectionManager
import space.opensensor.data.ble.BleConnectionFailure
import space.opensensor.data.ble.BleScanner
import space.opensensor.data.ble.BluetoothStatus
import space.opensensor.data.ble.ConnectionState
import space.opensensor.data.local.database.KnownDeviceDao
import space.opensensor.settings.SettingsRepository
import space.opensensor.setup.SetupCandidate
import space.opensensor.setup.SetupController
import space.opensensor.setup.SetupLink
import space.opensensor.setup.SetupPort
import space.opensensor.ui.permissions.hasBluetoothPermissions
import space.opensensor.ui.scan.NearbyAdverts

/**
 * The Android [SetupPort] behind the shared wizard. Find scans like the Devices list, Pair opens the
 * same [DeviceConnectionManager] link the Device page uses, so the known row is written by the link
 * owner only once the identity check passed. The nickname and the pending flag stay in
 * [SettingsRepository] on the phone.
 */
@HiltViewModel(assistedFactory = SetupViewModel.Factory::class)
class SetupViewModel @AssistedInject constructor(
    @ApplicationContext private val context: Context,
    private val manager: DeviceConnectionManager,
    private val knownDeviceDao: KnownDeviceDao,
    private val settings: SettingsRepository,
    @Assisted("resume") resume: String?,
    @Assisted("pick") pick: String?,
    @Assisted("pickName") pickName: String?,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(@Assisted("resume") resume: String?, @Assisted("pick") pick: String?, @Assisted("pickName") pickName: String?): SetupViewModel
    }

    private val adverts = NearbyAdverts(touch = { knownDeviceDao.touch(it.address, it.name, it.lastSeenEpochMs) })
    private var scanJob: Job? = null

    /** The failure each accepted connect replaced, by lowercase id, until its [SetupPort.link] skips it. */
    private val staleFailures = mutableMapOf<String, ConnectionState.Failed>()

    private val port = object : SetupPort {
        override val nearby: Flow<List<SetupCandidate>> = combine(adverts.seen, knownDeviceDao.observeAll()) { seen, known ->
            val added = known.mapTo(HashSet()) { it.address }
            seen.values.sortedByDescending { it.rssi }.map { SetupCandidate(it.address, it.name, added = it.address in added) }
        }

        override fun startFind() {
            if (scanJob?.isActive == true) return
            if (!hasBluetoothPermissions(context) || !BluetoothStatus.isEnabled(context)) return
            scanJob = viewModelScope.launch {
                try {
                    adverts.collect(BleScanner.scan(context))?.let { ev -> Log.w(TAG, "scan failed (${ev.errorCode})") }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "scan failed (${e::class.simpleName})")
                }
            }
        }

        override fun stopFind() {
            scanJob?.cancel()
            scanJob = null
        }

        override fun connect(id: String, name: String?) {
            if (!bluetoothReady()) return
            val facade = manager.controller(id)
            val state = facade.connectionState.value
            if (state.isReady || state.isBusy) return
            if (facade.connect(id, name)) {
                if (state is ConnectionState.Failed) staleFailures[id.lowercase()] = state else staleFailures.remove(id.lowercase())
            }
        }

        override fun link(id: String): Flow<SetupLink> = flow {
            if (!bluetoothReady()) return@flow emit(SetupLink.NoBluetooth)
            val facade = manager.controller(id)
            val stale = staleFailures.remove(id.lowercase())
            var bonding = false
            emitAll(
                combine(facade.connectionState.skipStale(stale), facade.info) { state, info ->
                    if (state is ConnectionState.Bonding) bonding = true
                    setupLink(state, info?.proto, info != null, bonding)
                }.distinctUntilChanged(),
            )
        }

        override suspend fun nickname(id: String): String? = settings.nicknames.first()[id.lowercase()]

        override suspend fun saveNickname(id: String, nickname: String?) = settings.setNickname(id, nickname)

        override suspend fun setPending(id: String, pending: Boolean) = settings.setSetupPending(id, pending)
    }

    private fun bluetoothReady() = hasBluetoothPermissions(context) && BluetoothStatus.isEnabled(context)

    /** A resumed run shows the advertised name the link owner already knows. */
    val controller = SetupController(
        viewModelScope,
        port,
        resumeId = resume,
        resumeName = resume?.let { manager.controller(it).currentDisplayName },
        pick = pick?.let { SetupCandidate(it, pickName, added = false) },
    )

    override fun onCleared() {
        scanJob?.cancel()
    }

    private companion object {
        const val TAG = "SetupViewModel"
    }
}

/**
 * Drops the leading [stale] failure the shared state still holds after a retry, until the state
 * moves on. The same instance, never an equal one, so a new failure with the same words still shows.
 */
internal fun Flow<ConnectionState>.skipStale(stale: ConnectionState.Failed?): Flow<ConnectionState> =
    if (stale == null) this else dropWhile { it === stale }

/**
 * What the wizard sees of one Android link. The proto gate fails the link before INFO is kept, so old
 * firmware shows as [SetupLink.UpdateFirmware] rather than as an unreachable sensor.
 */
internal fun setupLink(state: ConnectionState, proto: Int?, hasInfo: Boolean, bonding: Boolean): SetupLink = when (state) {
    is ConnectionState.Ready -> if (!hasInfo) SetupLink.Connecting else SetupLink.Ready(proto)
    is ConnectionState.Failed -> when {
        BleConnectionFailure.needsFirmwareUpdate(state) -> SetupLink.UpdateFirmware
        bonding || BleConnectionFailure.needsPairingRepair(state) -> SetupLink.PairingRefused
        else -> SetupLink.Unreachable
    }
    else -> SetupLink.Connecting
}
