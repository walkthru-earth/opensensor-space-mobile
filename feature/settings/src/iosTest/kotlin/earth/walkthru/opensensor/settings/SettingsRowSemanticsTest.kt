package earth.walkthru.opensensor.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import earth.walkthru.aqsync.settings.SettingsRepository
import earth.walkthru.aqsync.units.UnitSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Settings rows read as one sentence with their state for TalkBack and VoiceOver (#99): a Storage row
 * is its name and its size, the old format files row its name and its status, and each button says
 * what it acts on. Rendered headless on the iOS simulator (verifyIos).
 */
@OptIn(ExperimentalTestApi::class)
class SettingsRowSemanticsTest {
    private class MemoryStore : DataStore<Preferences> {
        val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private class Storage : StorageControls {
        override suspend fun usage() = StorageUsage(sensorFilesBytes = 40 * MB, historyCacheBytes = 3 * MB, mapCacheBytes = 0, temporaryBytes = 0)

        override suspend fun clearHistoryCache() = Unit

        override suspend fun clearMapCache() = Unit

        override suspend fun archiveDays(): OldFilesRange? = null

        override suspend fun findOldFiles(range: OldFilesRange?) = OldFilesPlan(files = 3, bytes = 15 * MB, stations = 1, range = range, handle = "plan")

        override suspend fun removeOldFiles(plan: OldFilesPlan) = OldFilesResult(plan.files, plan.bytes, kept = 0)

        override suspend fun removedFiles(): Int = 2

        override suspend fun downloadRemovedAgain() = Unit
    }

    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val controller = SettingsController(scope, SettingsRepository(MemoryStore()), Storage())

    @AfterTest
    fun tearDown() = scope.cancel()

    /** Nodes whose merged text is exactly [lines], one per line in order. */
    private fun readsAs(vararg lines: String) = SemanticsMatcher("reads as ${lines.toList()}") { node ->
        node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.map { it.text } == lines.toList()
    }

    @Test
    fun theOldFilesRowReadsAsOneSentenceWithItsStatus() = runComposeUiTest {
        setContent { MaterialTheme { SettingsScreen(controller, appVersion = "1.0", automaticUnits = UnitSystem.METRIC) } }
        onAllNodes(readsAs("Old format files")).assertCountEquals(1)
        // The buttons name what they act on, and the dates button says it chooses dates and which it holds.
        onNodeWithContentDescription("Find old format files").performSemanticsAction(SemanticsActions.OnClick)
        onAllNodes(readsAs("Old format files", "3 files, 15.0 MB")).assertCountEquals(1)
        onAllNodesWithContentDescription("Remove old format files").assertCountEquals(1)
        onAllNodes(
            hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Choose dates")) and
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "All dates"),
        ).assertCountEquals(1)
        // The info button keeps its own focus stop beside the merged text.
        onAllNodesWithContentDescription("More about Old format files").assertCountEquals(1)
    }

    @Test
    fun storageRowsReadTheirNameWithTheirSize() = runComposeUiTest {
        setContent { MaterialTheme { SettingsScreen(controller, appVersion = "1.0", automaticUnits = UnitSystem.METRIC) } }
        onAllNodes(readsAs("Sensor files", "40.0 MB")).assertCountEquals(1)
        onAllNodes(readsAs("History cache", "3.0 MB")).assertCountEquals(1)
        onAllNodes(readsAs("Removed files", "2 files")).assertCountEquals(1)
        onAllNodesWithContentDescription("Clear History cache").assertCountEquals(1)
        onAllNodesWithContentDescription("Download removed files again").assertCountEquals(1)
    }

    private companion object {
        const val MB = 1_048_576L
    }
}
