package earth.walkthru.opensensor.strings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The repository copy lint (docs/screens.md, Copy and the info affordance). Every user-facing
 * string in every Android `strings.xml` and Compose `strings.xml` follows the copy rules. No em or
 * en dashes, no semicolons and no colons in sentences. Format placeholders (`%1$s`) and clock-like
 * numbers are not sentence colons. Compose resources show a backslash literally, so an escaped
 * apostrophe there is a mistake.
 */
class CopyLintTest {
    @Test
    fun everyCatalogueIsFound() {
        val found = Catalogues.all().map(Catalogues::relative)
        val expected = listOf(
            "app/src/main/res/values/strings.xml",
            "feature/discover/src/commonMain/composeResources/values/strings.xml",
            "feature/settings/src/commonMain/composeResources/values/strings.xml",
            "ui/designsystem/src/commonMain/composeResources/values/strings.xml",
            "ui/strings/src/commonMain/composeResources/values/strings.xml",
        )
        assertTrue(found.containsAll(expected), "catalogues found $found")
        // A catalogue that parses to nothing would pass the lint silently.
        val sizes = Catalogues.all().associate { Catalogues.relative(it) to Catalogues.parse(it).size }
        assertTrue(expected.all { (sizes[it] ?: 0) > 0 }, "empty catalogues in $sizes")
        assertTrue(sizes.getValue("feature/settings/src/commonMain/composeResources/values/strings.xml") > 20)
    }

    @Test
    fun noDashesSemicolonsOrSentenceColons() {
        val broken = Catalogues.all().flatMap { file ->
            val compose = Catalogues.isCompose(file)
            Catalogues.parse(file).filter { (_, text) -> violates(text, compose) }.map { (key, text) -> "${Catalogues.relative(file)} $key = $text" }
        }
        assertTrue(broken.isEmpty(), "copy rule violations\n${broken.joinToString("\n")}")
    }

    @Test
    fun theRuleCatchesEachMark() {
        listOf("A — B", "A – B", "A; B", "Note: B").forEach { assertTrue(violates(it, compose = false), it) }
        assertTrue(violates("It\\'s", compose = true))
        listOf("Status %1\$s", "At 12:30", "It\\'s", "Wi-Fi · Connected").forEach { assertEquals(false, violates(it, compose = false), it) }
    }

    private fun violates(text: String, compose: Boolean): Boolean {
        val sentence = text.replace(Regex("%\\d+\\$[a-z]"), "X").replace(Regex("\\d:\\d"), "0")
        return '—' in sentence || '–' in sentence || ';' in sentence || ':' in sentence || (compose && "\\'" in sentence)
    }
}
