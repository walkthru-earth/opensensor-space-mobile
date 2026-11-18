package space.opensensor.tools.pins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pre-commit ktlint hook downloads the ktlint version in `tools/hooks/ktlint.sh`. Dependabot does
 * not read shell scripts, so the version also lives in the version catalog as `ktlint`, where the
 * Gradle updates see it. When an update bumps the catalog, this test fails until the script carries
 * the same version and the SHA-256 of its new release file.
 */
class KtlintPinTest {
    private val script = Repo.text("tools/hooks/ktlint.sh")

    @Test
    fun hookDownloadsTheCatalogVersion() {
        val version = Regex("""(?m)^KTLINT_VERSION="([^"]+)"""").find(script)?.groupValues?.get(1)
        assertEquals(Repo.catalogVersion("ktlint"), version)
    }

    @Test
    fun catalogDeclaresTheKtlintLibrarySoDependabotTracksIt() {
        val catalog = Repo.text("gradle/libs.versions.toml")
        assertTrue(
            Regex("""(?m)^ktlint-cli\s*=\s*\{\s*module\s*=\s*"com\.pinterest\.ktlint:ktlint-cli",\s*version\.ref\s*=\s*"ktlint"\s*}""")
                .containsMatchIn(catalog),
        )
    }

    @Test
    fun hookPinsAFullSha256() {
        val sha = Regex("""(?m)^KTLINT_SHA256="([^"]*)"""").find(script)?.groupValues?.get(1).orEmpty()
        assertTrue(Regex("[0-9a-f]{64}").matches(sha), sha)
    }
}
