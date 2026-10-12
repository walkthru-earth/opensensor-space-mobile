package earth.walkthru.opensensor.strings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Simple speaks in moods. Its copy never names the guideline body, in any language, even though
 * the colours and moods still come from the WHO steps (docs/superpowers/specs/2026-10-12-stations-ux-design.md).
 */
class SimpleCopyTest {
    private val banned = listOf(Regex("""\bWHO\b"""), Regex("منظمة الصحة"))

    private val designsystem = listOf("values", "values-ar").map { File(Catalogues.root, "ui/designsystem/src/commonMain/composeResources/$it/strings.xml") }
    private val discover = listOf("values", "values-ar").map { File(Catalogues.root, "feature/discover/src/commonMain/composeResources/$it/strings.xml") }

    private fun simpleKeys(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        designsystem.forEach { file ->
            Catalogues.parse(file).filterKeys { it.startsWith("mood_") || it.startsWith("value_") }.forEach { (k, v) -> out["${file.parentFile!!.name}/$k"] = v }
        }
        discover.forEach { file ->
            Catalogues.parse(file).filterKeys { it.startsWith("discover_mood_") }.forEach { (k, v) -> out["discover/${file.parentFile!!.name}/$k"] = v }
        }
        return out
    }

    @Test
    fun theMoodVocabularyIsComplete() {
        val words = listOf("fresh", "fair", "hazy", "poor", "smoky", "thick", "none")
        designsystem.forEach { file ->
            val keys = Catalogues.parse(file).keys
            words.forEach { w ->
                assertTrue("mood_$w" in keys, "${file.parentFile!!.name} lacks mood_$w")
                assertTrue("mood_${w}_line" in keys, "${file.parentFile!!.name} lacks mood_${w}_line")
            }
            assertTrue("value_not_available" in keys, "${file.parentFile!!.name} lacks value_not_available")
        }
    }

    @Test
    fun simpleCopyNeverNamesTheGuidelineBody() {
        val found = simpleKeys().filter { (_, text) -> banned.any { it.containsMatchIn(text) } }
        if (found.isNotEmpty()) fail("Simple copy names the guideline body $found")
    }

    @Test
    fun simpleDiscoverHasItsMoodInfoSheet() {
        discover.forEach { file ->
            val keys = Catalogues.parse(file).keys
            listOf("discover_mood_info_title", "discover_mood_info1", "discover_mood_info2").forEach { k ->
                assertTrue(k in keys, "${file.parentFile.name} lacks $k")
            }
        }
    }

    /** The mood words live once, in the design system. A copy elsewhere drifts, as iOS once did. */
    @Test
    fun moodWordsLiveOnlyInTheDesignSystem() {
        val duplicate = Regex("""mood_(fresh|fair|hazy|poor|smoky|thick|none)(_title)?""")
        val found = Catalogues.all()
            .filter { !Catalogues.relative(it).startsWith("ui/designsystem/") }
            .flatMap { file -> Catalogues.parse(file).keys.filter { duplicate.matches(it) }.map { "${Catalogues.relative(file)} $it" } }
        if (found.isNotEmpty()) fail("Mood words duplicated outside the design system $found")
    }

    /**
     * The Today hero's Simple info sheet. Which key each level reads is a behavior test in
     * feature/today (TodayHeroCopyTest), this checks the Simple text itself.
     */
    @Test
    fun todaySimpleInfoNeverNamesTheGuidelineBody() {
        listOf("values", "values-ar").forEach { dir ->
            val file = File(Catalogues.root, "feature/today/src/commonMain/composeResources/$dir/strings.xml")
            val text = Catalogues.parse(file)["info_now_body_simple"] ?: fail("$dir lacks info_now_body_simple")
            if (banned.any { it.containsMatchIn(text) }) fail("$dir info_now_body_simple names the guideline body")
        }
    }
}
