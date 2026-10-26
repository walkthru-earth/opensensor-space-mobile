package space.opensensor.data.local.database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * The one database of both phones, `opensensor.db`, opened through [configured] with the bundled SQLite
 * driver. Android uses every table. iOS uses `archived_file` and `pruned_file`, the other tables stay
 * empty there, so one schema and one version serve both (#100).
 *
 * The schema is exported to `core/database/schemas/`. The app ID `space.opensensor.app` always starts
 * with an empty database, so there are no migrations. A later schema change adds its first one.
 * `metric_bucket` and `derived_day` are caches of the archive that `HistoryDeriver` rebuilds.
 */
@ConstructedBy(AppDatabaseConstructor::class)
@Database(
    entities = [KnownDevice::class, ArchivedFile::class, MetricBucket::class, DerivedDay::class, ThingEpoch::class, SensorInstallation::class, PrunedFileEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun knownDeviceDao(): KnownDeviceDao
    abstract fun archivedFileDao(): ArchivedFileDao
    abstract fun metricBucketDao(): MetricBucketDao
    abstract fun derivedDayDao(): DerivedDayDao
    abstract fun thingEpochDao(): ThingEpochDao
    abstract fun sensorInstallationDao(): SensorInstallationDao
    abstract fun prunedFileDao(): PrunedFileDao

    companion object {
        /** The file name on both phones. */
        const val FILE_NAME: String = "opensensor.db"

        /**
         * The settings both phones open the database with: the bundled SQLite driver
         * (the same SQLite build and file format on Android and iOS) and queries on the IO dispatcher.
         */
        fun configured(builder: RoomDatabase.Builder<AppDatabase>): AppDatabase = builder
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }
}

/** Room generates the actual for each target, so iOS opens the database without reflection. */
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
