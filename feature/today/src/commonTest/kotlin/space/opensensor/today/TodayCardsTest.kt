package space.opensensor.today

import space.opensensor.collection.SyncEndReason
import space.opensensor.data.SyncProgress
import space.opensensor.data.ble.ConfigSnapshot
import space.opensensor.data.history.pmsStatus
import space.opensensor.data.protocol.DeviceConfig
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.LiveSample
import space.opensensor.data.protocol.SensorConfig
import space.opensensor.data.sync.FileOutcome
import space.opensensor.settings.DetailLevel
import space.opensensor.today.TodayCard.BATCH_ESTIMATE
import space.opensensor.today.TodayCard.BOARD
import space.opensensor.today.TodayCard.CLOCK
import space.opensensor.today.TodayCard.DEVICE_INFO
import space.opensensor.today.TodayCard.ENVIRONMENT
import space.opensensor.today.TodayCard.NOW
import space.opensensor.today.TodayCard.PARTICLES
import space.opensensor.today.TodayCard.READINGS
import space.opensensor.today.TodayCard.SMART_SYNC
import space.opensensor.today.TodayCard.STORAGE
import space.opensensor.today.TodayCard.TODAY_SO_FAR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TodayCardsTest {
    private val waveshare = DeviceInfo(proto = 3, schema = "waveshare-sim7670g-telemetry-v5", dict = "a8605aafb6f76b794e9e7fb32927be6d203afeeb14527206c0e49c1eb0436809")
    private val cores3 = DeviceInfo(proto = 1, schema = "cores3-telemetry-v5", dict = "3b4d8500a19f42c6743b0f7d725276c96ca14a0c249f613048603afbc3cfaff9")
    private val particleSample = LiveSample.parse("""{"pms":4,"pm25":14.9}""")
    private val absentSensor = LiveSample.parse("""{"pms":0,"pm25":null}""")

    private fun state(info: DeviceInfo?, live: LiveSample?, level: DetailLevel, batch: Int? = null) = TodayUiState(
        snapshot = TodaySnapshot(
            info = info,
            live = live,
            config = batch?.let { ConfigSnapshot(DeviceConfig(sensor = SensorConfig(batchCandidate = it)), false, "{}", 0) },
        ),
        level = level,
    )

    @Test
    fun simpleShowsTheHeroTodaySyncAndClock() {
        assertEquals(
            listOf(NOW, TODAY_SO_FAR, ENVIRONMENT, SMART_SYNC, CLOCK),
            state(waveshare, particleSample, DetailLevel.SIMPLE).cards,
        )
    }

    @Test
    fun detailedAddsMeasurementsBoardStorageAndDevice() {
        assertEquals(
            listOf(NOW, TODAY_SO_FAR, ENVIRONMENT, SMART_SYNC, CLOCK, READINGS, PARTICLES, BOARD, STORAGE, DEVICE_INFO),
            state(waveshare, particleSample, DetailLevel.DETAILED).cards,
        )
    }

    @Test
    fun developerShowsTheSameCardsAsDetailed() {
        assertEquals(
            state(waveshare, particleSample, DetailLevel.DETAILED).cards,
            state(waveshare, particleSample, DetailLevel.DEVELOPER).cards,
        )
    }

    @Test
    fun aBoardTemperatureDeviceGetsTheEnvironmentHeroAndNoParticleCards() {
        // A CoreS3 sample without a PM sensor reports board temperature only, so neither the mood nor the ambient card applies.
        val boardOnly = LiveSample.parse("""{"pms":0,"imu_t":31.0}""")
        assertEquals(listOf(NOW, TODAY_SO_FAR, SMART_SYNC, CLOCK), state(cores3, boardOnly, DetailLevel.SIMPLE).cards)
        assertEquals(
            listOf(NOW, TODAY_SO_FAR, SMART_SYNC, CLOCK, BOARD, STORAGE, DEVICE_INFO),
            state(cores3, boardOnly, DetailLevel.DETAILED).cards,
        )
        // With its PM sensor the CoreS3 gets the mood but never the ambient card.
        assertEquals(listOf(NOW, TODAY_SO_FAR, SMART_SYNC, CLOCK), state(cores3, particleSample, DetailLevel.SIMPLE).cards)
    }

    @Test
    fun anAbsentParticleSensorNeverShowsAMood() {
        val cards = state(waveshare, absentSensor, DetailLevel.DETAILED).cards
        assertEquals(NOW, cards.first())
        assertFalse(PARTICLES in cards)
        assertFalse(READINGS in cards)
    }

    @Test
    fun noLiveSampleYetKeepsTheTopOfTheList() {
        val cards = state(waveshare, null, DetailLevel.SIMPLE).cards
        assertEquals(listOf(NOW, TODAY_SO_FAR, SMART_SYNC, CLOCK), cards)
    }

    @Test
    fun theBatchEstimateNeedsTheOptInAndADetailedLevel() {
        assertTrue(BATCH_ESTIMATE in state(waveshare, particleSample, DetailLevel.DETAILED, batch = 1).cards)
        assertFalse(BATCH_ESTIMATE in state(waveshare, particleSample, DetailLevel.SIMPLE, batch = 1).cards)
        assertFalse(BATCH_ESTIMATE in state(waveshare, particleSample, DetailLevel.DETAILED, batch = 0).cards)
        assertFalse(BATCH_ESTIMATE in state(waveshare, particleSample, DetailLevel.DETAILED).cards)
    }

    @Test
    fun theDeviceCardNeedsInfo() {
        assertFalse(DEVICE_INFO in state(null, null, DetailLevel.DEVELOPER).cards)
    }

    @Test
    fun cardKeysAreUniqueWithinOneList() {
        for (level in DetailLevel.entries) {
            for (live in listOf(particleSample, absentSensor, null)) {
                val keys = state(waveshare, live, level, batch = 1).cards.map { it.key }
                assertEquals(keys.size, keys.toSet().size, "duplicate key at $level with $live")
            }
        }
    }

    @Test
    fun syncMoodFollowsWhatThePlatformReports() {
        assertEquals(SyncMood.EMPTY, syncMood(SyncProgress(), TodayArchive()))
        assertEquals(SyncMood.READY, syncMood(SyncProgress(), TodayArchive(files = 3)))
        assertEquals(SyncMood.RUNNING, syncMood(SyncProgress(running = true), TodayArchive(files = 3)))
        assertEquals(SyncMood.CAUGHT_UP, syncMood(SyncProgress(finishedAtEpochMs = 1), TodayArchive()))
        assertEquals(
            SyncMood.SOME_FAILED,
            syncMood(SyncProgress(finishedAtEpochMs = 1, outcomes = listOf(FileOutcome.Failed("x.parquet", "crc"))), TodayArchive()),
        )
    }

    @Test
    fun anEarlyEndIsNeverCaughtUp() {
        val done = SyncProgress(finishedAtEpochMs = 1, endReason = SyncEndReason.COMPLETED)
        assertEquals(SyncMood.CAUGHT_UP, syncMood(done, TodayArchive()))
        assertEquals(SyncMood.STOPPED, syncMood(done.copy(endReason = SyncEndReason.STOPPED), TodayArchive()))
        assertEquals(SyncMood.STOPPED, syncMood(done.copy(endReason = SyncEndReason.INTERRUPTED), TodayArchive()))
        SyncEndReason.entries.filter { it !in setOf(SyncEndReason.COMPLETED, SyncEndReason.STOPPED, SyncEndReason.INTERRUPTED) }.forEach { reason ->
            assertEquals(SyncMood.FAILED, syncMood(done.copy(endReason = reason), TodayArchive(files = 3)), reason.name)
            // A failure without a finish instant is still a failure.
            assertEquals(SyncMood.FAILED, syncMood(SyncProgress(endReason = reason), TodayArchive()), reason.name)
        }
        assertEquals(SyncMood.RUNNING, syncMood(done.copy(running = true, endReason = SyncEndReason.LINK_LOST), TodayArchive()))
    }

    @Test
    fun everyEarlyEndHasItsOwnLineAndSimpleHidesTokenWording() {
        val ended = SyncEndReason.entries.filter { it != SyncEndReason.COMPLETED }
        ended.forEach { reason ->
            assertNotNull(syncEndLine(SyncProgress(endReason = reason), DetailLevel.SIMPLE), reason.name)
        }
        assertNull(syncEndLine(SyncProgress(endReason = SyncEndReason.COMPLETED), DetailLevel.DETAILED))
        assertNull(syncEndLine(SyncProgress(running = true, endReason = SyncEndReason.LINK_LOST), DetailLevel.DETAILED))
        assertEquals(SyncEndLine.WIFI_ACCESS, syncEndLine(SyncProgress(endReason = SyncEndReason.TOKEN_ROTATED), DetailLevel.SIMPLE))
        assertEquals(SyncEndLine.WIFI_ACCESS, syncEndLine(SyncProgress(endReason = SyncEndReason.TOKEN_REJECTED), DetailLevel.SIMPLE))
        assertEquals(SyncEndLine.TOKEN_ROTATED, syncEndLine(SyncProgress(endReason = SyncEndReason.TOKEN_ROTATED), DetailLevel.DETAILED))
        assertEquals(SyncEndLine.TOKEN_REJECTED, syncEndLine(SyncProgress(endReason = SyncEndReason.TOKEN_REJECTED), DetailLevel.DEVELOPER))
        assertEquals(SyncEndLine.BLUETOOTH_OFF, syncEndLine(SyncProgress(endReason = SyncEndReason.BLUETOOTH_OFF), DetailLevel.SIMPLE))
        assertEquals(SyncEndLine.PAIRING_REPAIR, syncEndLine(SyncProgress(endReason = SyncEndReason.PAIRING_REPAIR), DetailLevel.SIMPLE))
        // Every reason but the two token ones reads the same at each level.
        ended.filter { it != SyncEndReason.TOKEN_ROTATED && it != SyncEndReason.TOKEN_REJECTED }.forEach { reason ->
            assertEquals(syncEndLine(SyncProgress(endReason = reason), DetailLevel.SIMPLE), syncEndLine(SyncProgress(endReason = reason), DetailLevel.DEVELOPER))
        }
    }

    @Test
    fun unknownDictionaryShowsNoLiveReadings() {
        val unknown = waveshare.copy(dict = "0".repeat(64))
        val state = state(unknown, LiveSample.parse("""{"pms":4,"pm25":14.9,"t":22.5,"imu_t":40.0,"rh":50.0}"""), DetailLevel.SIMPLE)
        assertNull(state.capabilities.thing)
        assertNull(state.reading)
        assertEquals(LiveSupport.UNSUPPORTED, state.liveSupport)
        assertEquals(listOf(NOW, TODAY_SO_FAR, SMART_SYNC, CLOCK), state.cards)
    }

    @Test
    fun pendingInfoIsAQuietWaitNeverUnsupported() {
        // #152. Before INFO arrives nothing says the board is unsupported.
        assertEquals(LiveSupport.PENDING, state(null, null, DetailLevel.SIMPLE).liveSupport)
        assertEquals(LiveSupport.PENDING, state(null, particleSample, DetailLevel.SIMPLE).liveSupport)
        assertEquals(LiveSupport.READY, state(waveshare, null, DetailLevel.SIMPLE).liveSupport)
        assertEquals(LiveSupport.UNSUPPORTED, state(DeviceInfo(proto = 3), null, DetailLevel.SIMPLE).liveSupport)
    }

    @Test
    fun anOverflowSampleClearsEveryLiveValueAndKeepsTheCards() {
        val overflow = LiveSample.parse("""{"seq":null,"mono":null,"utc":null,"pms":null,"pm25":null,"t":null,"rh":null}""")
        val before = state(waveshare, particleSample, DetailLevel.DETAILED)
        val after = state(waveshare, overflow, DetailLevel.DETAILED)
        assertEquals(14.9, before.reading?.value("pm25_atmospheric_ug_m3"))
        assertNull(after.reading?.value("pm25_atmospheric_ug_m3"))
        assertNull(after.reading?.value("ambient_temperature_c"))
        assertNull(after.reading?.pmsStatus)
        assertEquals(before.cards, after.cards, "a null reading never hides a card")
    }
}
