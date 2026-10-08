package earth.walkthru.aqsync.ui

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        nav.openSection(FilesKey("A"))
        nav.select(TopTab.DISCOVER)
        nav.select(TopTab.SETTINGS)
        nav.select(TopTab.MY_DEVICES)
        assertEquals(listOf(ScanKey, FilesKey("A")), stack(TopTab.MY_DEVICES))
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
    fun `device sections replace each other rather than stack`() {
        nav.select(TopTab.MY_DEVICES)
        nav.push(TodayKey("A"))
        nav.openSection(HistoryKey("A"))
        nav.openSection(DeviceKey("A"))
        nav.openSection(DeviceKey("A"))
        assertEquals(listOf(ScanKey, DeviceKey("A")), stack(TopTab.MY_DEVICES))
    }

    @Test
    fun `a section opened over an inspector stacks on it`() {
        nav.select(TopTab.MY_DEVICES)
        nav.push(FilesKey("A"))
        nav.push(FileMetricsKey("s", "f"))
        nav.openSection(TodayKey("A"))
        assertEquals(listOf(ScanKey, FilesKey("A"), FileMetricsKey("s", "f"), TodayKey("A")), stack(TopTab.MY_DEVICES))
    }

    @Test
    fun `the bar hides only on the full screen inspectors`() {
        nav.select(TopTab.MY_DEVICES)
        nav.push(TodayKey("A"))
        assertTrue(nav.showsBottomBar)
        nav.push(DebugKey("A"))
        assertFalse(nav.showsBottomBar)
        nav.popIfTop { it is DebugKey }
        nav.openSection(FilesKey("A"))
        nav.push(FileMetricsKey("s", "f"))
        assertFalse(nav.showsBottomBar)
        nav.pop()
        assertTrue(nav.showsBottomBar)
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
