package earth.walkthru.opensensor.strings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A key in this catalogue that Android `strings.xml` also has keeps the same words, so the two apps
 * never drift while Android screens outside the shared modules still read their own copy.
 */
class StringsMatchAndroidTest {
    @Test
    fun sharedKeysSayTheSameAsAndroid() {
        val android = Catalogues.parse(File(Catalogues.root, "app/src/main/res/values/strings.xml"))
        val shared = Catalogues.parse(File("src/commonMain/composeResources/values/strings.xml"))
        val both = shared.keys.intersect(android.keys)
        assertTrue(both.containsAll(listOf("app_name", "now_waiting", "nav_disconnect", "action_cancel")), "shared keys $both")
        // Android escapes an apostrophe with a backslash, Compose resources do not. Compare the text shown.
        both.forEach { key -> assertEquals(android.getValue(key).replace("\\'", "'"), shared.getValue(key), key) }
    }
}
