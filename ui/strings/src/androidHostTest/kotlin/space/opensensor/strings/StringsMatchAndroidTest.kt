package space.opensensor.strings

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
    fun sharedKeysSayTheSameAsAndroid() = compare("values")

    /** The Arabic copies of the shared keys match too, so a translation never drifts between apps. */
    @Test
    fun sharedArabicKeysSayTheSameAsAndroid() = compare("values-ar")

    private fun compare(folder: String) {
        val android = Catalogues.parse(File(Catalogues.root, "app/src/main/res/$folder/strings.xml"))
        val shared = Catalogues.parse(File("src/commonMain/composeResources/$folder/strings.xml"))
        val both = shared.keys.intersect(android.keys)
        assertTrue(both.containsAll(listOf("app_name", "msg_up_to_date", "nav_disconnect", "action_cancel", "perm_continue")), "$folder shared keys $both")
        // Android escapes an apostrophe with a backslash, Compose resources do not. Compare the text shown.
        both.forEach { key -> assertEquals(android.getValue(key).replace("\\'", "'"), shared.getValue(key), "$folder $key") }
    }
}
