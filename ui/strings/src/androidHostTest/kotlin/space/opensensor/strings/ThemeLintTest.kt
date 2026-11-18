package space.opensensor.strings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Screens take their look from `:ui:designsystem` (#306). Every content card is a `BrandCard` or
 * wraps one, so no screen sets a card radius (`shapes.large`, `shapes.extraLarge`) or its own
 * `RoundedCornerShape`. Type comes from the theme typography and `mono()`, never a raw
 * `FontFamily.` or `sp` size, and colour from the scheme, never a `Color(0x` literal. A screen list
 * (`LazyColumn`) spaces its cards by `CardGap` and pads its sides by `ScreenPadding`.
 *
 * An exception goes in [allowed] with its reason, and an entry that no longer matches fails, so
 * the list only shrinks.
 */
class ThemeLintTest {
    /** Modules another change owns right now. They join the rule when that work lands. */
    private val owned: Set<String> = setOf(
        // The History metrics work changes these screens now (HistoryTrackCard colours, CalendarHeatmap sizes).
        "history",
    )

    private val sources: List<String> = listOf(
        "app/src/main/java/space/opensensor/ui",
        "ios/shared/src/commonMain/kotlin",
    ) + File(Catalogues.root, "feature").listFiles().orEmpty().filter { it.isDirectory && it.name !in owned }.sorted()
        .map { "feature/${it.name}/src/commonMain/kotlin" }

    /** `file rule` pairs allowed to break a rule. Keep this short and say why. */
    private val allowed: Set<String> = setOf(
        // MapLibre label sizes are part of the map style, not Compose text.
        "DiscoverMap.kt size",
        // The legend swatches and the scale bar are small map key shapes, not cards.
        "DiscoverScreen.kt shape",
        // A colour the sensor declares in its template, parsed from its hex string.
        "GenericCards.kt colour",
        // The connection banner is a status strip in the state colours, not a content card.
        "ThingChrome.kt card",
    )

    @Test
    fun screensUseTheThemeAndTheCardRecipe() {
        val files = sources.flatMap { dir ->
            File(Catalogues.root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
        assertTrue(files.size > 50, "found only ${files.size} sources")
        val found = files.flatMap { file -> breaks(file.name, file.readText()) }.toSet()
        val broken = (found - allowed).sorted()
        assertTrue(broken.isEmpty(), "screens that skip the theme, use BrandCard, mono(), CardGap and ScreenPadding\n" + broken.joinToString("\n"))
        val stale = (allowed - found).sorted()
        assertTrue(stale.isEmpty(), "allowed entries that no longer match, remove them\n" + stale.joinToString("\n"))
    }

    @Test
    fun theRuleCatchesEachBreak() {
        val raw = """
            // FontFamily.Serif in a comment does not count.
            val a = Text("x", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFF00806F))
            val b = Surface(shape = MaterialTheme.shapes.large) {}
            val c = Modifier.clip(RoundedCornerShape(4.dp))
            val d = LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {}
        """.trimIndent()
        assertEquals(
            listOf("A.kt card", "A.kt colour", "A.kt font", "A.kt gap", "A.kt padding", "A.kt shape", "A.kt size"),
            breaks("A.kt", raw).sorted(),
        )
        val themed = """
            val a = Text("x", style = MaterialTheme.typography.bodySmall.mono())
            val b = BrandCard(tone = CardTone.ALERT) {}
            val d = LazyColumn(contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 8.dp), verticalArrangement = Arrangement.spacedBy(CardGap)) {}
        """.trimIndent()
        assertEquals(emptyList(), breaks("A.kt", themed))
    }

    private fun breaks(name: String, source: String): List<String> {
        val code = source.lines().filterNot { line ->
            val t = line.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }.joinToString("\n") { it.substringBefore(" // ") }
        val out = mutableListOf<String>()
        if ("FontFamily." in code) out += "$name font"
        if ("Color(0x" in code) out += "$name colour"
        if ("RoundedCornerShape(" in code) out += "$name shape"
        if (Regex("""shapes\.(large|extraLarge)\b""").containsMatchIn(code)) out += "$name card"
        if (Regex("""\b\d+(\.\d+)?\.sp\b""").containsMatchIn(code)) out += "$name size"
        Regex("""\bLazyColumn\s*\(""").findAll(code).forEach { m ->
            val open = m.range.last
            val args = code.substring(open, matching(code, open) ?: code.length)
            if (Regex("""verticalArrangement\s*=\s*Arrangement\.spacedBy\((?!CardGap\))""").containsMatchIn(args)) out += "$name gap"
            if (Regex("""\b(start|end|horizontal)\s*=\s*\d""").containsMatchIn(args) || Regex("""PaddingValues\(\d""").containsMatchIn(args)) out += "$name padding"
        }
        return out.distinct()
    }

    /** The index of the bracket that closes the one at [open], or null. */
    private fun matching(text: String, open: Int): Int? {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return i
            }
        }
        return null
    }
}
