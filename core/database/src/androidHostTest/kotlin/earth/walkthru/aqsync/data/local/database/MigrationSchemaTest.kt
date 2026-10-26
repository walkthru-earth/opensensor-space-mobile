package earth.walkthru.aqsync.data.local.database

import androidx.room.migration.Migration
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import earth.walkthru.aqsync.archive.ArchiveKey
import earth.walkthru.aqsync.data.archive.RoomArchiveIndex
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every migration of [AppDatabase], hand written or generated, checked step by step against the exported
 * schema JSON (#129). Each step builds version N from `N.json`, runs the one migration from N to N + 1 and
 * compares the tables, columns, primary keys and indices with `N+1.json`, so a dropped, renamed or retyped
 * column fails here. Then every old version opens through Room as the app opens it, which runs the whole
 * chain and Room's own schema check, and the archive row written at that version is still there.
 */
class MigrationSchemaTest {
    private val dir = newTempDir()

    @AfterTest
    fun cleanUp() = deleteTempDir(dir)

    private val migrations: List<Migration> =
        AppDatabase.MIGRATIONS.toList() + AppDatabaseConstructor.initialize().createAutoMigrations(emptyMap())

    @Test
    fun everyStepHasExactlyOneMigration() {
        for (from in FIRST until LATEST) {
            assertEquals(1, migrations.count { it.startVersion == from && it.endVersion == from + 1 }, "migrations from $from to ${from + 1}")
        }
        assertTrue(migrations.all { it.endVersion == it.startVersion + 1 }, "every migration is one step")
    }

    @Test
    fun eachMigrationLeavesTheExportedSchemaOfItsTarget() {
        val failures = (FIRST until LATEST).mapNotNull { from ->
            val migration = migrations.single { it.startVersion == from && it.endVersion == from + 1 }
            val actual = BundledSQLiteDriver().open("$dir/step-$from.db").let { connection ->
                try {
                    ExportedSchema(from).create(connection)
                    migration.migrate(connection)
                    ExportedSchema.read(connection)
                } finally {
                    connection.close()
                }
            }
            schemaDiff(ExportedSchema(from + 1).tables(), actual).takeIf { it.isNotEmpty() }?.let { "$from -> ${from + 1}:\n  " + it.joinToString("\n  ") }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun everyOlderVersionOpensThroughRoomWithItsArchiveRow() = runTest {
        for (from in FIRST until LATEST) {
            val path = "$dir/open-$from/${AppDatabase.FILE_NAME}"
            java.io.File(path).parentFile.mkdirs()
            BundledSQLiteDriver().open(path).apply {
                ExportedSchema(from).create(this)
                execSQL(
                    "INSERT INTO archived_file (station, name, bytes, crc32, sha256, syncedAtEpochMs, sourceDevice) " +
                        "VALUES ('station-a', 'year=2026/month=10/day=01/data.parquet', 10, 1, 'ab', 5, 'device')",
                )
                close()
            }
            val db = fileAppDatabase(path)
            try {
                val record = RoomArchiveIndex(db.archivedFileDao()).get(ArchiveKey("station-a", "year=2026/month=10/day=01/data.parquet"))
                assertEquals(10L, record?.bytes, "archive row after opening version $from")
            } finally {
                db.close()
            }
        }
    }

    @Test
    fun migration12To13DropsTheTierCacheAndKeepsTheArchive() {
        // #186. Old tier rows carry the old metric ids and no sample interval, so they must not survive.
        val connection = BundledSQLiteDriver().open("$dir/tiers-12.db")
        try {
            ExportedSchema(12).create(connection)
            connection.execSQL(
                "INSERT INTO archived_file (station, name, bytes, crc32, sha256, syncedAtEpochMs, sourceDevice, pmValidRows) " +
                    "VALUES ('station-a', 'data.parquet', 10, 1, 'ab', 5, 'device', 7)",
            )
            connection.execSQL(
                "INSERT INTO metric_bucket (station, metricId, resolution, startEpochS, rows, validRows, estimatedRows, mean, min, max) " +
                    "VALUES ('station-a', 'pm25', 3600, 0, 360, 360, 0, 10.0, 9.0, 11.0)",
            )
            connection.execSQL(
                "INSERT INTO derived_day (station, epochDay, sourceSignature, sourceFiles, rows, builtAtEpochMs) VALUES ('station-a', 1, 'sig', 1, 360, 5)",
            )
            AppDatabase.MIGRATION_12_13.migrate(connection)
            fun count(table: String) = connection.prepare("SELECT COUNT(*) FROM $table").use { s -> s.step(); s.getLong(0) }
            assertEquals(1L, count("archived_file"))
            assertEquals(0L, count("metric_bucket"))
            assertEquals(0L, count("derived_day"))
        } finally {
            connection.close()
        }
    }

    @Test
    fun theDiffCatchesADroppedARenamedAndARetypedColumn() {
        val expected = ExportedSchema(LATEST).tables()
        val table = expected.getValue("known_device")
        val dropped = expected + ("known_device" to table.copy(columns = table.columns - "lanPort"))
        val renamed = expected + ("known_device" to table.copy(columns = table.columns - "lanPort" + ("lan_port" to table.columns.getValue("lanPort"))))
        val retyped = expected + ("known_device" to table.copy(columns = table.columns + ("lanPort" to table.columns.getValue("lanPort").copy(affinity = "TEXT"))))
        for (actual in listOf(dropped, renamed, retyped)) assertTrue(schemaDiff(expected, actual).isNotEmpty())
        assertEquals(emptyList(), schemaDiff(expected, expected))
    }

    private fun schemaDiff(expected: Map<String, TableShape>, actual: Map<String, TableShape>): List<String> = buildList {
        (expected.keys - actual.keys).forEach { add("missing table $it") }
        (actual.keys - expected.keys).forEach { add("unexpected table $it") }
        for ((name, want) in expected) {
            val got = actual[name] ?: continue
            (want.columns.keys - got.columns.keys).forEach { add("$name missing column $it") }
            (got.columns.keys - want.columns.keys).forEach { add("$name unexpected column $it") }
            for ((column, shape) in want.columns) {
                val have = got.columns[column] ?: continue
                val comparable = if (shape.defaultValue == null) have.copy(defaultValue = null) else have
                if (comparable != shape) add("$name.$column is $have, schema says $shape")
            }
            if (want.primaryKey != got.primaryKey) add("$name primary key is ${got.primaryKey}, schema says ${want.primaryKey}")
            if (want.indices != got.indices) add("$name indices are ${got.indices}, schema says ${want.indices}")
        }
    }

    private companion object {
        const val FIRST = 1
        const val LATEST = 13
    }
}
