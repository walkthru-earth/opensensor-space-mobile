package space.opensensor.data.local.database

import androidx.room.useReaderConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import space.opensensor.data.archive.RoomThingPlaces
import space.opensensor.data.sync.ThingPlace
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A version 6 database file with a stored own Thing place, opened by the app at version 7 (the
 * automatic step that adds `thing_place.moving`, #263). The place keeps its cell and time and reads
 * as a fixed Thing until the next location read says otherwise.
 */
class ThingPlaceMigrationTest {
    private val dir = newTempDir()
    private val path = "$dir/${AppDatabase.FILE_NAME}"

    @AfterTest
    fun cleanUp() = deleteTempDir(dir)

    @Test
    fun aStoredPlaceKeepsItsCellAndReadsAsFixed() = runTest {
        val connection = BundledSQLiteDriver().open(path)
        try {
            V6_SCHEMA.forEach { connection.execSQL(it) }
            connection.execSQL("INSERT INTO thing_place (thing_uuid, cell, updated_epoch_ms) VALUES ('thing-a', '8828308281fffff', 42)")
            connection.execSQL("PRAGMA user_version = 6")
        } finally {
            connection.close()
        }

        val db = fileAppDatabase(path)
        try {
            assertEquals(listOf(ThingPlace("thing-a", "8828308281fffff", 42, moving = false)), RoomThingPlaces(db.thingPlaceDao()).observeAll().first())
            val version = db.useReaderConnection { c -> c.usePrepared("PRAGMA user_version") { it.step(); it.getLong(0) } }
            assertEquals(7L, version)
        } finally {
            db.close()
        }
    }

    private companion object {
        /** The version 6 tables, from `schemas/.../6.json`, as Room created them. */
        val V6_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `known_device` (`address` TEXT NOT NULL, `name` TEXT, `thing` TEXT, `deviceId` TEXT, `firmware` TEXT, `lastSeenEpochMs` INTEGER NOT NULL, `lastConnectedEpochMs` INTEGER, `lanToken` TEXT, `lanPort` INTEGER, `autoSync` INTEGER NOT NULL, `companionAssociationId` INTEGER, PRIMARY KEY(`address`))",
            "CREATE TABLE IF NOT EXISTS `archived_file` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `thing` TEXT NOT NULL, `name` TEXT NOT NULL, `bytes` INTEGER NOT NULL, `crc32` INTEGER NOT NULL, `sha256` TEXT NOT NULL, `syncedAtEpochMs` INTEGER NOT NULL, `sourceDevice` TEXT NOT NULL, `indexedAtEpochMs` INTEGER, `indexError` TEXT, `rows` INTEGER, `rowGroups` INTEGER, `bootId` TEXT, `purpose` TEXT, `utcRows` INTEGER, `firstUtcNs` INTEGER, `lastUtcNs` INTEGER, `firstMonoUs` INTEGER, `lastMonoUs` INTEGER, `firstSeq` INTEGER, `lastSeq` INTEGER, `estFirstUtcNs` INTEGER, `estLastUtcNs` INTEGER, `estAnchorFile` TEXT, `dictionarySha256` TEXT, `templateSha256` TEXT, `uploadedAtEpochMs` INTEGER)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_archived_file_thing_name` ON `archived_file` (`thing`, `name`)",
            "CREATE INDEX IF NOT EXISTS `index_archived_file_thing_sha256` ON `archived_file` (`thing`, `sha256`)",
            "CREATE INDEX IF NOT EXISTS `index_archived_file_templateSha256` ON `archived_file` (`templateSha256`)",
            "CREATE INDEX IF NOT EXISTS `index_archived_file_dictionarySha256` ON `archived_file` (`dictionarySha256`)",
            "CREATE TABLE IF NOT EXISTS `metric_bucket` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `thing` TEXT NOT NULL, `metricId` TEXT NOT NULL, `resolution` INTEGER NOT NULL, `startEpochS` INTEGER NOT NULL, `rows` INTEGER NOT NULL, `validRows` INTEGER NOT NULL, `estimatedRows` INTEGER NOT NULL, `mean` REAL NOT NULL, `min` REAL NOT NULL, `max` REAL NOT NULL, `sampleIntervalMs` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_metric_bucket_thing_metricId_resolution_startEpochS` ON `metric_bucket` (`thing`, `metricId`, `resolution`, `startEpochS`)",
            "CREATE TABLE IF NOT EXISTS `derived_day` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `thing` TEXT NOT NULL, `epochDay` INTEGER NOT NULL, `sourceSignature` TEXT NOT NULL, `sourceFiles` INTEGER NOT NULL, `rows` INTEGER NOT NULL, `builtAtEpochMs` INTEGER NOT NULL, `dailyFile` TEXT)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_derived_day_thing_epochDay` ON `derived_day` (`thing`, `epochDay`)",
            "CREATE TABLE IF NOT EXISTS `thing_epoch` (`address` TEXT NOT NULL, `thing` TEXT NOT NULL, `deviceId` TEXT, `firstSeenEpochMs` INTEGER NOT NULL, `observedFromAuthenticatedBle` INTEGER NOT NULL, PRIMARY KEY(`address`, `thing`))",
            "CREATE TABLE IF NOT EXISTS `sensor_installation` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `address` TEXT NOT NULL, `thing` TEXT NOT NULL, `deviceId` TEXT, `vendor` TEXT NOT NULL, `model` TEXT NOT NULL, `serial` TEXT, `batch` TEXT, `startedAtEpochMs` INTEGER NOT NULL, `endedAtEpochMs` INTEGER, `source` TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS `index_sensor_installation_address` ON `sensor_installation` (`address`)",
            "CREATE INDEX IF NOT EXISTS `index_sensor_installation_thing` ON `sensor_installation` (`thing`)",
            "CREATE TABLE IF NOT EXISTS `pruned_file` (`thing` TEXT NOT NULL, `name` TEXT NOT NULL, `bytes` INTEGER NOT NULL, `crc32` INTEGER NOT NULL, `prunedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`thing`, `name`))",
            "CREATE TABLE IF NOT EXISTS `list_cursor` (`thing` TEXT NOT NULL, `cursor` BLOB NOT NULL, `wholeAtEpochMs` INTEGER NOT NULL, `sinceWhole` INTEGER NOT NULL, PRIMARY KEY(`thing`))",
            "CREATE TABLE IF NOT EXISTS `thing_source` (`thing_uuid` TEXT NOT NULL, `kind` TEXT NOT NULL, `kind_version` INTEGER NOT NULL, `config_json` TEXT NOT NULL, PRIMARY KEY(`thing_uuid`))",
            "CREATE TABLE IF NOT EXISTS `thing_place` (`thing_uuid` TEXT NOT NULL, `cell` TEXT NOT NULL, `updated_epoch_ms` INTEGER NOT NULL, PRIMARY KEY(`thing_uuid`))",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'b99f574664be3dea31530dac706fc74f')",
        )
    }
}
