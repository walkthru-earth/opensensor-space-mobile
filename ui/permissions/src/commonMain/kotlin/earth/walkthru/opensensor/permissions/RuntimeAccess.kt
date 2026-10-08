package earth.walkthru.opensensor.permissions

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The in-context dialog a [RuntimeAccess] wants shown, if any. */
enum class AccessDialog { RATIONALE, SETTINGS }

/**
 * How one platform reads and asks for a permission. The platform calls back
 * [RuntimeAccess.onPermissionResult] when its prompt answers and [RuntimeAccess.onTurnOnResult]
 * when its radio switch answers. Build one with [rationaleBased] (Android) or [statusBased] (iOS).
 */
class AccessHooks(
    val granted: () -> Boolean,
    val onTap: () -> AccessStep,
    val onResult: () -> AccessStep,
    val radioOn: () -> Boolean = { true },
    val requestPermission: () -> Unit,
    val requestTurnOn: () -> Unit = {},
    val openSettings: () -> Unit,
) {
    companion object {
        /** Nothing to ask. Every action runs at once. */
        val None = AccessHooks(granted = { true }, onTap = { AccessStep.READY }, onResult = { AccessStep.READY }, requestPermission = {}, openSettings = {})

        /** Android. The OS reports the grant and whether a rationale is due. */
        fun rationaleBased(
            granted: () -> Boolean,
            rationaleDue: () -> Boolean,
            requestPermission: () -> Unit,
            openSettings: () -> Unit,
            radioOn: () -> Boolean = { true },
            requestTurnOn: () -> Unit = {},
        ) = AccessHooks(
            granted = granted,
            onTap = { PermissionFlow.onTap(granted(), rationaleDue(), radioOn()) },
            onResult = { PermissionFlow.onResult(granted(), rationaleDue(), radioOn()) },
            radioOn = radioOn,
            requestPermission = requestPermission,
            requestTurnOn = requestTurnOn,
            openSettings = openSettings,
        )

        /** iOS. The OS reports an [AccessStatus]. */
        fun statusBased(
            status: () -> AccessStatus,
            requestPermission: () -> Unit,
            openSettings: () -> Unit,
            radioOn: () -> Boolean = { true },
            requestTurnOn: () -> Unit = {},
        ) = AccessHooks(
            granted = { status() == AccessStatus.GRANTED },
            onTap = { PermissionFlow.onTap(status(), radioOn()) },
            onResult = { PermissionFlow.onResult(status(), radioOn()) },
            radioOn = radioOn,
            requestPermission = requestPermission,
            requestTurnOn = requestTurnOn,
            openSettings = openSettings,
        )
    }
}

/**
 * A permission (and optionally a radio) that an action needs. Call [run] from a tap. Nothing is
 * requested at launch or from composition. Render [AccessDialogs] next to the screen that owns it.
 */
@Stable
class RuntimeAccess(val kind: AccessKind) {
    /** Permission held (or nothing to hold here). Updated by [refresh] and after a dialog. */
    var granted by mutableStateOf(false)

    /** The radio is on, or the action needs no radio. Platforms may follow the radio live. */
    var radioOn by mutableStateOf(true)

    /** True when an action can run without any dialog. Safe to read in composition. */
    val ready: Boolean get() = granted && radioOn

    var dialog by mutableStateOf<AccessDialog?>(null)
        private set

    private var hooks: AccessHooks = AccessHooks.None
    private var onReady: (() -> Unit)? = null
    private var onDeclined: (() -> Unit)? = null

    /** Connects the platform. Call again on every composition with fresh launchers. */
    fun bind(hooks: AccessHooks) {
        this.hooks = hooks
    }

    /** Re-reads the grant and radio, for example on resume after system settings. Never asks. */
    fun refresh() {
        granted = hooks.granted()
        radioOn = hooks.radioOn()
    }

    /** Run [action] once the permission (and radio) are in place, asking only if needed. */
    fun run(onDeclined: () -> Unit = {}, action: () -> Unit) {
        this.onReady = action
        this.onDeclined = onDeclined
        handle(hooks.onTap())
    }

    fun onPermissionResult() {
        refresh()
        handle(hooks.onResult())
    }

    fun onTurnOnResult() {
        radioOn = hooks.radioOn()
        handle(if (radioOn) AccessStep.READY else AccessStep.DECLINED)
    }

    fun confirmRationale() {
        dialog = null
        hooks.requestPermission()
    }

    fun openSettings() {
        dialog = null
        hooks.openSettings()
        handle(AccessStep.DECLINED)
    }

    fun dismiss() {
        dialog = null
        handle(AccessStep.DECLINED)
    }

    private fun handle(step: AccessStep) {
        when (step) {
            AccessStep.READY -> {
                granted = true
                finish(onReady)
            }
            AccessStep.TURN_ON -> hooks.requestTurnOn()
            AccessStep.ASK -> hooks.requestPermission()
            AccessStep.EXPLAIN_THEN_ASK -> dialog = AccessDialog.RATIONALE
            AccessStep.OPEN_SETTINGS -> dialog = AccessDialog.SETTINGS
            AccessStep.DECLINED -> finish(onDeclined)
        }
    }

    private fun finish(callback: (() -> Unit)?) {
        onReady = null
        onDeclined = null
        callback?.invoke()
    }
}
