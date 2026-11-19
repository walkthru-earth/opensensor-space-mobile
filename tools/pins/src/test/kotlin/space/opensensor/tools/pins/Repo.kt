package space.opensensor.tools.pins

import java.io.File

/** The repository checkout, which the Gradle test task passes as `opensensor.repoDir`. */
internal object Repo {
    private val root: File = File(checkNotNull(System.getProperty("opensensor.repoDir")) { "opensensor.repoDir is not set" })

    fun text(path: String): String = File(root, path).readText()

    /** The value of `name = "..."` in the `[versions]` table of `gradle/libs.versions.toml`. */
    fun catalogVersion(name: String): String {
        val versions = text("gradle/libs.versions.toml").substringAfter("[versions]").substringBefore("\n[")
        val match = Regex("""(?m)^$name\s*=\s*"([^"]+)"""").find(versions)
        return checkNotNull(match) { "no $name in the [versions] table" }.groupValues[1]
    }
}
