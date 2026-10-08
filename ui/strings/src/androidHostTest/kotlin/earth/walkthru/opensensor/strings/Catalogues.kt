package earth.walkthru.opensensor.strings

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** File helpers for the host copy tests. They read the repository sources, never a build output. */
internal object Catalogues {
    /** The repository root. Gradle runs host tests from the module directory. */
    val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "gradlew").isFile }

    /** Directories that never hold source catalogues. */
    private val skipped = setOf("build", ".gradle", ".git", ".claude", ".idea", ".kotlin", "node_modules", "DerivedData")

    /** A source catalogue. Android `res/values*` or Compose `composeResources/values*`. */
    private val catalogue = Regex(""".*/src/[^/]+/(res|composeResources)/values[^/]*/strings\.xml""")

    /** Every user-facing string catalogue in the repository, Android and Compose resources. */
    fun all(): List<File> = root.walkTopDown()
        .onEnter { it == root || it.name !in skipped }
        .filter { it.isFile && catalogue.matches(it.invariantSeparatorsPath) }
        .sortedBy { it.invariantSeparatorsPath }
        .toList()

    fun relative(file: File): String = file.relativeTo(root).invariantSeparatorsPath

    fun isCompose(file: File): Boolean = "/composeResources/" in file.invariantSeparatorsPath

    /** Strings by name, plurals as `name/quantity`, with the text as written in the file. */
    fun parse(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = LinkedHashMap<String, String>()
        val strings = doc.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val e = strings.item(i) as Element
            out[e.getAttribute("name")] = e.textContent
        }
        val plurals = doc.getElementsByTagName("plurals")
        for (i in 0 until plurals.length) {
            val p = plurals.item(i) as Element
            val items = p.getElementsByTagName("item")
            for (j in 0 until items.length) {
                val item = items.item(j) as Element
                out[p.getAttribute("name") + "/" + item.getAttribute("quantity")] = item.textContent
            }
        }
        return out
    }
}
