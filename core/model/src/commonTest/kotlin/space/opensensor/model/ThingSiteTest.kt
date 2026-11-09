package space.opensensor.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The owner site rules mirror the firmware `check_owner_value`. Each rule has a case that breaks it. */
class ThingSiteTest {
    private fun check(field: SiteField, value: String) = SiteRules.check(field, value)

    @Test
    fun capsAreUtf8BytesFromTheFirmwareTable() {
        assertEquals(64, SiteField.TITLE.maxBytes)
        assertEquals(64, SiteField.PROVIDER.maxBytes)
        assertEquals(96, SiteField.URL.maxBytes)
        assertEquals(160, SiteField.DESCRIPTION.maxBytes)
        assertEquals(32, SiteField.DEPLOYMENT_ID.maxBytes)
    }

    @Test
    fun eachTextFieldTakesItsCapAndRefusesOneByteMore() {
        listOf(SiteField.TITLE, SiteField.PROVIDER, SiteField.DESCRIPTION).forEach { f ->
            assertNull(check(f, "a".repeat(f.maxBytes)), "$f at cap")
            assertEquals(SiteProblem.TOO_LONG, check(f, "a".repeat(f.maxBytes + 1)), "$f over cap")
        }
        val url = "https://" + "a".repeat(SiteField.URL.maxBytes - 8)
        assertNull(check(SiteField.URL, url))
        assertEquals(SiteProblem.TOO_LONG, check(SiteField.URL, url + "a"))
        assertNull(check(SiteField.DEPLOYMENT_ID, "a".repeat(32)))
        assertEquals(SiteProblem.TOO_LONG, check(SiteField.DEPLOYMENT_ID, "a".repeat(33)))
    }

    @Test
    fun theCapCountsBytesNotCharacters() {
        // "ب" is two UTF-8 bytes, so 32 of them fill the 64 byte title.
        assertNull(check(SiteField.TITLE, "ب".repeat(32)))
        assertEquals(SiteProblem.TOO_LONG, check(SiteField.TITLE, "ب".repeat(33)))
        // An emoji is one code point, two chars and four bytes.
        assertEquals(SiteProblem.TOO_LONG, check(SiteField.TITLE, "🌍".repeat(17)))
        assertNull(check(SiteField.TITLE, "🌍".repeat(16)))
    }

    @Test
    fun emptyAlwaysPassesBecauseItClears() {
        SiteField.entries.forEach { assertNull(check(it, ""), "$it empty") }
    }

    @Test
    fun textRefusesC0DelAndC1ButKeepsOtherUnicode() {
        listOf(SiteField.TITLE, SiteField.PROVIDER, SiteField.DESCRIPTION).forEach { f ->
            listOf("a\u0000b", "a\tb", "a\nb", "a\rb", "a\u001fb", "a\u007fb", "a\u0080b", "a\u009fb").forEach { v ->
                assertEquals(SiteProblem.CONTROL, check(f, v), "$f ${v.encodeToByteArray().toList()}")
            }
            listOf("Accra Ridge", "a b", "مدرسة أكرا", "\"quoted\" \\ back", "café").forEach { v ->
                assertNull(check(f, v), "$f $v")
            }
        }
    }

    @Test
    fun aLoneSurrogateIsNotUtf8() {
        assertEquals(SiteProblem.ENCODING, check(SiteField.TITLE, "a\uD83C"))
        assertEquals(SiteProblem.ENCODING, check(SiteField.DESCRIPTION, "\uDF0Da"))
        assertEquals(SiteProblem.ENCODING, check(SiteField.URL, "https://a\uD83C"))
    }

    @Test
    fun urlNeedsHttpsAndALetterOrDigitFirst() {
        assertNull(check(SiteField.URL, "https://opensensor.space"))
        assertNull(check(SiteField.URL, "https://1.example/path?q=1#top"))
        listOf("http://opensensor.space", "HTTPS://opensensor.space", "https://", "https://-a.example", "https://.example", "opensensor.space", "ftp://a").forEach {
            assertEquals(SiteProblem.SCHEME, check(SiteField.URL, it), it)
        }
    }

    @Test
    fun urlRefusesUserInfoInTheAuthorityOnly() {
        assertEquals(SiteProblem.USER_INFO, check(SiteField.URL, "https://user@example.com"))
        assertEquals(SiteProblem.USER_INFO, check(SiteField.URL, "https://a:b@example.com/x"))
        // An @ after the authority is a path or query character.
        assertNull(check(SiteField.URL, "https://example.com/@team"))
        assertNull(check(SiteField.URL, "https://example.com?u=a@b"))
        assertNull(check(SiteField.URL, "https://example.com#a@b"))
    }

    @Test
    fun urlKeepsTheRfc3986SetAndRefusesQuotesSpacesAndBackslash() {
        assertNull(check(SiteField.URL, "https://a.example/-._~:/?#[]@!$&'()*+,;=%20"))
        listOf("https://a.example/a b", "https://a.example/\"x\"", "https://a.example/a\\b", "https://a.example/<x>", "https://a.example/é", "https://a.example/{x}", "https://a.example/a|b", "https://a.example/`x`", "https://a.example/^").forEach {
            assertEquals(SiteProblem.CHARSET, check(SiteField.URL, it), it)
        }
        listOf("https://a.example/\u0000", "https://a.example/\t", "https://a.example/\u007f").forEach {
            assertEquals(SiteProblem.CONTROL, check(SiteField.URL, it), it)
        }
    }

    @Test
    fun deploymentIsLowercaseDigitsAndInnerDashes() {
        listOf("accra-1", "a", "0", "school-roof-2026").forEach { assertNull(check(SiteField.DEPLOYMENT_ID, it), it) }
        listOf("Accra", "accra_1", "accra 1", "accra.1", "أكرا").forEach { assertEquals(SiteProblem.CHARSET, check(SiteField.DEPLOYMENT_ID, it), it) }
        listOf("-accra", "accra-", "-", "unknown").forEach { assertEquals(SiteProblem.RESERVED, check(SiteField.DEPLOYMENT_ID, it), it) }
        assertNull(check(SiteField.DEPLOYMENT_ID, "unknown-1"))
    }

    @Test
    fun problemsListsTheFirstProblemOfEachField() {
        val site = ThingSite(title = "ok", url = "http://x", deploymentId = "Bad", description = "a".repeat(161))
        assertEquals(
            mapOf(SiteField.URL to SiteProblem.SCHEME, SiteField.DEPLOYMENT_ID to SiteProblem.CHARSET, SiteField.DESCRIPTION to SiteProblem.TOO_LONG),
            site.problems(),
        )
        assertTrue(ThingSite().problems().isEmpty())
    }

    @Test
    fun changesSendAnEmptiedValueAndLeaveAnUnchangedEmptyOut() {
        val stored = ThingSite(title = "Roof", provider = "", url = "https://a.example", description = "", deploymentId = "")
        val draft = stored.copy(title = "", provider = "", deploymentId = "accra-1")
        assertEquals(mapOf(SiteField.TITLE to "", SiteField.DEPLOYMENT_ID to "accra-1"), draft.changesFrom(stored))
        // A value the board did not report (null) and an empty draft are the same, nothing to send.
        assertTrue(ThingSite(title = "").changesFrom(ThingSite()).isEmpty())
        assertTrue(stored.changesFrom(stored).isEmpty())
    }

    @Test
    fun withAndGetCoverEveryField() {
        SiteField.entries.forEach { f -> assertEquals("v", ThingSite().with(f, "v")[f]) }
    }
}
