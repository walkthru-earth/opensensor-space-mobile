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
import space.opensensor.data.sync.PlanStep
import space.opensensor.data.sync.PlanStepKind
import space.opensensor.data.sync.SetupPlan
import space.opensensor.model.StepId
import space.opensensor.settings.DetailLevel

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
 * What the wizard needs from a platform. The nickname stays on the phone (`SettingsRepository`).
 * Nothing here sends a frame, every step page and its ack go through `DeviceConfigController`.
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
}

/**
 * The wizard's page. [Find] lists nearby sensors and pairs the picked one. [Loading] waits for the
 * paired sensor's page 1, which the [SetupPlan] needs. [Step] is one step of that plan, [Done] the summary.
 */
sealed interface SetupPage {
    data object Find : SetupPage

    data object Loading : SetupPage

    data class Step(val id: StepId) : SetupPage

    data object Done : SetupPage
}

data class SetupUiState(
    val page: SetupPage = SetupPage.Find,
    val candidates: List<SetupCandidate> = emptyList(),
    val finding: Boolean = false,
    val selected: SetupCandidate? = null,
    val pairing: Boolean = false,
    val paired: Boolean = false,
    val failure: SetupFailure? = null,
    val nickname: String = "",
    /** The paired Thing's setup steps, null until its page 1 was read. */
    val plan: SetupPlan? = null,
    /**
     * The step pages in walk order. It keeps a step that left the plan, a Location dropped once the
     * Thing moves, so Next and Back still pass it.
     */
    val order: List<StepId> = emptyList(),
    /** Planned steps without a page at this level. Their bits ride along with every ack. */
    val hidden: List<StepId> = emptyList(),
    /** Next is saving the page or writing its ack. Skip waits for it, Back leaves it and the page stays. */
    val saving: Boolean = false,
    /** The sensor did not confirm this page's step bit, the page says so and offers Retry. */
    val ackFailed: Boolean = false,
    val askClose: Boolean = false,
    val closed: Boolean = false,
) {
    val nicknameValid: Boolean get() = nickname.trim().length <= SetupController.NICKNAME_MAX

    /** The plan step of this page, null off a step or once the step left the plan. */
    val step: PlanStep? get() = (page as? SetupPage.Step)?.let { plan?.step(it.id) }

    /**
     * Next is on unless the page is saving, the nickname is too long, the step is required and its fact
     * is not on the Thing yet, or the step needs a newer app. A step that left the plan passes.
     */
    val canAdvance: Boolean get() = when (val p = page) {
        is SetupPage.Step -> !saving && (p.id != StepId.NAME || nicknameValid) && step.let { it == null || (it.kind != PlanStepKind.UNSUPPORTED && (!it.required || it.satisfied)) }
        SetupPage.Done -> !saving
        SetupPage.Find, SetupPage.Loading -> false
    }

    /** A board step's template label in [language], null on a page that keeps its own title. */
    fun title(language: String): String? = step?.label?.get(language)

    /** Skip shows on an optional step, and is off while Next saves. */
    val canSkip: Boolean get() = step?.skippable == true && !saving

    /** Done is reached only once every required step holds its fact. */
    val doneAllowed: Boolean get() = plan?.steps.orEmpty().none { it.required && (!it.satisfied || it.kind == PlanStepKind.UNSUPPORTED) }

    /** Back shows on every step but the first, Done closes instead. */
    val canGoBack: Boolean get() = (page as? SetupPage.Step)?.let { order.indexOf(it.id) > 0 } == true

    /** Display name on Done, `nickname ?: name`. */
    val displayName: String? get() = nickname.trim().ifEmpty { null } ?: selected?.name

    /** Whether [candidate] is the sensor pairing now. Ids match ignoring case, as a scan may spell one in lower case. */
    fun pairingWith(candidate: SetupCandidate): Boolean = pairing && selected?.id.equals(candidate.id, ignoreCase = true)
}

/**
 * Walks the [SetupPlan] of one sensor, the board template's steps merged with its page 1 record and the
 * app floors. Find pairs the picked sensor, then the wizard waits on Loading for the plan
 * ([onPlan]) and opens the first step not done, or the first step when all are. Back goes one step but
 * stops at the first, since unpairing belongs to settings. Closing after pairing asks first, and the
 * sensor stays added.
 *
 * Next saves the page when it has something to save, then sets the step's bit in `thing.setup_ack`
 * through `ack`, and moves on only once both are confirmed. Skip on an optional step sets the bit too.
 * Lan never has a page, pairing and bonds only from Detailed, and the bits of those hidden steps are
 * sent with every ack. Done is reached only once every required step holds its fact, and its Close
 * sets the hidden bits still clear.
 *
 * A resumed run ([resumeId]) reconnects silently and opens where the plan says. A [pick] is a new
 * sensor tapped outside Find, paired at once as if chosen there. Find lists it first while it pairs,
 * even before a scan hears it. With [offer] the pick is a sensor that asked for setup by itself
 * (#272). It is only listed first on Find, as pairing stays the owner's tap.
 *
 * [opened] tells the shared setup watch that the wizard ran for a sensor, when it opens on a resume
 * or an offer, whenever it pairs and again when it closes, so it does not open by itself again this
 * foreground session. The close counts too, as the session may have ended while the wizard showed.
 */
class SetupController(
    private val scope: CoroutineScope,
    private val port: SetupPort,
    resumeId: String? = null,
    resumeName: String? = null,
    pick: SetupCandidate? = null,
    offer: Boolean = false,
    private val opened: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()
    private var linkJob: Job? = null
    private var nearbyJob: Job? = null
    private val offered: SetupCandidate? = pick?.takeIf { offer }

    /** A [pick] is listed on Find even before a scan hears it, so its row shows while it pairs. */
    private val picked: SetupCandidate? = pick

    init {
        if (resumeId != null) {
            val candidate = SetupCandidate(resumeId, resumeName, added = true)
            _state.update { it.copy(page = SetupPage.Loading, selected = candidate, paired = true) }
            pair(candidate, silent = true)
            scope.launch { port.nickname(resumeId)?.let { nickname -> _state.update { it.copy(nickname = nickname) } } }
        } else {
            offered?.let { opened(it.id) }
            picked?.let { p -> _state.update { it.copy(candidates = listOf(p)) } }
            nearbyJob = scope.launch { port.nearby.collect { list -> _state.update { it.copy(candidates = withPick(list)) } } }
            if (pick != null && offered == null) pair(pick, silent = false)
        }
    }

    /** The picked or offered sensor stays listed first until the scan on Find hears it too. */
    private fun withPick(list: List<SetupCandidate>): List<SetupCandidate> {
        val p = picked ?: return list
        return if (list.any { it.id.equals(p.id, ignoreCase = true) }) list else listOf(p) + list
    }

    /** Starts the scan, after the platform granted Bluetooth from the Look for sensors tap. */
    fun find() {
        if (_state.value.page != SetupPage.Find) return
        _state.update { it.copy(finding = true, failure = null) }
        port.startFind()
    }

    /** Picks [candidate] on Find and pairs with it. An added row runs setup again. */
    fun choose(candidate: SetupCandidate) {
        val s = _state.value
        if (s.page != SetupPage.Find || s.pairing) return
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
        // A resumed run already waits for the plan and the forms wait for the link, so a silent failure stays quiet.
        if (silent) return
        if (failure != null) {
            _state.update { it.copy(pairing = false, failure = failure) }
            linkJob?.cancel()
            return
        }
        if (_state.value.paired) return
        val nickname = port.nickname(candidate.id).orEmpty()
        _state.update { it.copy(pairing = false, paired = true, page = SetupPage.Loading, nickname = nickname) }
    }

    /**
     * The paired Thing's plan at [level], each time page 1 or the template changes. A null plan before
     * the first one keeps Loading, a null one later keeps the last. The first plan opens the first step
     * not done.
     */
    fun onPlan(plan: SetupPlan?, level: DetailLevel) {
        _state.update { s ->
            if (plan == null || !s.paired) return@update s
            val hasLocation = plan.steps.any { it.id == StepId.LOCATION } || s.page == SetupPage.Step(StepId.LOCATION)
            val visible = plan.steps.map { it.id }.filter { shows(it, level, hasLocation) }
            val hidden = plan.steps.map { it.id }.filter { !shows(it, level, hasLocation) }
            val order = merge(s.order, visible, hidden, (s.page as? SetupPage.Step)?.id)
            val page = if (s.page == SetupPage.Loading) {
                order.firstOrNull { id -> plan.step(id)?.done == false }?.let(SetupPage::Step)
                    ?: order.firstOrNull()?.let(SetupPage::Step)
                    ?: SetupPage.Done
            } else {
                s.page
            }
            s.copy(plan = plan, order = order, hidden = hidden, page = page)
        }
    }

    fun setNickname(value: String) {
        _state.update { it.copy(nickname = value.replace('\n', ' ').take(NICKNAME_MAX + 1)) }
    }

    /**
     * Next. On a step it runs [save] when the page has something to save, saves the nickname on Name,
     * then sets the step's bit with [ack], and moves on once both are confirmed. A refused save or an
     * unconfirmed ack stays on the page and sets [SetupUiState.ackFailed], as does a save that ends
     * after Back left it. On Done it sets the hidden bits still clear and leaves.
     */
    fun next(save: (suspend () -> Boolean)? = null, ack: suspend (Collection<StepId>) -> Boolean = { true }) {
        val s = _state.value
        if (!s.canAdvance) return
        when (val page = s.page) {
            is SetupPage.Step -> busy(page) {
                if (page.id == StepId.NAME) {
                    val id = s.selected?.id ?: return@busy false
                    port.saveNickname(id, s.nickname.trim().ifEmpty { null })
                }
                (save == null || save()) && acked(page, ack)
            }
            SetupPage.Done -> {
                if (acks(s, page).isEmpty()) return finish()
                busy(page) { acked(page, ack) }
            }
            SetupPage.Find, SetupPage.Loading -> Unit
        }
    }

    /** Skips an optional step, setting its bit too. Ignored while Next saves. */
    fun skip(ack: suspend (Collection<StepId>) -> Boolean = { true }) {
        val s = _state.value
        val page = s.page as? SetupPage.Step ?: return
        if (!s.canSkip) return
        busy(page) { acked(page, ack) }
    }

    /** Sends only the step bits again after [SetupUiState.ackFailed], the page's values are already saved. */
    fun retryAck(ack: suspend (Collection<StepId>) -> Boolean) {
        val s = _state.value
        if (!s.ackFailed || s.saving) return
        busy(s.page) { acked(s.page, ack) }
    }

    /** The bits a page sets, its own and the hidden steps' on a step, the hidden ones still clear on Done. */
    private fun acks(s: SetupUiState, page: SetupPage): List<StepId> = when (page) {
        is SetupPage.Step -> listOf(page.id) + s.hidden
        SetupPage.Done -> s.hidden.filter { s.plan?.step(it)?.acked == false }
        SetupPage.Find, SetupPage.Loading -> emptyList()
    }

    /** Sets the bits of [from], and marks [SetupUiState.ackFailed] when the sensor did not confirm them while still on [from]. */
    private suspend fun acked(from: SetupPage, ack: suspend (Collection<StepId>) -> Boolean): Boolean {
        val ok = ack(acks(_state.value, from))
        if (!ok) _state.update { if (it.page == from) it.copy(ackFailed = true) else it }
        return ok
    }

    /** Runs [work] with Next and Skip off, and moves on when it succeeds while the wizard is still on [from]. */
    private fun busy(from: SetupPage, work: suspend () -> Boolean) {
        _state.update { it.copy(saving = true, ackFailed = false) }
        scope.launch {
            val ok = try {
                work()
            } finally {
                _state.update { it.copy(saving = false) }
            }
            if (ok && _state.value.page == from) {
                if (from == SetupPage.Done) finish() else advance()
            }
        }
    }

    private fun advance() {
        val s = _state.value
        val at = (s.page as? SetupPage.Step)?.id ?: return
        val next = s.order.getOrNull(s.order.indexOf(at) + 1)
        when {
            next != null -> _state.update { it.copy(page = SetupPage.Step(next)) }
            s.doneAllowed -> _state.update { it.copy(page = SetupPage.Done) }
        }
    }

    /** One step back. On the first step, and on Find or Loading, it asks to close instead. On Done it closes. */
    fun back() {
        val s = _state.value
        when (val page = s.page) {
            SetupPage.Done -> finish()
            is SetupPage.Step -> {
                val at = s.order.indexOf(page.id)
                if (at <= 0) requestClose() else _state.update { it.copy(page = SetupPage.Step(s.order[at - 1]), ackFailed = false) }
            }
            SetupPage.Find, SetupPage.Loading -> requestClose()
        }
    }

    /** Closes at once before pairing or on Done. After pairing it asks first. */
    fun requestClose() {
        val s = _state.value
        if (s.paired && s.page != SetupPage.Done) _state.update { it.copy(askClose = true) } else finish()
    }

    fun dismissClose() {
        _state.update { it.copy(askClose = false) }
    }

    /** Leaves the wizard. A paired sensor stays added. */
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

        /** Lan is never a wizard page. Mobility folds into Location when both are planned. Pairing and bonds show from Detailed. */
        internal fun shows(id: StepId, level: DetailLevel, hasLocation: Boolean = false): Boolean = when (id) {
            StepId.LAN -> false
            StepId.MOBILITY -> !hasLocation
            StepId.PAIRING, StepId.BONDS -> level.detailed
            else -> true
        }

        /**
         * The walk order after a new plan. The [old] order keeps its places, less steps the level now
         * hides (but never [current]), and a newly [visible] step goes right after the step before it in
         * the plan, or first.
         */
        internal fun merge(old: List<StepId>, visible: List<StepId>, hidden: List<StepId>, current: StepId?): List<StepId> {
            val order = old.filterTo(ArrayList()) { it !in hidden || it == current }
            visible.forEachIndexed { index, id ->
                if (id in order) return@forEachIndexed
                val before = visible.subList(0, index).lastOrNull { it in order }
                order.add(before?.let { order.indexOf(it) + 1 } ?: 0, id)
            }
            return order
        }
    }
}
