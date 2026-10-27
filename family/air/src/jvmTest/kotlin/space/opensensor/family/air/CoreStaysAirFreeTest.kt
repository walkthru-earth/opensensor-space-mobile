package space.opensensor.family.air

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins #188. No production source of `:core:*` names particulate air quality, its WHO or EPA science,
 * the Plantower module or the moods, and no core module builds against this family outside its tests.
 * Bundled JSON data (board templates and profiles) is not source and stays out of the scan.
 */
class CoreStaysAirFreeTest {
    private val core = File(requireNotNull(System.getProperty("opensensor.coreDir")) { "opensensor.coreDir not set" })

    private val airTerm = Regex(
        listOf(
            """\bWHO\b""", "Who[A-Z_]", """\bwho[A-Z_-]""",
            """\bEPA\b""", "EPA_", "_EPA", "Epa[A-Z]", """\bepa\b""", """\bepa[A-Z]""", """_epa\b""",
            "[Pp]lantower", "PLANTOWER", "PMS[0-9A-Z_]", "Pms[A-Z]", "pms_", """\bpms[A-Za-z0-9]""",
            "[Mm]ood", "MOOD",
            "PM[0-9]", "pm[0-9]", "Pm[0-9]", """\bPM\b""", """\bpm\b""", """\bPm\b""", """\bpm[A-Z]""", """[a-z]Pm\b""", "[a-z]Pm[A-Z]",
            "airquality", "family[.]air", ":family:air",
        ).joinToString("|"),
    )

    @Test
    fun `core production sources name no air term`() {
        val sources = core.walkTopDown()
            .onEnter { it.name != "build" }
            .filter { it.isFile && it.extension == "kt" && it.invariantSeparatorsPath.contains(Regex("/src/[A-Za-z]*Main/")) }
            .toList()
        assertTrue(sources.size > 100, "found only ${sources.size} core sources under $core")
        val hits = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line -> if (airTerm.containsMatchIn(line)) "${file.relativeTo(core)}:${i + 1}: ${line.trim()}" else null }
        }
        assertTrue(hits.isEmpty(), "air terms in :core:* sources\n" + hits.joinToString("\n"))
    }

    @Test
    fun `no core module builds against the air family outside its tests`() {
        val builds = core.walkTopDown().onEnter { it.name != "build" && it.name != "src" }.filter { it.name == "build.gradle.kts" }.toList()
        assertTrue(builds.isNotEmpty())
        val hits = builds.flatMap { file ->
            var block = ""
            file.readLines().mapIndexedNotNull { i, line ->
                Regex("""(\w+)\.dependencies\s*\{""").find(line)?.let { block = it.groupValues[1] }
                if (line.trim() == "}" && line.indexOf('}') <= 8) block = ""
                if (":family:" in line && !block.endsWith("Test")) "${file.relativeTo(core)}:${i + 1}: ${line.trim()}" else null
            }
        }
        assertTrue(hits.isEmpty(), "core modules depending on a family outside tests\n" + hits.joinToString("\n"))
    }
}
