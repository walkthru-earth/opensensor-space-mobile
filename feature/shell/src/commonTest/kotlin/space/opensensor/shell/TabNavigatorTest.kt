package space.opensensor.shell

import androidx.navigation3.runtime.NavKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TabNavigatorTest {
    private var tab = TopTab.DISCOVER
    private val stacks: Map<TopTab, MutableList<NavKey>> = TopTab.entries.associateWith { mutableListOf(it.root) }
    private val nav = TabNavigator(stacks, readTab = { tab }, writeTab = { tab = it })

    private fun stack(t: TopTab) = stacks.getValue(t).toList()

    @Test
    fun `starts on Discover with only the map drawn`() {
        assertEquals(TopTab.DISCOVER, nav.tab)
        assertEquals(listOf(TopTab.DISCOVER), nav.visibleTabs)
        assertEquals(DiscoverKey, nav.top)
        assertTrue(nav.showsBottomBar)
    }

    @Test
    fun `back at the Discover root lets the app close`() {
        assertFalse(nav.pop())
        assertEquals(TopTab.DISCOVER, nav.tab)
    }

    @Test
    fun `other tabs draw over Discover so Back from their root lands on the map`() {
        nav.select(TopTab.SETTINGS)
        assertEquals(listOf(TopTab.DISCOVER, TopTab.SETTINGS), nav.visibleTabs)
        assertTrue(nav.pop())
        assertEquals(TopTab.DISCOVER, nav.tab)
        assertEquals(listOf(SettingsKey), stack(TopTab.SETTINGS))
    }

    @Test
    fun `each tab keeps its own stack across tab switches`() {
        nav.select(TopTab.MY_DEVICES)
        nav.push(TodayKey("A"))
        nav.push(FilesKey("A"))
        nav.select(TopTab.DISCOVER)
        nav.select(TopTab.SETTINGS)
        nav.select(TopTab.MY_DEVICES)
        assertEquals(listOf(ScanKey, TodayKey("A"), FilesKey("A")), stack(TopTab.MY_DEVICES))
        assertEquals(FilesKey("A"), nav.top)
    }

    @Test
    fun `reselecting the active tab pops it to its root`() {
        nav.select(TopTab.MY_DEVICES)
        nav.push(TodayKey("A"))
        nav.push(DebugKey("A"))
        nav.select(TopTab.MY_DEVICES)
        assertEquals(listOf(ScanKey), stack(TopTab.MY_DEVICES))
        assertEquals(TopTab.MY_DEVICES, nav.tab)
    }

    @Test
    fun `back pops within the tab before leaving it`() {
        nav.select(TopTab.MY_DEVICES)
        nav.push(TodayKey("A"))
        assertTrue(nav.pop())
        assertEquals(TopTab.MY_DEVICES, nav.tab)
        assertEquals(ScanKey, nav.top)
        assertTrue(nav.pop())
        assertEquals(TopTab.DISCOVER, nav.tab)
        assertFalse(nav.pop())
    }

    @Test
    fun `every page the Station page opens is a push so Back returns to the Station page`() {
        nav.select(TopTab.MY_DEVICES)
        nav.push(TodayKey("A"))
        for (page in listOf(HistoryKey("A"), FilesKey("A"), DeviceKey("A"))) {
            nav.push(page)
            assertEquals(listOf(ScanKey, TodayKey("A"), page), stack(TopTab.MY_DEVICES))
            assertTrue(nav.showsBottomBar)
            nav.popIfTop { it == page }
            assertEquals(TodayKey("A"), nav.top)
        }
    }

    @Test
    fun `a second tap on a page already on top does not stack it twice`() {
        nav.select(TopTab.MY_DEVICES)
        nav.push(TodayKey("A"))
        nav.push(HistoryKey("A"))
        nav.push(HistoryKey("A"))
        assertEquals(listOf(ScanKey, TodayKey("A"), HistoryKey("A")), stack(TopTab.MY_DEVICES))
        assertTrue(nav.pop())
        assertEquals(TodayKey("A"), nav.top)
    }

    @Test
    fun `the bar hides only on the full screen inspectors and the wizard`() {
        nav.select(TopTab.MY_DEVICES)
        nav.push(TodayKey("A"))
        assertTrue(nav.showsBottomBar)
        nav.push(DebugKey("A"))
        assertFalse(nav.showsBottomBar)
        nav.popIfTop { it is DebugKey }
        nav.push(FilesKey("A"))
        nav.push(FileMetricsKey("s", "f"))
        assertFalse(nav.showsBottomBar)
        nav.pop()
        assertTrue(nav.showsBottomBar)
    }

    @Test
    fun `a new sensor tapped on My devices opens the wizard and not Today`() {
        nav.select(TopTab.MY_DEVICES)
        nav.openSetup(pick = "A", pickName = "OS-1")
        assertEquals(listOf(ScanKey, SetupKey(pick = "A", pickName = "OS-1")), stack(TopTab.MY_DEVICES))
        assertTrue(stack(TopTab.MY_DEVICES).none { it is TodayKey })
    }

    @Test
    fun `a tap on Finish setup resumes a known sensor and offers a new one`() {
        nav.select(TopTab.MY_DEVICES)
        nav.finishSetup("A", "OS-1", known = true)
        assertEquals(listOf(ScanKey, SetupKey(resume = "A")), stack(TopTab.MY_DEVICES))
        nav.pop()
        nav.finishSetup("B", "OS-2", known = false)
        assertEquals(listOf(ScanKey, SetupKey(pick = "B", pickName = "OS-2", offer = true)), stack(TopTab.MY_DEVICES))
    }

    @Test
    fun `an automatic open only offers a known sensor so nothing resumes or connects`() {
        nav.select(TopTab.MY_DEVICES)
        nav.finishSetup("A", "OS-1", known = true, auto = true)
        assertEquals(listOf(ScanKey, SetupKey(pick = "A", pickName = "OS-1", offer = true)), stack(TopTab.MY_DEVICES))
    }

    @Test
    fun `a setup request acts only while the My devices root is on top`() {
        nav.select(TopTab.MY_DEVICES)
        nav.finishSetup("A", "OS-1", known = false, auto = true)
        // A second request sent as My devices fades out does not replace the open wizard.
        nav.finishSetup("B", "OS-2", known = false, auto = true)
        assertEquals(listOf(ScanKey, SetupKey(pick = "A", pickName = "OS-1", offer = true)), stack(TopTab.MY_DEVICES))
        nav.pop()
        nav.push(TodayKey("C"))
        nav.finishSetup("B", "OS-2", known = true)
        assertEquals(listOf(ScanKey, TodayKey("C")), stack(TopTab.MY_DEVICES))
        nav.select(TopTab.DISCOVER)
        nav.finishSetup("B", "OS-2", known = false)
        assertEquals(TopTab.DISCOVER, nav.tab)
        assertEquals(listOf(ScanKey, TodayKey("C")), stack(TopTab.MY_DEVICES))
    }

    @Test
    fun `add sensor opens the wizard full screen over the My devices root`() {
        nav.select(TopTab.MY_DEVICES)
        nav.push(TodayKey("A"))
        nav.select(TopTab.DISCOVER)
        nav.openSetup()
        assertEquals(TopTab.MY_DEVICES, nav.tab)
        assertEquals(listOf(ScanKey, SetupKey()), stack(TopTab.MY_DEVICES))
        assertFalse(nav.showsBottomBar)
        nav.pop()
        assertTrue(nav.showsBottomBar)
    }

    @Test
    fun `debug opens only at the developer level and never twice`() {
        nav.select(TopTab.MY_DEVICES)
        nav.push(TodayKey("A"))
        nav.openDebug("A", developer = false)
        assertEquals(listOf(ScanKey, TodayKey("A")), stack(TopTab.MY_DEVICES))
        nav.openDebug("A", developer = true)
        nav.openDebug("A", developer = true)
        assertEquals(listOf(ScanKey, TodayKey("A"), DebugKey("A")), stack(TopTab.MY_DEVICES))
    }

    @Test
    fun `a screen's back arrow pops only while it is on top`() {
        nav.select(TopTab.MY_DEVICES)
        nav.push(TodayKey("A"))
        nav.push(DebugKey("A"))
        nav.popIfTop { it is DebugKey }
        nav.popIfTop { it is DebugKey }
        assertEquals(listOf(ScanKey, TodayKey("A")), stack(TopTab.MY_DEVICES))
    }

    @Test
    fun `a closed session and Add your sensor both return to the My devices root`() {
        nav.select(TopTab.MY_DEVICES)
        nav.push(TodayKey("A"))
        nav.push(DebugKey("A"))
        nav.toMyDevicesRoot()
        assertEquals(listOf(ScanKey), stack(TopTab.MY_DEVICES))

        nav.push(TodayKey("B"))
        nav.select(TopTab.DISCOVER)
        nav.toMyDevicesRoot()
        assertEquals(TopTab.MY_DEVICES, nav.tab)
        assertEquals(listOf(ScanKey), stack(TopTab.MY_DEVICES))
    }

    @Test
    fun `opening Settings from a device keeps the device for the way back`() {
        nav.select(TopTab.MY_DEVICES)
        nav.push(TodayKey("A"))
        nav.select(TopTab.SETTINGS)
        assertEquals(SettingsKey, nav.top)
        nav.select(TopTab.MY_DEVICES)
        assertEquals(TodayKey("A"), nav.top)
    }
}
