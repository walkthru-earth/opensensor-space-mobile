package space.opensensor.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Where one Thing's data comes from, keyed by its UUID (#223). [kind] and [kindVersion] are the
 * `ThingKind` id and contract version, and [configJson] is that kind's settings as a JSON object.
 * A board's Bluetooth row stays in [KnownDevice], which names this Thing in `thing`. No foreign key,
 * so forgetting a board keeps its source and its archive.
 */
@Entity(tableName = "thing_source")
class ThingSourceEntity(
    @PrimaryKey @ColumnInfo(name = "thing_uuid") val thing: String,
    val kind: String,
    @ColumnInfo(name = "kind_version") val kindVersion: Int,
    @ColumnInfo(name = "config_json") val configJson: String,
)

@Dao
interface ThingSourceDao {
    @Query("SELECT * FROM thing_source WHERE thing_uuid = :thing")
    suspend fun get(thing: String): ThingSourceEntity?

    @Query("SELECT * FROM thing_source ORDER BY thing_uuid")
    suspend fun all(): List<ThingSourceEntity>

    /** Returns -1 when the Thing already has a source, which stays as it is. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(source: ThingSourceEntity): Long
}

/**
 * Version 2 to 3 adds `thing_source` and gives every Thing the phone already knows a source row, so
 * no stored Thing is lost. Every Thing before version 3 is an opensensor board, kind
 * `opensensor-board` at contract version 1. Its UUID comes from a [KnownDevice], a [ThingEpoch] or
 * an archived file, the last covers iOS, which keeps its boards outside this database. The literals
 * stay fixed here whatever the kind becomes later, as a migration always runs on version 2 data.
 */
object Migration2To3 : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `thing_source` (`thing_uuid` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`kind_version` INTEGER NOT NULL, `config_json` TEXT NOT NULL, PRIMARY KEY(`thing_uuid`))",
        )
        for (select in listOf(
            "SELECT thing FROM known_device",
            "SELECT thing FROM thing_epoch",
            "SELECT DISTINCT thing FROM archived_file",
        )) {
            connection.execSQL(
                "INSERT OR IGNORE INTO thing_source (thing_uuid, kind, kind_version, config_json) " +
                    "SELECT thing, 'opensensor-board', 1, '{}' FROM ($select) WHERE thing IS NOT NULL AND trim(thing) != ''",
            )
        }
    }
}
