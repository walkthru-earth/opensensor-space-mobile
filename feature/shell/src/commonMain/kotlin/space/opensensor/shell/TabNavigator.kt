package space.opensensor.shell

import androidx.navigation3.runtime.NavKey

/** The app's one bottom bar. Discover is always the start tab (owner decision 2026-10-08). */
enum class TopTab(val root: AppKey) {
    DISCOVER(DiscoverKey),
    MY_DEVICES(ScanKey),
    SETTINGS(SettingsKey),
}

/**
 * Per-tab back stacks behind the one bottom bar, free of Compose so the rules stay testable
 * (`TabNavigatorTest`). Each tab keeps its own stack, so leaving My devices and coming back shows
 * the device that was open. Reselecting the active tab pops it to its root. Back pops the active
 * stack, then returns a tab root to Discover, and at the Discover root it reports `false` so the
 * system closes the app.
 *
 * [stacks] holds one list per tab, each starting with that tab's root (Navigation 3's
 * `NavBackStack` in the shell, plain lists in tests). The selected tab lives outside so Compose can
 * save it, reached through [readTab] and [writeTab].
 */
class TabNavigator(
    private val stacks: Map<TopTab, MutableList<NavKey>>,
    private val readTab: () -> TopTab,
    private val writeTab: (TopTab) -> Unit,
) {
    val tab: TopTab get() = readTab()

    fun stack(tab: TopTab): MutableList<NavKey> = stacks.getValue(tab)

    /** The tabs whose entries are drawn, bottom first. Discover sits under every other tab, so Back lands on it. */
    val visibleTabs: List<TopTab> get() = if (tab == TopTab.DISCOVER) listOf(TopTab.DISCOVER) else listOf(TopTab.DISCOVER, tab)

    /** What the user sees now. */
    val top: NavKey get() = stack(tab).last()

    /** The bar steps aside only for the full-screen inspectors, which have their own back arrow. */
    val showsBottomBar: Boolean get() = top !is DebugKey && top !is FileMetricsKey

    /** A bottom bar tap. The active tab pops to its root, another tab is shown as it was left. */
    fun select(target: TopTab) {
        if (target == tab) popToRoot(target) else writeTab(target)
    }

    /** Opens the My devices root, for "Add your sensor" and when a device screen closes its session. */
    fun toMyDevicesRoot() {
        popToRoot(TopTab.MY_DEVICES)
        writeTab(TopTab.MY_DEVICES)
    }

    /** A detail screen on top of the active tab. */
    fun push(key: NavKey) {
        stack(tab).add(key)
    }

    /**
     * The Debug inspector, reachable only at the Developer detail level on both platforms. A tap below
     * that level, or a second tap while it is already on top, does nothing.
     */
    fun openDebug(address: String, developer: Boolean) {
        if (!developer || top == DebugKey(address)) return
        push(DebugKey(address))
    }

    /** Today, History, Files and Device are siblings. Replace the open one rather than stack them. */
    fun openSection(key: NavKey) {
        val stack = stack(tab)
        val last = stack.last()
        if (last == key) return
        if (isDeviceSection(last)) stack.removeAt(stack.lastIndex)
        stack.add(key)
    }

    /** A screen's own back arrow. Pops only while that screen is still on top, so a double tap cannot pop twice. */
    fun popIfTop(predicate: (NavKey) -> Boolean) {
        if (predicate(top)) pop()
    }

    /** System Back. Returns `false` at the Discover root, where the app closes. */
    fun pop(): Boolean {
        val stack = stack(tab)
        return when {
            stack.size > 1 -> {
                stack.removeAt(stack.lastIndex)
                true
            }
            tab != TopTab.DISCOVER -> {
                writeTab(TopTab.DISCOVER)
                true
            }
            else -> false
        }
    }

    private fun popToRoot(target: TopTab) {
        val stack = stack(target)
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    companion object {
        fun isDeviceSection(key: NavKey): Boolean = key is TodayKey || key is HistoryKey || key is FilesKey || key is DeviceKey
    }
}
