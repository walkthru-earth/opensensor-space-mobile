package space.opensensor.tools.pins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CI installs pre-commit from `tools/ci/requirements.txt`, an exact pin that the Dependabot pip
 * updates see. The hooks job must not run a floating `pipx run pre-commit`.
 */
class PreCommitPinTest {
    @Test
    fun requirementsPinOneExactVersion() {
        val lines = Repo.text("tools/ci/requirements.txt").lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals(1, lines.size, lines.toString())
        assertNotNull(Regex("""pre-commit==\d+\.\d+\.\d+""").matchEntire(lines.single()), lines.single())
    }

    @Test
    fun hooksJobInstallsThePinnedVersion() {
        val verify = Repo.text(".github/workflows/verify.yml")
        assertTrue("-r tools/ci/requirements.txt" in verify)
        assertFalse(Regex("""pipx run pre-commit""").containsMatchIn(verify))
    }

    @Test
    fun dependabotWatchesTheRequirements() {
        val dependabot = Repo.text(".github/dependabot.yml")
        assertTrue(Regex("""package-ecosystem: pip\s*\n\s*directory: /tools/ci""").containsMatchIn(dependabot))
    }
}
