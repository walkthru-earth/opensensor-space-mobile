package space.opensensor.data.local.database

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * The one database of both phones, `opensensor.db`, opened through [configured] with the bundled SQLite
 * driver. Android uses every table. iOS uses `archived_file`, `pruned_file`, `list_cursor`,
 * `thing_source` and `thing_place`, the other tables stay empty there, so one schema and one version serve both (#100).
 *
 * The schema is exported to `core/database/schemas/`. Version 2 adds `list_cursor` (#95) through an
 * automatic migration, since a new table needs no hand written step. Version 3 adds `thing_source`
 * (#223) through [Migration2To3], which also gives every Thing already stored a source row. Version 4
 * adds `thing_place` (#226), the cell of each own fixed Thing, through an automatic migration.
 * `metric_bucket` and `derived_day` are caches of the archive that `HistoryDeriver` rebuilds.
 */
@ConstructedBy(AppDatabaseConstructor::class)
@Database(
    entities = [KnownDevice::class, ArchivedFile::class, MetricBucket::class, DerivedDay::class, ThingEpoch::class, SensorInstallation::class, PrunedFileEntity::class, ListCursorEntity::class, ThingSourceEntity::class, ThingPlaceEntity::class],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 3, to = 4)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun knownDeviceDao(): KnownDeviceDao
    abstract fun archivedFileDao(): ArchivedFileDao
    abstract fun metricBucketDao(): MetricBucketDao
    abstract fun derivedDayDao(): DerivedDayDao
    abstract fun thingEpochDao(): ThingEpochDao
    abstract fun sensorInstallationDao(): SensorInstallationDao
    abstract fun prunedFileDao(): PrunedFileDao
    abstract fun listCursorDao(): ListCursorDao
    abstract fun thingSourceDao(): ThingSourceDao
    abstract fun thingPlaceDao(): ThingPlaceDao

    companion object {
        /** The file name on both phones. */
        const val FILE_NAME: String = "opensensor.db"

        /**
         * The settings both phones open the database with: the bundled SQLite driver
         * (the same SQLite build and file format on Android and iOS) and queries on the IO dispatcher.
         */
        fun configured(builder: RoomDatabase.Builder<AppDatabase>): AppDatabase = builder
            .setDriver(BundledSQLiteDriver())
            .addMigrations(Migration2To3)
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }
}

/** Room generates the actual for each target, so iOS opens the database without reflection. */
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}
