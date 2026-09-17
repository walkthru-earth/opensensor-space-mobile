package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.history.parquet.ColumnValues
import earth.walkthru.aqsync.data.history.parquet.ParquetFile

/**
 * Time buckets the History screens read instead of raw rows. Sizes are chosen so that a year view
 * is ~365 points (DAY), a month ~720 (HOUR) and a week ~672 (MIN15); anything finer reads the rows.
 */
enum class Resolution(val seconds: Long) {
    MIN15(900), HOUR(3600), DAY(86_400);

    /** Rows the logger produces in one bucket when nothing is missed (one every 10 s). */
    val expectedRows: Int get() = (seconds / 10).toInt()

    fun bucketStart(epochSeconds: Long): Long = Math.floorDiv(epochSeconds, seconds) * seconds
}

/**
 * One aggregated bucket. Particle values come only from rows whose `pms_status == 4` (present, warmed
 * up, fresh, no sensor error) — `rows` counts every row, `validRows` the ones behind the means, and
 * `coverage` is `validRows / expected`, so a bucket built from three readings says so.
 *
 * Board temperature is the IMU die, explicitly not ambient; battery/USB are the board's report,
 * unqualified — same caveats as the Today screen.
 */
data class Bucket(
    val station: String,
    val resolution: Resolution,
    val startEpochS: Long,
    val rows: Int,
    val validRows: Int,
    val pm1Mean: Float?,
    val pm25Mean: Float?,
    val pm25Min: Int?,
    val pm25Max: Int?,
    val pm10Mean: Float?,
    val particlesGt03Mean: Float?,
    val particlesGt25Mean: Float?,
    val boardTempMean: Float?,
    val batteryPercentMean: Float?,
    val vbusMvMean: Float?,
) {
    val coverage: Float get() = (validRows.toFloat() / resolution.expectedRows).coerceIn(0f, 1f)
    val endEpochS: Long get() = startEpochS + resolution.seconds
}

/**
 * Pure aggregation of decoded columns into [Bucket]s. Feed it any number of files (a UTC day's worth,
 * in practice) through [add]; [buckets] returns one entry per non-empty (resolution, start).
 * Rows without UTC are skipped — they belong to the unsynced view, not to a timeline.
 */
class Aggregator(private val station: String, private val resolutions: Set<Resolution> = Resolution.entries.toSet()) {

    /** The columns a tier needs; three of them drive validity and time, the rest are the metrics. */
    companion object {
        val COLUMNS = listOf(
            ParquetIndexer.UTC, ParquetIndexer.PMS_STATUS,
            "pm1_atmospheric_ug_m3", "pm25_atmospheric_ug_m3", "pm10_atmospheric_ug_m3",
            "particles_gt03_per_01l", "particles_gt25_per_01l",
            "imu_temperature_c", "battery_percent", "vbus_mv",
        )
    }

    private class Acc {
        var rows = 0
        var valid = 0
        var pm1 = 0.0
        var pm25 = 0.0
        var pm25Min = Int.MAX_VALUE
        var pm25Max = Int.MIN_VALUE
        var pm10 = 0.0
        var gt03 = 0.0
        var gt25 = 0.0
        var temp = 0.0
        var tempN = 0
        var bat = 0.0
        var batN = 0
        var vbus = 0.0
        var vbusN = 0
    }

    private val accs = HashMap<Pair<Resolution, Long>, Acc>()

    /** Adds every UTC row of [file]. Only the [COLUMNS] chunks are read. */
    fun add(file: ParquetFile) {
        val md = file.metadata
        val utcLeaf = md.leaf(ParquetIndexer.UTC) ?: return
        val utc = file.readColumn(utcLeaf)
        fun col(name: String): ColumnValues? = md.leaf(name)?.let { file.readColumn(it) }
        add(
            utc = utc,
            pms = col(ParquetIndexer.PMS_STATUS),
            pm1 = col("pm1_atmospheric_ug_m3"),
            pm25 = col("pm25_atmospheric_ug_m3"),
            pm10 = col("pm10_atmospheric_ug_m3"),
            gt03 = col("particles_gt03_per_01l"),
            gt25 = col("particles_gt25_per_01l"),
            temp = col("imu_temperature_c"),
            battery = col("battery_percent"),
            vbus = col("vbus_mv"),
        )
    }

    /** Column-level entry point (tests, and later the daily derived file). All columns share one row index. */
    fun add(
        utc: ColumnValues,
        pms: ColumnValues?,
        pm1: ColumnValues?,
        pm25: ColumnValues?,
        pm10: ColumnValues?,
        gt03: ColumnValues?,
        gt25: ColumnValues?,
        temp: ColumnValues?,
        battery: ColumnValues?,
        vbus: ColumnValues?,
    ) {
        for (i in 0 until utc.size) {
            val ns = utc.asLong(i) ?: continue
            val s = Math.floorDiv(ns, 1_000_000_000L)
            val pmValid = pms?.asLong(i) == ParquetIndexer.PMS_VALID.toLong()
            val pm25v = if (pmValid) pm25?.asLong(i)?.toInt() else null
            for (r in resolutions) {
                val acc = accs.getOrPut(r to r.bucketStart(s)) { Acc() }
                acc.rows++
                if (pmValid && pm25v != null) {
                    acc.valid++
                    acc.pm25 += pm25v
                    if (pm25v < acc.pm25Min) acc.pm25Min = pm25v
                    if (pm25v > acc.pm25Max) acc.pm25Max = pm25v
                    pm1?.asDouble(i)?.let { acc.pm1 += it }
                    pm10?.asDouble(i)?.let { acc.pm10 += it }
                    gt03?.asDouble(i)?.let { acc.gt03 += it }
                    gt25?.asDouble(i)?.let { acc.gt25 += it }
                }
                temp?.asDouble(i)?.let { if (!it.isNaN()) { acc.temp += it; acc.tempN++ } }
                battery?.asDouble(i)?.let { acc.bat += it; acc.batN++ }
                vbus?.asDouble(i)?.let { acc.vbus += it; acc.vbusN++ }
            }
        }
    }

    fun buckets(): List<Bucket> = accs.entries
        .sortedWith(compareBy({ it.key.first.ordinal }, { it.key.second }))
        .map { (key, a) ->
            val v = a.valid
            Bucket(
                station = station,
                resolution = key.first,
                startEpochS = key.second,
                rows = a.rows,
                validRows = v,
                pm1Mean = if (v > 0) (a.pm1 / v).toFloat() else null,
                pm25Mean = if (v > 0) (a.pm25 / v).toFloat() else null,
                pm25Min = if (v > 0) a.pm25Min else null,
                pm25Max = if (v > 0) a.pm25Max else null,
                pm10Mean = if (v > 0) (a.pm10 / v).toFloat() else null,
                particlesGt03Mean = if (v > 0) (a.gt03 / v).toFloat() else null,
                particlesGt25Mean = if (v > 0) (a.gt25 / v).toFloat() else null,
                boardTempMean = if (a.tempN > 0) (a.temp / a.tempN).toFloat() else null,
                batteryPercentMean = if (a.batN > 0) (a.bat / a.batN).toFloat() else null,
                vbusMvMean = if (a.vbusN > 0) (a.vbus / a.vbusN).toFloat() else null,
            )
        }
}
