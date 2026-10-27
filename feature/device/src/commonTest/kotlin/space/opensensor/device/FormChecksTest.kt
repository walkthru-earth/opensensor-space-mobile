package space.opensensor.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormChecksTest {
    @Test
    fun aPassphraseFitsEightToSixtyThreeBytes() {
        assertNull(pskProblem(""), "nothing typed shows no error")
        assertEquals(PskProblem.SHORT, pskProblem("a".repeat(7)))
        assertNull(pskProblem("a".repeat(8)))
        assertNull(pskProblem("a".repeat(63)))
        assertEquals(PskProblem.LONG, pskProblem("a".repeat(64)))
    }

    @Test
    fun aPassphraseCountsUtf8BytesNotLetters() {
        // Four Arabic letters are eight bytes, so they are long enough.
        assertNull(pskProblem("سلام"))
        // Thirty two Arabic letters are 64 bytes, one over the limit.
        assertEquals(PskProblem.LONG, pskProblem("س".repeat(32)))
    }

    @Test
    fun onlyAFittingPassphraseCanBeSaved() {
        assertFalse(pskSavable(""))
        assertFalse(pskSavable("short"))
        assertFalse(pskSavable("a".repeat(64)))
        assertTrue(pskSavable("a".repeat(8)))
    }

    @Test
    fun aStickerForAnotherModelSaysSoInsteadOfABareError() {
        assertNull(stickerProblem(""))
        assertNull(stickerProblem("  "))
        assertNull(stickerProblem("PMS5003T-202604081332"))
        assertEquals(StickerProblem.OTHER_MODEL, stickerProblem("PMS5003-202604081332"))
        assertEquals(StickerProblem.OTHER_MODEL, stickerProblem("PMSA003-202604081332"))
        assertEquals(StickerProblem.FORMAT, stickerProblem("PMS5003T"))
        assertEquals(StickerProblem.FORMAT, stickerProblem("not a sticker"))
    }

    @Test
    fun theCountryCodeIsEmptyOrTwoLetters() {
        assertTrue(countryValid(""))
        assertTrue(countryValid("GH"))
        assertFalse(countryValid("G"))
        assertFalse(countryValid("G1"))
        assertFalse(countryValid("12"))
        assertFalse(countryValid("GHA"))
    }
}
