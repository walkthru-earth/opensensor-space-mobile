package space.opensensor.tools.pins

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * iOS links the vendored H3 C library (`core/publicfeed/src/nativeInterop/h3c`) and Android and the
 * JVM use `com.uber:h3` from the version catalog, so both phones index cells with the same H3. The
 * h3-java release names its own patch version (4.5.1 wraps H3 C v4.5.0), so major and minor must
 * match and the patch may differ.
 */
class H3VersionPinTest {
    private fun cDefine(header: String, name: String): Int {
        val match = Regex("""(?m)^#define $name (\d+)""").find(header)
        return checkNotNull(match) { "no $name in h3api.h" }.groupValues[1].toInt()
    }

    @Test
    fun vendoredCLibraryMatchesTheJvmMajorAndMinor() {
        val header = Repo.text("core/publicfeed/src/nativeInterop/h3c/include/h3api.h")
        val c = "${cDefine(header, "H3_VERSION_MAJOR")}.${cDefine(header, "H3_VERSION_MINOR")}"
        val jvm = Repo.catalogVersion("h3").split('.').take(2).joinToString(".")
        assertEquals(jvm, c, "H3 C $c against com.uber:h3 ${Repo.catalogVersion("h3")}")
    }
}
