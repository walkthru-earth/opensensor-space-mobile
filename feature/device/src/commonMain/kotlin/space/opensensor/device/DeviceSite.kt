package space.opensensor.device

import space.opensensor.model.SiteField
import space.opensensor.model.SiteProblem
import space.opensensor.model.ThingSite

enum class DeviceSiteResult { SAVED, FAILED, NOT_CONFIRMED }

/**
 * The owner site of the sensor (firmware #148). [stored] is the board's last answer from pages 5 and
 * 6, [draft] what the user typed. [supported] is null until the board answered, false when page 5
 * says the board has no site keys, and then the fields stay hidden.
 */
data class DeviceSiteState(
    val supported: Boolean? = null,
    val working: Boolean = false,
    val stored: ThingSite = ThingSite(),
    val draft: ThingSite = ThingSite(),
    val result: DeviceSiteResult? = null,
) {
    /** The first problem of each drafted value, shown under its field. */
    val problems: Map<SiteField, SiteProblem> get() = draft.problems()

    /** What Save would send, each changed value. An emptied one clears the key. */
    val changes: Map<SiteField, String> get() = draft.changesFrom(stored)

    val dirty: Boolean get() = changes.isNotEmpty()

    /** Whether Save may send now, not counting the link. */
    val canSave: Boolean get() = supported == true && !working && dirty && problems.isEmpty()
}
