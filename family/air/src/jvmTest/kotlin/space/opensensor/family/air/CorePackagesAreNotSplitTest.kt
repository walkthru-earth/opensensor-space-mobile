package space.opensensor.family.air

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins #316. Each package of a `:core:*` production source belongs to one module, so `internal`
 * and a reader's "where does this live" never span two builds. A module is the nearest directory
 * above `src/` that holds a `build.gradle.kts`, so `core/thing/testkit` is its own module.
 */
class CorePackagesAreNotSplitTest {
    private val core = File(requireNotNull(System.getProperty("opensensor.coreDir")) { "opensensor.coreDir not set" })

    @Test
    fun `no package is declared in two core modules`() {
        val owners = mutableMapOf<String, MutableSet<String>>()
        core.walkTopDown()
            .onEnter { it.name != "build" }
            .filter { it.isFile && it.extension == "kt" && it.invariantSeparatorsPath.contains(Regex("/src/[A-Za-z]*Main/")) }
            .forEach { file ->
                val pkg = file.useLines { lines -> lines.firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.trim() }
                    ?: return@forEach
                owners.getOrPut(pkg) { mutableSetOf() } += moduleOf(file)
            }
        assertTrue(owners.size > 10, "found only ${owners.size} core packages under $core")
        val split = owners.filterValues { it.size > 1 }.map { (pkg, modules) -> "$pkg in ${modules.sorted()}" }
        assertTrue(split.isEmpty(), "packages split across :core modules\n" + split.joinToString("\n"))
    }

    private fun moduleOf(file: File): String {
        var dir = file.invariantSeparatorsPath.substringBefore("/src/").let(::File)
        while (!File(dir, "build.gradle.kts").isFile) dir = dir.parentFile
        return dir.relativeTo(core).invariantSeparatorsPath
    }
}
