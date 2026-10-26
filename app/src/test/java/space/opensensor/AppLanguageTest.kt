package space.opensensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** The language `res/` resolved leads the list, the rest keep their order (AppLanguage). */
class AppLanguageTest {
    private val fr = Locale.forLanguageTag("fr-FR")
    private val arMa = Locale.forLanguageTag("ar-MA")
    private val en = Locale.forLanguageTag("en-GB")

    @Test
    fun theResolvedLanguageMovesToTheFront() {
        assertEquals(listOf(arMa, fr, en), AppLanguage.reorder(listOf(fr, en, arMa), "ar"))
        assertEquals(listOf(arMa, fr), AppLanguage.reorder(listOf(fr, arMa), "ar"))
    }

    @Test
    fun nothingChangesWhenItLeadsOrIsAbsent() {
        assertNull(AppLanguage.reorder(listOf(arMa, fr), "ar"))
        assertNull(AppLanguage.reorder(listOf(fr), "en"))
        assertNull(AppLanguage.reorder(emptyList(), "ar"))
    }
}
