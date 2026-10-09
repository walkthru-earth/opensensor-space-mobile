package earth.walkthru.opensensor.strings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * English stays in Compose resources. The Kotlin presentation sources of the iOS framework
 * (`ios/shared/src/commonMain` and `iosMain`) must not pass a string literal to `Text`, and must not
 * hold an English sentence in a string literal. A new literal fails the build, an exception goes in
 * [allowed] with its reason.
 */
class PresentationLiteralsTest {
    private val sources = listOf("ios/shared/src/commonMain/kotlin", "ios/shared/src/iosMain/kotlin")

    /** `file name` to the literal text allowed there. Keep this short and say why. */
    private val allowed: Set<Pair<String, String>> = setOf(
        // The bottom bar tab labels belong to the app shell, which moves them to resources with the shell.
        "AppRoot.kt" to "My devices",
    )

    /** `Text("...")`, `Text(text = "...")` or `Text(if (x) "..." else ...)`. */
    private val textLiteral = Regex("""\bText\(\s*(text\s*=\s*)?(if\s*\(.*?\)\s*)?"([^"]*)"""")

    /** A string literal that reads as English words, such as `"Looking for sensors"`. */
    private val sentence = Regex(""""([A-Z][a-z]+(?: [^"]*)? [a-z][^"]*)"""")

    private fun presentationFiles(): List<File> = sources.flatMap { dir ->
        File(Catalogues.root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** Code lines only. Comment lines and KDoc never reach the screen. */
    private fun codeLines(file: File): List<Pair<Int, String>> = file.readLines().mapIndexedNotNull { i, line ->
        val t = line.trim()
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) null else (i + 1) to line.substringBefore(" // ")
    }

    @Test
    fun scansThePresentationSources() {
        val names = presentationFiles().map { it.name }
        assertTrue(names.containsAll(listOf("CompanionScreen.kt", "CompanionState.kt")), "files $names")
    }

    @Test
    fun noTextLiterals() {
        val found = presentationFiles().flatMap { file ->
            codeLines(file).flatMap { (n, line) ->
                textLiteral.findAll(line).map { it.groupValues[3] }
                    .filterNot { (file.name to it) in allowed }
                    .map { "${Catalogues.relative(file)}:$n Text(\"$it\")" }
            }
        }
        assertTrue(found.isEmpty(), "English literals in Text, move them to :ui:strings\n${found.joinToString("\n")}")
    }

    @Test
    fun noEnglishSentenceLiterals() {
        val found = presentationFiles().flatMap { file ->
            codeLines(file).flatMap { (n, line) ->
                sentence.findAll(line).map { it.groupValues[1] }
                    .filterNot { (file.name to it) in allowed }
                    .map { "${Catalogues.relative(file)}:$n \"$it\"" }
            }
        }
        assertTrue(found.isEmpty(), "English literals, move them to :ui:strings\n${found.joinToString("\n")}")
    }

    @Test
    fun thePatternsCatchLiterals() {
        assertTrue(textLiteral.containsMatchIn("""Text("Now", style = s)"""))
        assertTrue(textLiteral.containsMatchIn("""Text(if (busy) "Saving…" else "Save")"""))
        assertTrue(textLiteral.containsMatchIn("""Text(text = "Connect")"""))
        assertTrue(!textLiteral.containsMatchIn("""Text(stringResource(Res.string.companion_now))"""))
        assertTrue(sentence.containsMatchIn("""message = "Looking for nearby sensors…""""))
        assertTrue(!sentence.containsMatchIn("""error("missing station")"""))
    }
}
