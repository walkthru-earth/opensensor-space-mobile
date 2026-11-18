package space.opensensor.strings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An effect keeps the lambdas it captured when it started, so a block that calls a caller's lambda
 * directly calls a stale one after the caller passes a new lambda. Each `LaunchedEffect`,
 * `DisposableEffect`, `LifecycleStartEffect`, `LifecycleResumeEffect` and `pointerInput` block in a
 * shared screen reads such a lambda through `rememberUpdatedState`, as `AppShell` and `BaseMap` do
 * (docs/cross-platform-guide.md, "Compose effects"). The rule reads the source text, so it sees a
 * parameter used by name in the block, not one reached through a local function. An exception goes
 * in [allowed] with its reason.
 */
class EffectLambdaLintTest {
    private val sources: List<String> = listOf(
        "ios/shared/src/commonMain/kotlin",
        "ios/shared/src/iosMain/kotlin",
        "ui/designsystem/src/commonMain/kotlin",
        "ui/map/src/commonMain/kotlin",
        "ui/permissions/src/commonMain/kotlin",
    ) + File(Catalogues.root, "feature").listFiles().orEmpty().filter { it.isDirectory }.sorted()
        .map { "feature/${it.name}/src/commonMain/kotlin" }

    /** `file function parameter` of a capture allowed in an effect. Keep this short and say why. */
    private val allowed: Set<String> = setOf(
        // Owned by the History metrics work, which changes this file now. It takes the fix there (#315).
        "CalendarHeatmap.kt CalendarHeatmap onPickDay",
    )

    @Test
    fun noEffectCallsACallerLambdaDirectly() {
        val files = sources.flatMap { dir ->
            File(Catalogues.root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
        assertTrue(files.size > 50, "found only ${files.size} sources")
        val found = files.flatMap { file -> captures(file.name, file.readText()) }.filterNot { it in allowed }
        assertTrue(found.isEmpty(), "effects that call a caller lambda directly, wrap it in rememberUpdatedState\n" + found.joinToString("\n"))
    }

    @Test
    fun theRuleCatchesACapturedLambdaAndPassesAnUpdatedOne() {
        val stale = """
            @Composable
            fun Screen(closed: Boolean, onClose: () -> Unit, label: String) {
                LaunchedEffect(closed) { if (closed) onClose() }
                Box(Modifier.pointerInput(Unit) { detectTapGestures { onClose.invoke() } })
            }
        """.trimIndent()
        assertEquals(listOf("A.kt Screen onClose", "A.kt Screen onClose"), captures("A.kt", stale))
        val fresh = """
            @Composable
            fun Screen(closed: Boolean, onClose: () -> Unit, onPick: ((Int) -> Unit)? = null) {
                val close by rememberUpdatedState(onClose)
                // onClose() in a comment and "onClose()" in a string do not count.
                LaunchedEffect(closed) { if (closed) close(); println("onClose()") }
                Button(onClick = onClose) {}
            }
        """.trimIndent()
        assertEquals(emptyList(), captures("A.kt", fresh))
    }

    /** `file function parameter` for each function typed parameter an effect block names. */
    private fun captures(name: String, source: String): List<String> {
        val text = blankCommentsAndStrings(source)
        val out = ArrayList<String>()
        for (fn in function.findAll(text)) {
            val open = fn.range.last
            val close = matching(text, open) ?: continue
            val lambdas = parameters(text.substring(open + 1, close)).filter { (_, type) -> isFunctionType(type) }.map { it.first }
            if (lambdas.isEmpty()) continue
            val bodyOpen = (close + 1 until text.length).firstOrNull { text[it] == '{' || text[it] == '=' }?.takeIf { text[it] == '{' } ?: continue
            val bodyClose = matching(text, bodyOpen) ?: continue
            val body = text.substring(bodyOpen, bodyClose + 1)
            for (call in effect.findAll(body)) {
                val argsClose = matching(body, call.range.last) ?: continue
                val blockOpen = body.indexOf('{', argsClose).takeIf { it >= 0 && body.substring(argsClose + 1, it).isBlank() } ?: continue
                val block = body.substring(blockOpen, (matching(body, blockOpen) ?: continue) + 1)
                lambdas.filter { Regex("""(?<![\w.])$it\b""").containsMatchIn(block) }.forEach { out += "$name ${fn.groupValues[1]} $it" }
            }
        }
        return out
    }

    private val function = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(""")
    private val effect = Regex("""\b(LaunchedEffect|DisposableEffect|LifecycleStartEffect|LifecycleResumeEffect|pointerInput)\s*\(""")

    /** A parameter type with an arrow before any default value is a function type. */
    private fun isFunctionType(type: String): Boolean = "->" in type.substringBefore('=')

    /** `name to type` of each top level parameter in a parameter list. */
    private fun parameters(list: String): List<Pair<String, String>> {
        val parts = ArrayList<String>()
        var depth = 0
        var start = 0
        list.forEachIndexed { i, c ->
            when (c) {
                '(', '<', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                '>' -> if (i == 0 || list[i - 1] != '-') depth--
                ',' -> if (depth == 0) {
                    parts += list.substring(start, i)
                    start = i + 1
                }
            }
        }
        parts += list.substring(start)
        return parts.mapNotNull { part ->
            val p = part.trim().replace(Regex("""^(@\w+(\([^)]*\))?\s+)*"""), "").removePrefix("vararg ").removePrefix("crossinline ").removePrefix("noinline ")
            val colon = p.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
            p.substring(0, colon).trim() to p.substring(colon + 1).trim()
        }
    }

    /** The index of the bracket that closes the one at [open], or null. */
    private fun matching(text: String, open: Int): Int? {
        val pair = when (text[open]) {
            '(' -> ')'
            '{' -> '}'
            else -> return null
        }
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                text[open] -> depth++
                pair -> if (--depth == 0) return i
            }
        }
        return null
    }

    /** [source] with comments and string contents replaced by spaces, so brackets and names in them do not count. */
    private fun blankCommentsAndStrings(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        fun blank(until: Int) {
            for (j in i until until) out.append(if (source[j] == '\n') '\n' else ' ')
            i = until
        }
        while (i < source.length) {
            when {
                source.startsWith("//", i) -> blank(source.indexOf('\n', i).let { if (it < 0) source.length else it })
                source.startsWith("/*", i) -> blank(source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 })
                source.startsWith("\"\"\"", i) -> blank(source.indexOf("\"\"\"", i + 3).let { if (it < 0) source.length else it + 3 })
                source[i] == '"' -> {
                    var j = i + 1
                    while (j < source.length && source[j] != '"' && source[j] != '\n') j += if (source[j] == '\\') 2 else 1
                    blank(minOf(j + 1, source.length))
                }
                source[i] == '\'' && i + 2 < source.length && (source[i + 2] == '\'' || source[i + 1] == '\\') -> {
                    val end = source.indexOf('\'', if (source[i + 1] == '\\') i + 3 else i + 2)
                    blank(if (end < 0) source.length else end + 1)
                }
                else -> {
                    out.append(source[i])
                    i++
                }
            }
        }
        return out.toString()
    }
}
