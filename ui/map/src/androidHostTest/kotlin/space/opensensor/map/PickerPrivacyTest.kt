package space.opensensor.map

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The picker and the place search hold coordinates only on the stack. They log nothing, save no
 * state across process death and write no storage, so no coordinate is kept or leaves the phone.
 * [CellPickerGridTest] checks that a pick emits only a resolution 8 cell id.
 */
class PickerPrivacyTest {
    private val sources = listOf("CellPicker.kt", "PlaceSearch.kt", "PlaceSearchField.kt")
        .map { File("src/commonMain/kotlin/space/opensensor/map/$it") }

    @Test
    fun noLogStoreOrSavedStateInThePicker() {
        val banned = listOf("println(", "Log.", "NSLog", "Napier", "rememberSaveable", "Saver", "DataStore", "SharedPreferences", "NSUserDefaults", "writeText", "writeBytes")
        for (file in sources) {
            assertTrue(file.isFile, "missing $file")
            val code = file.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n")
            for (word in banned) assertTrue(word !in code, "$word in ${file.name}")
        }
    }

    @Test
    fun aPickCallsBackWithTheCheckedCellOnly() {
        val picker = sources.first().readText()
        // The one call of onSelect goes through pickable, which passes only a resolution 8 cell id.
        assertTrue("CellPickerGrid.pickable(hex)?.let(onSelect)" in picker)
        assertTrue(Regex("onSelect\\(").findAll(picker).count() == 0, "onSelect is called only through pickable")
    }
}
