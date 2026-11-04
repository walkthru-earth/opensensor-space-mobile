package space.opensensor.today

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.jetbrains.compose.resources.stringResource
import space.opensensor.settings.DetailLevel
import space.opensensor.today.resources.Res
import space.opensensor.today.resources.flush
import space.opensensor.today.resources.today_none
import space.opensensor.today.resources.today_none_clock
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Clock card is Developer only, so Today so far says why no timed readings show while the
 * phone has not set the clock yet. Finalize stays on the Storage card before any STATUS arrives.
 */
@OptIn(ExperimentalTestApi::class)
class ClockPendingCardsTest {
    private fun todaySoFar(clockMissing: Boolean, level: DetailLevel, expectClock: Int, expectNone: Int) = runComposeUiTest {
        var clock = ""
        var none = ""
        setContent {
            MaterialTheme {
                clock = stringResource(Res.string.today_none_clock)
                none = stringResource(Res.string.today_none)
                TodaySoFarCard(today = null, clockMissing = clockMissing, level = level)
            }
        }
        onAllNodesWithText(clock).assertCountEquals(expectClock)
        onAllNodesWithText(none).assertCountEquals(expectNone)
    }

    @Test
    fun aSimpleUserReadsThatThePhoneSetsTheClock() = todaySoFar(clockMissing = true, level = DetailLevel.SIMPLE, expectClock = 1, expectNone = 0)

    @Test
    fun aDetailedUserReadsThatThePhoneSetsTheClock() = todaySoFar(clockMissing = true, level = DetailLevel.DETAILED, expectClock = 1, expectNone = 0)

    @Test
    fun aSetClockKeepsThePlainNoReadingsLine() = todaySoFar(clockMissing = false, level = DetailLevel.SIMPLE, expectClock = 0, expectNone = 1)

    @Test
    fun finalizeStaysOnTheStorageCardBeforeTheFirstStatus() = runComposeUiTest {
        var label = ""
        var flushed = 0
        setContent {
            MaterialTheme {
                label = stringResource(Res.string.flush)
                StorageCard(status = null, receivedAtEpochMs = null, level = DetailLevel.DETAILED, enabled = true, onFlush = { flushed++ })
            }
        }
        onNodeWithText(label).performClick()
        assertEquals(1, flushed)
    }
}
