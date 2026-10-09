package earth.walkthru.opensensor.shell

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import earth.walkthru.opensensor.designsystem.AppIcons
import earth.walkthru.opensensor.designsystem.BottomBarTab
import earth.walkthru.opensensor.designsystem.BrandBottomBar
import earth.walkthru.opensensor.strings.resources.Res
import earth.walkthru.opensensor.strings.resources.nav_settings
import earth.walkthru.opensensor.strings.resources.tab_discover
import earth.walkthru.opensensor.strings.resources.tab_my_devices
import org.jetbrains.compose.resources.stringResource

/**
 * The app root on Android and iOS. One bottom bar (Discover, My devices, Settings) around one
 * Navigation 3 [NavDisplay]. Discover is always the start tab, and nothing is asked at launch.
 *
 * Each tab keeps its own back stack, decorated separately so a device left open in My devices keeps
 * its saved state and ViewModels while another tab shows. A key leaves its stack only on a real pop,
 * which is when its state is cleared. The rules live in [TabNavigator].
 *
 * Back (Android predictive back, the iOS start edge swipe) reaches [NavDisplay] through Navigation
 * Event. A screen that must take Back first, such as the open Discover sheet, registers its own
 * `NavigationBackHandler`, which wins because handlers run last in, first out.
 *
 * [entries] supplies each platform's screens until they move to common. [bottomBar] defaults to
 * [ShellBottomBar].
 */
@Composable
fun AppShell(
    entries: EntryProviderScope<NavKey>.(TabNavigator) -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: @Composable (TabNavigator) -> Unit = { ShellBottomBar(it) },
) {
    val discover = rememberNavBackStack(AppNavConfig, TopTab.DISCOVER.root)
    val devices = rememberNavBackStack(AppNavConfig, TopTab.MY_DEVICES.root)
    val settings = rememberNavBackStack(AppNavConfig, TopTab.SETTINGS.root)
    var tab by rememberSaveable { mutableStateOf(TopTab.DISCOVER) }
    val nav = remember(discover, devices, settings) {
        TabNavigator(
            stacks = mapOf(TopTab.DISCOVER to discover, TopTab.MY_DEVICES to devices, TopTab.SETTINGS to settings),
            readTab = { tab },
            writeTab = { tab = it },
        )
    }

    val provider = entryProvider<NavKey> { entries(nav) }

    // One decorated list per tab. Switching tabs only changes what is drawn.
    val entriesByTab = TopTab.entries.associateWith { t ->
        key(t) {
            rememberDecoratedNavEntries(
                backStack = nav.stack(t),
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
                entryProvider = provider,
            )
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        // Screens own their top insets. Only the bar's height is passed down, and consumed so a
        // screen's own Scaffold does not add the navigation bar inset a second time.
        contentWindowInsets = WindowInsets(0),
        bottomBar = { if (nav.showsBottomBar) bottomBar(nav) },
    ) { padding ->
        NavDisplay(
            entries = nav.visibleTabs.flatMap { entriesByTab.getValue(it) },
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            onBack = { nav.pop() },
        )
    }
}

/** The shared bottom bar ([BrandBottomBar]) with the three tabs, the same on both apps. */
@Composable
fun ShellBottomBar(nav: TabNavigator, modifier: Modifier = Modifier) {
    BrandBottomBar(
        tabs = listOf(
            BottomBarTab(TopTab.DISCOVER, stringResource(Res.string.tab_discover), AppIcons.Outlined.Map),
            BottomBarTab(TopTab.MY_DEVICES, stringResource(Res.string.tab_my_devices), AppIcons.Outlined.Sensors),
            BottomBarTab(TopTab.SETTINGS, stringResource(Res.string.nav_settings), AppIcons.Outlined.Settings),
        ),
        selected = nav.tab,
        onSelect = nav::select,
        modifier = modifier,
    )
}
