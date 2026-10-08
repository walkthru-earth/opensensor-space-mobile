package earth.walkthru.aqsync.ui.permissions

/**
 * What happens when the user taps an action that needs a runtime permission. Pure, so the timing
 * rules are unit tested (PermissionFlowTest). The app never asks at launch. It asks only from an
 * action, explains first only when Android says a rationale is due, and sends the user to system
 * settings only after Android stops showing its own dialog.
 *
 * https://developer.android.com/training/permissions/requesting#workflow_for_requesting_permissions
 */
enum class AccessStep {
    /** Everything is in place. Run the action. */
    READY,

    /** Permission held but the radio is off (Bluetooth). Ask the system to turn it on. */
    TURN_ON,

    /** Show the system permission dialog now. */
    ASK,

    /** Explain in context first (Android says a rationale is due), then show the system dialog. */
    EXPLAIN_THEN_ASK,

    /** The user said no. Do nothing more until the next tap. */
    DECLINED,

    /** Android no longer shows its dialog. Offer the app's system settings page. */
    OPEN_SETTINGS,
}

object PermissionFlow {
    /** Before any dialog: from the current grant, the rationale flag and the radio state. */
    fun onTap(granted: Boolean, showRationale: Boolean, radioOn: Boolean = true): AccessStep = when {
        granted && radioOn -> AccessStep.READY
        granted -> AccessStep.TURN_ON
        showRationale -> AccessStep.EXPLAIN_THEN_ASK
        else -> AccessStep.ASK
    }

    /**
     * After the system dialog answered. A denial with no rationale due means Android will not show
     * its dialog again (denied twice, or "Don't allow" with the policy set), so only settings help.
     */
    fun onResult(granted: Boolean, showRationale: Boolean, radioOn: Boolean = true): AccessStep = when {
        granted && radioOn -> AccessStep.READY
        granted -> AccessStep.TURN_ON
        showRationale -> AccessStep.DECLINED
        else -> AccessStep.OPEN_SETTINGS
    }
}
