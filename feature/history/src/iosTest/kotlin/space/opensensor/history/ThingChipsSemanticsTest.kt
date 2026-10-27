package space.opensensor.history

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import space.opensensor.settings.DetailLevel
import kotlin.test.Test
import kotlin.test.assertTrue

/** #119. The History thing chips read the full thing id only at Developer, Simple and Detailed read the label alone. */
@OptIn(ExperimentalTestApi::class)
class ThingChipsSemanticsTest {
    private val old = "0a1b2c3d4e5f60718293a4b5c6d7e8f9"
    private val current = "f9e8d7c6b5a49382716051e4d3c2b1a0"
    private val state = HistoryUiState(thing = current, things = listOf(old, current))

    /** Every text and description a screen reader can reach, merged as it reads them. */
    private fun spoken(level: DetailLevel): List<String> {
        val out = ArrayList<String>()
        runComposeUiTest {
            setContent { MaterialTheme { ThingChips(state, level, onSelect = {}) } }
            fun walk(node: SemanticsNode) {
                node.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.let { out += it }
                node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.let { t -> out += t.map { it.text } }
                node.children.forEach(::walk)
            }
            walk(onRoot().fetchSemanticsNode())
        }
        return out
    }

    @Test
    fun simpleAndDetailedNeverReadTheThingId() {
        for (level in listOf(DetailLevel.SIMPLE, DetailLevel.DETAILED)) {
            val said = spoken(level)
            assertTrue(said.any { "Current" in it } && said.any { "Earlier" in it }, "$level $said")
            assertTrue(said.none { old.take(6) in it || current.take(6) in it }, "$level reads an id $said")
        }
    }

    @Test
    fun developerReadsTheFullThingId() {
        val said = spoken(DetailLevel.DEVELOPER)
        assertTrue(said.any { current in it } && said.any { old in it }, "$said")
    }
}
