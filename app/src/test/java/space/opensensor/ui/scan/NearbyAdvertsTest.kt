package space.opensensor.ui.scan

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.opensensor.data.ble.BleScanner
import space.opensensor.data.ble.ScannedDevice

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyAdvertsTest {
    @Test
    fun `100 adverts per second touch Room once a minute and publish at most 4 times a second`() = runTest {
        val touches = mutableListOf<Long>()
        val now = { testScheduler.currentTime }
        val adverts = NearbyAdverts(touch = { touches += now() }, nowMs = now)
        val emissions = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { adverts.seen.collect { if (it.isNotEmpty()) emissions += now() } }
        val events = flow {
            repeat(13_000) {
                // 130 s at 100 per second
                emit(BleScanner.Event.Seen(ScannedDevice("AA:BB", "AQ", -50 - it % 10, false, now())))
                delay(10)
            }
        }
        assertNull(adverts.collect(events))

        assertEquals(listOf(0L, 60_000L, 120_000L), touches)
        val perSecond = emissions.groupingBy { it / 1_000 }.eachCount()
        assertTrue("emissions per second $perSecond", perSecond.values.all { it <= 4 })
        assertTrue(emissions.size >= 4 * 129)
        assertEquals(1, adverts.seen.value.size)
    }

    @Test
    fun `a failure ends the session and is returned`() = runTest {
        val adverts = NearbyAdverts(touch = {}, nowMs = { testScheduler.currentTime })
        val failed = BleScanner.Event.Failed(2, "registration")
        val events = flow {
            emit(BleScanner.Event.Seen(ScannedDevice("AA:BB", "AQ", -50, false, 0)))
            emit(failed)
            delay(1_000)
            emit(BleScanner.Event.Seen(ScannedDevice("CC:DD", "AQ", -50, false, 0)))
        }
        assertEquals(failed, adverts.collect(events))
        assertEquals(setOf("AA:BB"), adverts.seen.value.keys)
    }

    @Test
    fun `quiet devices drop out of the list`() = runTest {
        val now = { testScheduler.currentTime }
        val adverts = NearbyAdverts(touch = {}, nowMs = now)
        val events = flow {
            emit(BleScanner.Event.Seen(ScannedDevice("AA:BB", "AQ", -50, false, now())))
            repeat(200) {
                emit(BleScanner.Event.Seen(ScannedDevice("CC:DD", "AQ", -50, false, now())))
                delay(100)
            }
        }
        adverts.collect(events)
        assertEquals(setOf("CC:DD"), adverts.seen.value.keys)
    }

    @Test
    fun `a new session within a minute does not touch again`() = runTest {
        var touches = 0
        val now = { testScheduler.currentTime }
        val adverts = NearbyAdverts(touch = { touches++ }, nowMs = now)
        val one = flow { emit(BleScanner.Event.Seen(ScannedDevice("AA:BB", "AQ", -50, false, now()))) }
        adverts.collect(one)
        delay(30_000)
        adverts.collect(one)
        assertEquals(1, touches)
        delay(30_000)
        adverts.collect(one)
        assertEquals(2, touches)
    }

    @Test
    fun `every advert under the service UUID is listed whatever its name`() = runTest {
        val adverts = NearbyAdverts(touch = {}, nowMs = { testScheduler.currentTime })
        val events = flow {
            emit(BleScanner.Event.Seen(ScannedDevice("AA:BB", "OS-89ab", -50, false, 0)))
            emit(BleScanner.Event.Seen(ScannedDevice("CC:DD", "SL-0001", -50, false, 0)))
            emit(BleScanner.Event.Seen(ScannedDevice("EE:FF", null, -50, false, 0)))
        }
        adverts.collect(events)
        assertEquals(mapOf("AA:BB" to "OS-89ab", "CC:DD" to "SL-0001", "EE:FF" to null), adverts.seen.value.mapValues { it.value.name })
    }
}
