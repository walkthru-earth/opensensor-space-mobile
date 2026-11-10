package space.opensensor.today

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.jetbrains.compose.resources.stringResource
import space.opensensor.settings.DetailLevel
import space.opensensor.today.resources.Res
import space.opensensor.today.resources.nav_back
import space.opensensor.today.resources.nav_debug
import space.opensensor.today.resources.nav_device_settings
import space.opensensor.today.resources.station_files
import space.opensensor.today.resources.station_history
import space.opensensor.today.resources.station_more
import space.opensensor.units.UnitFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The Station page is the hub of one sensor (#117). Its More card opens History and Files at every
 * level and Debug at Developer, the gear opens Device settings and the back arrow leaves for My
 * devices. Each is one action the platform turns into a plain push or a pop.
 */
@OptIn(ExperimentalTestApi::class)
class StationLinksTest {
    private object Source : TodaySource {
        override val snapshot: StateFlow<TodaySnapshot> = MutableStateFlow(TodaySnapshot(name = "Kitchen"))
        override val notices = emptyFlow<TodayNotice>()
        override fun sync() = Unit
        override fun flush() = Unit
        override fun stopSync() = Unit
        override fun reconnect() = Unit
        override fun disconnect() = Unit
    }

    private val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Test
    fun historyAndFilesShowAtEveryLevelAndDebugOnlyAtDeveloper() {
        assertEquals(listOf(StationLink.HISTORY, StationLink.FILES), stationLinks(DetailLevel.SIMPLE))
        assertEquals(listOf(StationLink.HISTORY, StationLink.FILES), stationLinks(DetailLevel.DETAILED))
        assertEquals(listOf(StationLink.HISTORY, StationLink.FILES, StationLink.DEBUG), stationLinks(DetailLevel.DEVELOPER))
    }

    @Test
    fun simpleOffersHistoryAndFilesAndEachRowSendsItsAction() = runComposeUiTest {
        val actions = mutableListOf<TodayAction>()
        val labels = mutableMapOf<String, String>()
        setContent {
            MaterialTheme {
                labels["more"] = stringResource(Res.string.station_more)
                labels["history"] = stringResource(Res.string.station_history)
                labels["files"] = stringResource(Res.string.station_files)
                labels["debug"] = stringResource(Res.string.nav_debug)
                StationLinksCard(DetailLevel.SIMPLE, onAction = { actions += it })
            }
        }
        // The eyebrow is uppercased, so it speaks its title through its content description.
        val more = labels.getValue("more")
        onNode(hasContentDescription(more) or hasText(more), useUnmergedTree = true).assert(isHeading)
        onAllNodesWithText(labels.getValue("debug"), useUnmergedTree = true).assertCountEquals(0)
        onNodeWithText(labels.getValue("history"), useUnmergedTree = true).performClick()
        onNodeWithText(labels.getValue("files"), useUnmergedTree = true).performClick()
        assertEquals(listOf<TodayAction>(TodayAction.OpenHistory, TodayAction.OpenFiles), actions)
    }

    @Test
    fun developerAlsoOffersDebug() = runComposeUiTest {
        val actions = mutableListOf<TodayAction>()
        var debug = ""
        setContent {
            MaterialTheme {
                debug = stringResource(Res.string.nav_debug)
                StationLinksCard(DetailLevel.DEVELOPER, onAction = { actions += it })
            }
        }
        onNodeWithText(debug, useUnmergedTree = true).performClick()
        assertEquals(listOf<TodayAction>(TodayAction.OpenDebug), actions)
    }

    @Test
    fun theTopBarLeavesWithBackAndOpensDeviceSettingsWithTheGear() = runComposeUiTest {
        val actions = mutableListOf<TodayAction>()
        var controller: TodayController? = null
        var back = ""
        var gear = ""
        var history = ""
        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                val c = controller ?: TodayController(scope, Source, MutableStateFlow(DetailLevel.SIMPLE)).also { controller = it }
                back = stringResource(Res.string.nav_back)
                gear = stringResource(Res.string.nav_device_settings)
                history = stringResource(Res.string.station_history)
                TodayScreen(c, UnitFormatter.Metric, onAction = { actions += it })
            }
        }
        waitForIdle()
        onNodeWithContentDescription(back).performClick()
        onNodeWithContentDescription(gear).performClick()
        // The More card closes the Station page list, after every reading card.
        val list = onNode(hasScrollToKeyAction())
        list.performScrollToKey(STATION_LINKS_KEY)
        // No item follows it, so the list refuses to scroll one index past the More card.
        val config = list.fetchSemanticsNode().config
        val more = config[SemanticsProperties.IndexForKey](STATION_LINKS_KEY)
        assertFailsWith<IllegalArgumentException> { runOnIdle { config[SemanticsActions.ScrollToIndex].action?.invoke(more + 1) } }
        onNodeWithText(history, useUnmergedTree = true).performClick()
        assertEquals(listOf(TodayAction.OpenDevices, TodayAction.OpenDevice, TodayAction.OpenHistory), actions)
    }
}
