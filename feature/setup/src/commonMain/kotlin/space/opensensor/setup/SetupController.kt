package space.opensensor.setup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.opensensor.data.protocol.DeviceGatt

/** One nearby sensor on Find. [added] rows are already known and start a re-run. */
data class SetupCandidate(val id: String, val name: String?, val added: Boolean)

/** Where the link to the picked sensor is, as the platform sees it. */
sealed interface SetupLink {
    data object Idle : SetupLink

    data object Connecting : SetupLink

    /** INFO arrived and the identity check passed, so the platform has written the known row. */
    data class Ready(val protocol: Int?) : SetupLink

    /** The OS pairing was cancelled or the PIN was wrong. Both platforms report the two the same way. */
    data object PairingRefused : SetupLink

    data object Unreachable : SetupLink

    /** The link owner refused the sensor's INFO for a `proto` below 3, before any known row. */
    data object UpdateFirmware : SetupLink

    /** Bluetooth is off or not allowed. */
    data object NoBluetooth : SetupLink
}

/** One message per failed phase. */
enum class SetupFailure { UPDATE_FIRMWARE, PAIRING, UNREACHABLE, BLUETOOTH }

/**
 * What the wizard needs from a platform. The nickname and the setup flag stay on the phone
 * (`SettingsRepository`). Nothing here sends a frame, the Location and Wi-Fi pages go through
 * `DeviceConfigController`.
 */
interface SetupPort {
    val nearby: Flow<List<SetupCandidate>>

    fun startFind()

    fun stopFind()

    /** Opens the link to [id]. Pairing shows in the OS dialog. */
    fun connect(id: String, name: String?)

    fun link(id: String): Flow<SetupLink>

    suspend fun nickname(id: String): String?

    suspend fun saveNickname(id: String, nickname: String?)

    suspend fun setPending(id: String, pending: Boolean)
}

data class SetupUiState(
    val page: SetupStep,
    val candidates: List<SetupCandidate> = emptyList(),
    val finding: Boolean = false,
    val selected: SetupCandidate? = null,
    val pairing: Boolean = false,
    val paired: Boolean = false,
    val failure: SetupFailure? = null,
    val nickname: String = "",
    /** The Thing holds a saved location that satisfies the required Location page. */
    val located: Boolean = false,
    /** Whether the sensor has the owner site keys. Null until it answered, false leaves Site out. */
    val siteSupported: Boolean? = null,
    /** Next on Site is saving the site. Skip waits for it, so the save and a skip never race. */
    val siteSaving: Boolean = false,
    val askClose: Boolean = false,
    val closed: Boolean = false,
) {
    val nicknameValid: Boolean get() = nickname.trim().length <= SetupController.NICKNAME_MAX

    /** Next is on unless this is a required page whose fact is not saved yet. */
    val canAdvance: Boolean get() = nicknameValid && (!page.required || satisfied(page))

    /** Whether [step] is walked. Site is left out once the sensor says it has no site keys. */
    fun shows(step: SetupStep): Boolean = step != SetupStep.Site || siteSupported != false

    fun satisfied(step: SetupStep): Boolean = when (step) {
        is SetupStep.Location -> located
        else -> true
    }

    /** Display name on Done, `nickname ?: name`. */
    val displayName: String? get() = nickname.trim().ifEmpty { null } ?: selected?.name
}

/**
 * Walks [steps] for one sensor. Back goes one page but stops at Name once paired, since unpairing
 * belongs to settings. Closing after pairing asks first, and the sensor stays added with setup
 * pending. Nothing about the run is persisted besides the nickname and that flag, so a resumed run
 * ([resumeId]) opens at the first required page, Location for the board, and reconnects silently.
 * A [pick] is a new sensor tapped outside Find, paired at once as if chosen there, so Name still
 * comes first. With [offer] the pick is a sensor that asked for setup by itself (#272). It is only
 * listed first on Find, as pairing stays the owner's tap. Done, which clears the flag, is reached
 * only once every required page is satisfied.
 *
 * [opened] tells the shared setup watch that the wizard ran for a sensor, when it opens on a resume
 * or an offer, whenever it pairs and again when it closes, so it does not open by itself again this
 * foreground session. The close counts too, as the session may have ended while the wizard showed.
 */
class SetupController(
    private val scope: CoroutineScope,
    private val port: SetupPort,
    val steps: List<SetupStep> = SetupFlows.BOARD,
    resumeId: String? = null,
    resumeName: String? = null,
    pick: SetupCandidate? = null,
    offer: Boolean = false,
    private val opened: (String) -> Unit = {},
) {
    private val pages: List<SetupStep> = steps.filter { it.isPage }
    private val _state = MutableStateFlow(SetupUiState(page = pages.first()))
    val state: StateFlow<SetupUiState> = _state.asStateFlow()
    private var linkJob: Job? = null
    private var nearbyJob: Job? = null
    private val offered: SetupCandidate? = pick?.takeIf { offer }

    init {
        if (resumeId != null) {
            val candidate = SetupCandidate(resumeId, resumeName, added = true)
            val page = pages.firstOrNull { it.required } ?: SetupStep.Name
            _state.update { it.copy(page = page, selected = candidate, paired = true) }
            pair(candidate, silent = true)
        } else {
            offered?.let { o ->
                opened(o.id)
                _state.update { it.copy(candidates = listOf(o)) }
            }
            nearbyJob = scope.launch { port.nearby.collect { list -> _state.update { it.copy(candidates = withOffer(list)) } } }
            if (pick != null && offered == null) pair(pick, silent = false)
        }
    }

    /** The offered sensor stays listed first until the scan on Find hears it too. */
    private fun withOffer(list: List<SetupCandidate>): List<SetupCandidate> {
        val o = offered ?: return list
        return if (list.any { it.id.equals(o.id, ignoreCase = true) }) list else listOf(o) + list
    }

    /** Starts the scan, after the platform granted the access [steps] binds to Find. */
    fun find() {
        if (_state.value.page != SetupStep.Find) return
        _state.update { it.copy(finding = true, failure = null) }
        port.startFind()
    }

    /** Picks [candidate] on Find and pairs with it. An added row runs setup again. */
    fun choose(candidate: SetupCandidate) {
        val s = _state.value
        if (s.page != SetupStep.Find || s.pairing) return
        pair(candidate, silent = false)
    }

    private fun pair(candidate: SetupCandidate, silent: Boolean) {
        opened(candidate.id)
        port.stopFind()
        _state.update { it.copy(finding = false, selected = candidate, pairing = !silent, failure = null) }
        linkJob?.cancel()
        port.connect(candidate.id, candidate.name)
        linkJob = scope.launch {
            port.link(candidate.id).collect { link -> onLink(candidate, link, silent) }
        }
    }

    private suspend fun onLink(candidate: SetupCandidate, link: SetupLink, silent: Boolean) {
        val failure = when (link) {
            SetupLink.Idle, SetupLink.Connecting -> return
            is SetupLink.Ready -> if ((link.protocol ?: 0) < DeviceGatt.PROTOCOL_VERSION) SetupFailure.UPDATE_FIRMWARE else null
            SetupLink.PairingRefused -> SetupFailure.PAIRING
            SetupLink.Unreachable -> SetupFailure.UNREACHABLE
            SetupLink.UpdateFirmware -> SetupFailure.UPDATE_FIRMWARE
            SetupLink.NoBluetooth -> SetupFailure.BLUETOOTH
        }
        // A resumed run is already on Name and the forms wait for the link, so a silent failure stays quiet.
        if (silent) return
        if (failure != null) {
            _state.update { it.copy(pairing = false, failure = failure) }
            linkJob?.cancel()
            return
        }
        if (_state.value.paired) return
        port.setPending(candidate.id, true)
        val nickname = port.nickname(candidate.id).orEmpty()
        _state.update { it.copy(pairing = false, paired = true, page = SetupStep.Name, nickname = nickname) }
    }

    fun setNickname(value: String) {
        _state.update { it.copy(nickname = value.replace('\n', ' ').take(NICKNAME_MAX + 1)) }
    }

    /** Whether the Thing holds a saved location, from the Location form's confirmed read or write. */
    fun onLocated(located: Boolean) {
        _state.update { it.copy(located = located) }
    }

    /**
     * Whether the sensor has the owner site keys, from the site form's read of page 5. When it has
     * none while Site shows, the wizard moves on, as if Site were never in the list.
     */
    fun onSiteSupport(supported: Boolean?) {
        _state.update { it.copy(siteSupported = supported) }
        if (supported == false && _state.value.page == SetupStep.Site) advance()
    }

    /** The next page. On Name it saves the nickname first, a blank one shows the advertised name. */
    fun next() {
        val s = _state.value
        if (!s.canAdvance) return
        when (s.page) {
            SetupStep.Find -> return
            SetupStep.Name -> {
                val id = s.selected?.id ?: return
                scope.launch {
                    port.saveNickname(id, s.nickname.trim().ifEmpty { null })
                    advance()
                }
                return
            }
            SetupStep.Done -> return finish()
            else -> advance()
        }
    }

    /**
     * Next on Site with changed values. Runs [save], the explicit site write, and moves on only when
     * it reports the board confirmed every value. A refusal or an unconfirmed readback stays on Site
     * with its reason. When Back left Site while the save ran, its result never moves the wizard.
     */
    fun saveSiteThenNext(save: suspend () -> Boolean) {
        val s = _state.value
        if (s.page != SetupStep.Site || s.siteSaving) return
        _state.update { it.copy(siteSaving = true) }
        scope.launch {
            val saved = try {
                save()
            } finally {
                _state.update { it.copy(siteSaving = false) }
            }
            if (saved && _state.value.page == SetupStep.Site) advance()
        }
    }

    /** Skips a skippable page without writing anything. Ignored while a site save runs. */
    fun skip() {
        val s = _state.value
        if (s.page.skippable && !s.siteSaving) advance()
    }

    private fun advance() {
        val s = _state.value
        val next = pages.drop(pages.indexOf(s.page) + 1).firstOrNull(s::shows) ?: return
        if (next == SetupStep.Done && pages.any { it.required && !s.satisfied(it) }) return
        _state.update { it.copy(page = next) }
        if (next == SetupStep.Done) s.selected?.let { scope.launch { port.setPending(it.id, false) } }
    }

    /** One page back. On Name after pairing, and on Find, it asks to close instead. */
    fun back() {
        val s = _state.value
        val at = pages.indexOf(s.page)
        when {
            s.page == SetupStep.Done -> finish()
            at <= 0 || (s.page == SetupStep.Name && s.paired) -> requestClose()
            else -> pages.take(at).lastOrNull(s::shows)?.let { page -> _state.update { it.copy(page = page) } }
        }
    }

    /** Closes at once before pairing or on Done. After pairing it asks first. */
    fun requestClose() {
        val s = _state.value
        if (s.paired && s.page != SetupStep.Done) _state.update { it.copy(askClose = true) } else finish()
    }

    fun dismissClose() {
        _state.update { it.copy(askClose = false) }
    }

    /** Leaves the wizard. A paired sensor stays added, with setup pending unless Done was reached. */
    fun finish() {
        (_state.value.selected ?: offered)?.let { opened(it.id) }
        port.stopFind()
        linkJob?.cancel()
        nearbyJob?.cancel()
        _state.update { it.copy(askClose = false, closed = true, finding = false) }
    }

    /** Back on Find after a failure. */
    fun retry() {
        linkJob?.cancel()
        _state.update { it.copy(failure = null, pairing = false, selected = if (it.paired) it.selected else null) }
    }

    companion object {
        const val NICKNAME_MAX = 32
    }
}
