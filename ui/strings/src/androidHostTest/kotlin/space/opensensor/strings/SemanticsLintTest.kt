package space.opensensor.strings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every custom tap target in the shared screens names its role, so TalkBack and VoiceOver say
 * "button", "switch", "tab" or "radio button" after its label (docs/cross-platform-guide.md,
 * "Accessibility"). Material buttons, switches and tabs carry their role already. A `clickable`,
 * `combinedClickable`, `selectable` or `toggleable` modifier in a shared presentation source must
 * pass `role =`. An exception goes in [allowed] with its reason.
 */
class SemanticsLintTest {
    private val sources: List<String> = listOf(
        "ios/shared/src/commonMain/kotlin",
        "ios/shared/src/iosMain/kotlin",
        "ui/designsystem/src/commonMain/kotlin",
        "ui/permissions/src/commonMain/kotlin",
    ) + File(Catalogues.root, "feature").listFiles().orEmpty().filter { it.isDirectory }.sorted()
        .map { "feature/${it.name}/src/commonMain/kotlin" }

    /** `file name` of a call allowed without a role. Keep this short and say why. */
    private val allowed: Set<String> = emptySet()

    private val call = Regex("""\.(clickable|combinedClickable|selectable|toggleable|triStateToggleable)\b\s*([({])""")

    private fun files(): List<File> = sources.flatMap { dir ->
        File(Catalogues.root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** The argument list of the call that opens at [open], or empty for the trailing lambda form. */
    private fun arguments(text: String, open: Int): String {
        if (text[open] != '(') return ""
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return text.substring(open, i + 1)
            }
        }
        return text.substring(open)
    }

    /** Problems in [text], as `line call` pairs. */
    private fun missingRoles(text: String): List<String> = call.findAll(text).mapNotNull { match ->
        val args = arguments(text, match.groups[2]!!.range.first)
        if (Regex("""\brole\s*=""").containsMatchIn(args)) {
            null
        } else {
            val line = text.substring(0, match.range.first).count { it == '\n' } + 1
            "$line .${match.groupValues[1]}"
        }
    }.toList()

    @Test
    fun scansTheSharedScreens() {
        val names = files().map { it.name }
        assertTrue(names.containsAll(listOf("BottomBar.kt", "DiscoverScreen.kt", "AutoSyncSwitch.kt")), "files $names")
    }

    @Test
    fun everyCustomTapTargetNamesItsRole() {
        val found = files().filterNot { it.name in allowed }.flatMap { file ->
            val code = file.readLines().joinToString("\n") { line ->
                val t = line.trim()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) "" else line
            }
            missingRoles(code).map { "${Catalogues.relative(file)}:$it" }
        }
        assertTrue(found.isEmpty(), "Pass role = Role.Button, Switch, Tab, RadioButton or Checkbox\n${found.joinToString("\n")}")
    }

    @Test
    fun thePatternCatchesAMissingRole() {
        assertTrue(missingRoles("Modifier.clickable { open() }").isNotEmpty())
        assertTrue(missingRoles("Modifier.clickable(onClick = { open() })").isNotEmpty())
        assertTrue(missingRoles("Modifier.clickable(\n  role = Role.Button,\n  onClick = { open() },\n)").isEmpty())
        assertTrue(missingRoles("Modifier.selectable(selected = s, onClick = f, role = Role.Tab)").isEmpty())
        assertTrue(missingRoles("Modifier.toggleable(value = on, onValueChange = f)").isNotEmpty())
    }
}
