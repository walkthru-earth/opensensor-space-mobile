package earth.walkthru.opensensor.today

import earth.walkthru.aqsync.collection.SyncEndReason
import earth.walkthru.aqsync.data.SyncProgress
import earth.walkthru.aqsync.data.ble.ConfigSnapshot
import earth.walkthru.aqsync.data.protocol.DeviceConfig
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.LiveProfiles
import earth.walkthru.aqsync.data.protocol.LiveSample
import earth.walkthru.aqsync.data.protocol.SensorConfig
import earth.walkthru.aqsync.data.sync.FileOutcome
import earth.walkthru.aqsync.settings.DetailLevel
import earth.walkthru.opensensor.today.TodayCard.BATCH_ESTIMATE
import earth.walkthru.opensensor.today.TodayCard.BOARD
import earth.walkthru.opensensor.today.TodayCard.CLOCK
import earth.walkthru.opensensor.today.TodayCard.DEVICE_INFO
import earth.walkthru.opensensor.today.TodayCard.ENVIRONMENT
import earth.walkthru.opensensor.today.TodayCard.NOW_AIR
import earth.walkthru.opensensor.today.TodayCard.NOW_ENVIRONMENT
import earth.walkthru.opensensor.today.TodayCard.PARTICLES
import earth.walkthru.opensensor.today.TodayCard.READINGS
import earth.walkthru.opensensor.today.TodayCard.SMART_SYNC
import earth.walkthru.opensensor.today.TodayCard.STORAGE
import earth.walkthru.opensensor.today.TodayCard.TODAY_SO_FAR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TodayCardsTest {
    private val waveshare = DeviceInfo(proto = 3, schema = "waveshare-sim7670g-telemetry-v4", dict = LiveProfiles.WAVESHARE_DICTIONARY)
    private val cores3 = DeviceInfo(proto = 1, schema = "cores3-telemetry-v5", dict = LiveProfiles.CORES3_DICTIONARY)
    private val particleSample = LiveSample(pms = 4, pm25 = 14.9)

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
            listOf(NOW_AIR, TODAY_SO_FAR, ENVIRONMENT, SMART_SYNC, CLOCK),
            state(waveshare, particleSample, DetailLevel.SIMPLE).cards,
        )
    }

    @Test
    fun detailedAddsMeasurementsBoardStorageAndDevice() {
        assertEquals(
            listOf(NOW_AIR, TODAY_SO_FAR, ENVIRONMENT, SMART_SYNC, CLOCK, READINGS, PARTICLES, BOARD, STORAGE, DEVICE_INFO),
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
        // CoreS3 reports board temperature, so neither the mood nor the ambient card applies.
        assertEquals(listOf(NOW_ENVIRONMENT, TODAY_SO_FAR, SMART_SYNC, CLOCK), state(cores3, LiveSample(imuT = 31.0), DetailLevel.SIMPLE).cards)
        assertEquals(
            listOf(NOW_ENVIRONMENT, TODAY_SO_FAR, SMART_SYNC, CLOCK, BOARD, STORAGE, DEVICE_INFO),
            state(cores3, LiveSample(imuT = 31.0), DetailLevel.DETAILED).cards,
        )
    }

    @Test
    fun anAbsentParticleSensorNeverShowsAMood() {
        val cards = state(waveshare, LiveSample(pms = 0), DetailLevel.DETAILED).cards
        assertEquals(NOW_ENVIRONMENT, cards.first())
        assertFalse(NOW_AIR in cards)
        assertFalse(PARTICLES in cards)
        assertFalse(READINGS in cards)
    }

    @Test
    fun noLiveSampleYetKeepsTheTopOfTheList() {
        val cards = state(waveshare, null, DetailLevel.SIMPLE).cards
        assertEquals(listOf(NOW_ENVIRONMENT, TODAY_SO_FAR, SMART_SYNC, CLOCK), cards)
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
            for (live in listOf(particleSample, LiveSample(pms = 0), null)) {
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
    fun unknownDictionaryTiesTodayTemperatureToTheLiveKey() {
        val unknown = waveshare.copy(dict = "0".repeat(64))
        val ambient = state(unknown, LiveSample(pms = 4, pm25 = 14.9, t = 22.5, imuT = 40.0, rh = 50.0), DetailLevel.SIMPLE)
        assertEquals(listOf(NOW_AIR, TODAY_SO_FAR, ENVIRONMENT, SMART_SYNC, CLOCK), ambient.cards)
        assertEquals(22.5, ambient.capabilities.liveProfile!!.temperatureOf(ambient.snapshot.live)!!, 0.0)
        val board = state(unknown, LiveSample(pms = 4, pm25 = 14.9, imuT = 40.0), DetailLevel.SIMPLE)
        assertEquals(listOf(NOW_AIR, TODAY_SO_FAR, SMART_SYNC, CLOCK), board.cards)
        assertEquals(40.0, board.capabilities.liveProfile!!.temperatureOf(board.snapshot.live)!!, 0.0)
        assertFalse(board.capabilities.liveProfile!!.reviewed)
    }
}
