package space.opensensor.map

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The picker and the place search hold coordinates only on the stack. They log nothing, save no
 * state across process death and write no storage, so no coordinate is kept or leaves the phone.
 * [CellPickerGridTest] checks that [CellPickerGrid.pick] emits only a resolution 8 cell id.
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
    fun aPickCallsBackOnlyThroughPick() {
        val picker = sources.first().readText()
        // No direct call, with or without invoke, a safe call or a space before the parenthesis.
        val direct = Regex("onSelect\\s*(\\?\\.)?\\s*(\\.?\\s*invoke)?\\s*\\(")
        assertEquals(emptyList(), direct.findAll(picker).map { it.value }.toList(), "onSelect is called only by CellPickerGrid.pick")
        // onSelect is handed on only to PickerMap and to pick, and pick hands it on after pickable alone.
        assertEquals(1, Regex("pickable\\(hex\\)\\?\\.let\\(onSelect\\)").findAll(picker).count())
        assertEquals(1, Regex("CellPickerGrid\\.pick\\(enabled, hex, onSelect\\)").findAll(picker).count())
        val handedOn = Regex("[(,]\\s*onSelect\\s*[,)]").findAll(picker).count()
        assertEquals(3, handedOn, "onSelect goes to PickerMap, to pick and to its let only")
    }
}
