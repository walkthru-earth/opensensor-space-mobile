package earth.walkthru.opensensor.permissions

/**
 * What happens when the user taps an action that needs a runtime permission. Pure, so the timing
 * rules are unit tested (PermissionFlowTest). The app never asks at launch. It asks only from an
 * action, explains first only when Android says a rationale is due, and sends the user to system
 * settings only after the system stops showing its own dialog.
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

    /** The system no longer shows its dialog. Offer the app's system settings page. */
    OPEN_SETTINGS,
}

/**
 * A permission's state where the OS reports one directly (iOS, see [IosAuthorization]). Android
 * reports only granted and the rationale flag, so it uses the two Boolean overloads of
 * [PermissionFlow] instead.
 */
enum class AccessStatus {
    /** Allowed. On iOS this includes reduced accuracy location and provisional notifications. */
    GRANTED,

    /** Never asked. The system prompt will show. */
    NOT_DETERMINED,

    /** The user said no. Only the app's settings page can change it now. */
    DENIED,

    /** Blocked by device management or parental controls. Settings cannot help, so no link. */
    RESTRICTED,

    /**
     * The OS has no status API (iOS Local Network). The action runs, the first real use shows the
     * prompt, and a later policy denial from that use turns this into [DENIED].
     */
    UNKNOWN,
}

object PermissionFlow {
    /** Android, before any dialog. From the current grant, the rationale flag and the radio state. */
    fun onTap(granted: Boolean, showRationale: Boolean, radioOn: Boolean = true): AccessStep = when {
        granted && radioOn -> AccessStep.READY
        granted -> AccessStep.TURN_ON
        showRationale -> AccessStep.EXPLAIN_THEN_ASK
        else -> AccessStep.ASK
    }

    /**
     * Android, after the system dialog answered. [rationaleBefore] is the rationale flag read just
     * before the dialog launched, and [askedBefore] is true once this permission has had an answer
     * earlier in this process.
     *
     * No rationale due after a denial has two meanings. Android stopped showing its dialog (denied
     * twice, or "Don't allow" with the policy set), or the user closed a first-ever dialog with Back
     * or a tap outside, which Android 11 and later do not count. Settings are offered only when an
     * earlier step proves the first: a rationale was due before, or the user is past their first
     * request. A first request with no rationale before or after stays quiet.
     */
    fun onResult(
        granted: Boolean,
        showRationale: Boolean,
        radioOn: Boolean = true,
        rationaleBefore: Boolean = false,
        askedBefore: Boolean = false,
    ): AccessStep = when {
        granted && radioOn -> AccessStep.READY
        granted -> AccessStep.TURN_ON
        showRationale -> AccessStep.DECLINED
        rationaleBefore || askedBefore -> AccessStep.OPEN_SETTINGS
        else -> AccessStep.DECLINED
    }

    /**
     * iOS, before any dialog. iOS shows each prompt once and never asks for a rationale, so a
     * denial seen on a later tap goes straight to settings.
     */
    fun onTap(status: AccessStatus, radioOn: Boolean = true): AccessStep = when (status) {
        AccessStatus.GRANTED -> if (radioOn) AccessStep.READY else AccessStep.TURN_ON
        AccessStatus.NOT_DETERMINED -> AccessStep.ASK
        AccessStatus.DENIED -> AccessStep.OPEN_SETTINGS
        AccessStatus.RESTRICTED -> AccessStep.DECLINED
        AccessStatus.UNKNOWN -> AccessStep.READY
    }

    /**
     * iOS, after the system prompt answered. A fresh "Don't Allow" is respected without a settings
     * dialog. The next tap offers settings ([onTap]).
     */
    fun onResult(status: AccessStatus, radioOn: Boolean = true): AccessStep = when (status) {
        AccessStatus.GRANTED -> if (radioOn) AccessStep.READY else AccessStep.TURN_ON
        AccessStatus.UNKNOWN -> AccessStep.READY
        AccessStatus.NOT_DETERMINED, AccessStatus.DENIED, AccessStatus.RESTRICTED -> AccessStep.DECLINED
    }

    /** True when a screen showing [status] should offer the app's settings page beside it. */
    fun offersSettings(status: AccessStatus): Boolean = status == AccessStatus.DENIED
}
