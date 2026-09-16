package earth.walkthru.aqsync.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import earth.walkthru.aqsync.ui.device.TodayScreen
import earth.walkthru.aqsync.ui.files.FilesScreen
import earth.walkthru.aqsync.ui.scan.ScanScreen

@Composable
fun MainNavigation(modifier: Modifier = Modifier) {
    val backStack = rememberNavBackStack(ScanKey)

    fun openTab(key: NavKey) {
        // Today <-> Files behave as sibling tabs: replace rather than stack.
        val last = backStack.lastOrNull()
        if (last is TodayKey || last is FilesKey) backStack.removeLastOrNull()
        backStack.add(key)
    }

    NavDisplay(
        modifier = modifier,
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<ScanKey> {
                ScanScreen(onConnected = { address -> backStack.add(TodayKey(address)) })
            }
            entry<TodayKey> { key ->
                TodayScreen(
                    address = key.address,
                    onOpenFiles = { openTab(FilesKey(key.address)) },
                    onDisconnected = { backStack.removeAll { it !is ScanKey } },
                )
            }
            entry<FilesKey> { key ->
                FilesScreen(
                    address = key.address,
                    onOpenToday = { openTab(TodayKey(key.address)) },
                    onDisconnected = { backStack.removeAll { it !is ScanKey } },
                )
            }
        },
    )
}
