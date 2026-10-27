package space.opensensor.data.local.database

import androidx.room.useReaderConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import space.opensensor.data.archive.RoomThingPlaces
import space.opensensor.data.archive.RoomThingSources
import space.opensensor.data.sync.ThingSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A version 2 database file, written as version 2 wrote it, opened by the app at version 5
 * ([Migration2To3], the automatic step that adds `thing_place`, then [Migration4To5]). Every Thing it
 * knew gets a board source, a blank Thing gets none and no stored row is lost. A version 3 file
 * reopens its indexed rows to read their footer digests.
 */
class ThingSourceMigrationTest {
    private val dir = newTempDir()
    private val path = "$dir/${AppDatabase.FILE_NAME}"

    @AfterTest
    fun cleanUp() = deleteTempDir(dir)

    private fun writeVersion2() {
        val connection = BundledSQLiteDriver().open(path)
        try {
            V2_SCHEMA.forEach { connection.execSQL(it) }
            connection.execSQL(
                "INSERT INTO known_device (address, name, thing, deviceId, firmware, lastSeenEpochMs, lastConnectedEpochMs, lanToken, lanPort, autoSync, companionAssociationId) " +
                    "VALUES ('AA:00:00:00:00:01', 'OS-0001', 'thing-a', 'aa0000000001', '1.0', 10, 9, NULL, 47390, 1, NULL)",
            )
            // Seen but never connected, so no Thing yet.
            connection.execSQL(
                "INSERT INTO known_device (address, name, thing, deviceId, firmware, lastSeenEpochMs, lastConnectedEpochMs, lanToken, lanPort, autoSync, companionAssociationId) " +
                    "VALUES ('AA:00:00:00:00:02', 'OS-0002', NULL, NULL, NULL, 20, NULL, NULL, NULL, 0, NULL)",
            )
            // An earlier generation of the first board, and a board this phone forgot.
            connection.execSQL("INSERT INTO thing_epoch (address, thing, deviceId, firstSeenEpochMs, observedFromAuthenticatedBle) VALUES ('AA:00:00:00:00:01', 'thing-old', NULL, 1, 0)")
            connection.execSQL("INSERT INTO thing_epoch (address, thing, deviceId, firstSeenEpochMs, observedFromAuthenticatedBle) VALUES ('AA:00:00:00:00:01', 'thing-a', NULL, 5, 1)")
            // iOS keeps its boards outside this database, so only its archived files name them.
            for (name in listOf("day=01/data.parquet", "day=02/data.parquet")) {
                connection.execSQL(
                    "INSERT INTO archived_file (thing, name, bytes, crc32, sha256, syncedAtEpochMs, sourceDevice) VALUES ('thing-ios', '$name', 10, 1, 'ab', 5, 'aabbccddeeff')",
                )
            }
            // A file named by no Thing, which gets no source.
            connection.execSQL(
                "INSERT INTO archived_file (thing, name, bytes, crc32, sha256, syncedAtEpochMs, sourceDevice) VALUES ('', 'orphan.parquet', 10, 1, 'ab', 5, 'aabbccddeeff')",
            )
            connection.execSQL("INSERT INTO list_cursor (thing, cursor, wholeAtEpochMs, sinceWhole) VALUES ('thing-a', X'2A000000', 7, 3)")
            connection.execSQL("INSERT INTO pruned_file (thing, name, bytes, crc32, prunedAtEpochMs) VALUES ('thing-a', 'day=03/data.parquet', 20, 2, 9)")
            connection.execSQL("PRAGMA user_version = 2")
        } finally {
            connection.close()
        }
    }

    @Test
    fun everyStoredThingGetsABoardSourceAndNoRowIsLost() = runTest {
        writeVersion2()

        val db = fileAppDatabase(path)
        try {
            val sources = RoomThingSources(db.thingSourceDao())
            assertEquals(
                listOf("thing-a", "thing-ios", "thing-old").map { ThingSource(it, "opensensor-board", 1, "{}") },
                sources.all(),
            )
            val board = db.knownDeviceDao().get("AA:00:00:00:00:01")!!
            assertEquals("thing-a", board.thing)
            assertTrue(board.autoSync)
            assertEquals(47390, board.lanPort)
            assertEquals(null, db.knownDeviceDao().get("AA:00:00:00:00:02")!!.thing)
            assertEquals(2, db.thingEpochDao().forDevice("AA:00:00:00:00:01").size)
            assertEquals(listOf("thing-ios"), db.archivedFileDao().identitiesForThing("thing-ios").map { it.thing }.distinct())
            assertEquals(1, db.archivedFileDao().identitiesForThing("").size)
            val cursor = db.listCursorDao().get("thing-a")!!
            assertEquals(listOf<Byte>(42, 0, 0, 0), cursor.cursor.toList())
            assertEquals(7L to 3, cursor.wholeAtEpochMs to cursor.sinceWhole)
            assertEquals(listOf("day=03/data.parquet"), db.prunedFileDao().forThing("thing-a").map { it.name })
            val version = db.useReaderConnection { c -> c.usePrepared("PRAGMA user_version") { it.step(); it.getLong(0) } }
            assertEquals(5L, version)
            // Version 4 adds the own Thing places empty. A pin waits for the next location read.
            assertEquals(emptyList(), RoomThingPlaces(db.thingPlaceDao()).observeAll().first())
        } finally {
            db.close()
        }
    }

    @Test
    fun version5ReopensEveryIndexedFileSoRepairReadsItsFooterDigests() = runTest {
        val connection = BundledSQLiteDriver().open(path)
        try {
            V2_SCHEMA.forEach { connection.execSQL(it) }
            Migration2To3.migrate(connection)
            val columns = "(thing, name, bytes, crc32, sha256, syncedAtEpochMs, sourceDevice, indexedAtEpochMs, indexError, rows)"
            connection.execSQL("INSERT INTO archived_file $columns VALUES ('thing-a', 'indexed.parquet', 10, 1, 'ab', 5, 'dev', 9, NULL, 90)")
            connection.execSQL("INSERT INTO archived_file $columns VALUES ('thing-a', 'refused.parquet', 10, 2, 'ab', 5, 'dev', 9, 'not parquet', NULL)")
            connection.execSQL("INSERT INTO archived_file $columns VALUES ('thing-a', 'waiting.parquet', 10, 3, 'ab', 5, 'dev', NULL, NULL, NULL)")
            connection.execSQL("PRAGMA user_version = 3")
        } finally {
            connection.close()
        }

        val db = fileAppDatabase(path)
        try {
            val dao = db.archivedFileDao()
            val indexed = dao.get("thing-a", "indexed.parquet")!!
            assertEquals(null, indexed.indexedAtEpochMs)
            assertEquals(90L, indexed.rows)
            assertEquals(null to null, indexed.dictionarySha256 to indexed.templateSha256)
            assertEquals(9L to "not parquet", dao.get("thing-a", "refused.parquet")!!.let { it.indexedAtEpochMs to it.indexError })
            assertEquals(null, dao.get("thing-a", "waiting.parquet")!!.indexedAtEpochMs)
        } finally {
            db.close()
        }
    }

    @Test
    fun aRecordedSourceStaysAsItWas() = runTest {
        val db = inMemoryAppDatabase()
        try {
            val sources = RoomThingSources(db.thingSourceDao())
            val first = ThingSource("thing-a", "opensensor-board", 1, "{}")
            assertTrue(sources.recordIfAbsent(first))
            assertFalse(sources.recordIfAbsent(first.copy(kindVersion = 2)))
            assertEquals(first, sources.get("thing-a"))
        } finally {
            db.close()
        }
    }

    private companion object {
        /** The version 2 tables, from `schemas/.../2.json`, as Room created them. */
        val V2_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `known_device` (`address` TEXT NOT NULL, `name` TEXT, `thing` TEXT, `deviceId` TEXT, `firmware` TEXT, `lastSeenEpochMs` INTEGER NOT NULL, `lastConnectedEpochMs` INTEGER, `lanToken` TEXT, `lanPort` INTEGER, `autoSync` INTEGER NOT NULL, `companionAssociationId` INTEGER, PRIMARY KEY(`address`))",
            "CREATE TABLE IF NOT EXISTS `archived_file` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `thing` TEXT NOT NULL, `name` TEXT NOT NULL, `bytes` INTEGER NOT NULL, `crc32` INTEGER NOT NULL, `sha256` TEXT NOT NULL, `syncedAtEpochMs` INTEGER NOT NULL, `sourceDevice` TEXT NOT NULL, `indexedAtEpochMs` INTEGER, `indexError` TEXT, `rows` INTEGER, `rowGroups` INTEGER, `bootId` TEXT, `purpose` TEXT, `utcRows` INTEGER, `firstUtcNs` INTEGER, `lastUtcNs` INTEGER, `firstMonoUs` INTEGER, `lastMonoUs` INTEGER, `firstSeq` INTEGER, `lastSeq` INTEGER, `estFirstUtcNs` INTEGER, `estLastUtcNs` INTEGER, `estAnchorFile` TEXT)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_archived_file_thing_name` ON `archived_file` (`thing`, `name`)",
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
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'c9c6bb28fb88768b1e0baa2170c95b5d')",
        )
    }
}
