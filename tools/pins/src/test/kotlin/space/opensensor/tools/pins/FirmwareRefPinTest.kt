package space.opensensor.tools.pins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `verify.yml` and `release.yml` check out opensensor-space-firmware at the commit the bundled
 * templates were copied from (FirmwareTemplatesTest). The `ref` is a literal in each workflow, so
 * this test keeps the two from drifting apart.
 */
class FirmwareRefPinTest {
    private fun firmwareRefs(workflow: String): List<String> {
        val text = Repo.text(".github/workflows/$workflow")
        return Regex("""repository: walkthru-earth/opensensor-space-firmware\s*\n\s*ref: (\S+)""")
            .findAll(text).map { it.groupValues[1] }.toList()
    }

    @Test
    fun everyWorkflowPinsTheSameFullCommit() {
        val verify = firmwareRefs("verify.yml")
        val release = firmwareRefs("release.yml")
        assertEquals(1, verify.size, "verify.yml firmware checkouts")
        assertEquals(1, release.size, "release.yml firmware checkouts")
        assertTrue(Regex("[0-9a-f]{40}").matches(verify.single()), verify.single())
        assertEquals(verify.single(), release.single())
    }
}
