package space.opensensor.family.air

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveQcTest {
    /** A Waveshare LIVE sample [i], ten seconds apart, with steady counts unless given. */
    private fun sample(
        i: Int,
        pm25: Double = 12.0,
        pm10: Double = 14.0,
        rh: Double = 50.0,
        n03: String = "${900 + i}",
        pms: Int = 4,
    ) = LiveFixtures.waveshare(
        """{"seq":$i,"mono":${i * 10_000_000L},"pms":$pms,"pm1":8,"pm25":$pm25,"pm10":$pm10,"n03":$n03,"n05":250,"n1":30,"n25":2,"rh":$rh}""",
    )

    @Test
    fun flagsPm25AbovePm10AndHighHumidityAtOnce() {
        val qc = LiveQc()
        assertEquals(QcFlags.NONE, qc.add(sample(1), null))
        assertEquals(QcFlags.PM25_ABOVE_PM10, qc.add(sample(2, pm25 = 20.0, pm10 = 18.0), null))
        assertEquals(QcFlags.NONE, qc.add(sample(3, pm25 = 15.0, pm10 = 14.0), null), "within the rounding tolerance")
        assertEquals(QcFlags.HIGH_HUMIDITY, qc.add(sample(4, rh = 90.0), null))
        assertEquals(QcFlags.NONE, qc.add(sample(5, rh = 85.0), null))
    }

    @Test
    fun flagsAFlatlineOnceThirtySamplesKeepTheSameCounts() {
        val qc = LiveQc()
        val flags = (1..QcRules.FLATLINE_ROWS + 20).map { qc.add(sample(it, n03 = "900"), null) }
        assertEquals(QcFlags.NONE, flags[QcRules.FLATLINE_ROWS - 2])
        assertEquals(QcFlags.FLATLINE, flags[QcRules.FLATLINE_ROWS - 1])
        assertEquals(QcFlags.FLATLINE, flags.last(), "the window keeps the run as it slides")
        assertEquals(QcFlags.NONE, qc.add(sample(QcRules.FLATLINE_ROWS + 21), null), "moving counts end the run")
    }

    /** Each sample read twice. Counted twice its repeated time would end every flatline run. */
    @Test
    fun aSampleReadAgainIsNotCountedTwice() {
        val qc = LiveQc()
        val flags = (1..QcRules.FLATLINE_ROWS).map { i ->
            val same = sample(i, n03 = "900")
            qc.add(same, null)
            qc.add(same, null)
        }
        assertEquals(QcFlags.NONE, flags[QcRules.FLATLINE_ROWS - 2])
        assertEquals(QcFlags.FLATLINE, flags.last())
    }

    @Test
    fun onlyValidSamplesJoinTheWindow() {
        val qc = LiveQc()
        assertEquals(QcFlags.NONE, qc.add(null, 0))
        // Warming samples never join, and leave the run of valid ones as it was.
        var i = 0
        repeat(QcRules.FLATLINE_ROWS - 1) {
            qc.add(sample(++i, n03 = "900"), null)
            assertEquals(QcFlags.NONE, qc.add(sample(++i, pms = 1, n03 = "900"), null))
        }
        assertEquals(QcFlags.NONE, qc.add(sample(++i, pms = 1, rh = 95.0), null))
    }

    @Test
    fun aMissingCountSkipsTheFlatline() {
        val qc = LiveQc()
        val flags = (1..QcRules.FLATLINE_ROWS + 5).map { qc.add(sample(it, n03 = "null"), null) }
        assertEquals(List(flags.size) { QcFlags.NONE }, flags)
    }

    /** A spike needs the sample after it, so the newest sample is never one. */
    @Test
    fun theNewestSampleIsNeverASpike() {
        val qc = LiveQc()
        (1..5).forEach { qc.add(sample(it), null) }
        assertEquals(QcFlags.NONE, qc.add(sample(6, pm25 = 90.0, pm10 = 95.0), null))
    }

    @Test
    fun aSampleWithoutMonoTakesTheReceivedTime() {
        val qc = LiveQc()
        val flags = (1..QcRules.FLATLINE_ROWS).map { i ->
            qc.add(LiveFixtures.waveshare("""{"pms":4,"pm25":10,"pm10":12,"n03":900,"n05":250,"n1":30,"n25":2}"""), i * 10_000L)
        }
        assertEquals(QcFlags.FLATLINE, flags.last())
    }
}
