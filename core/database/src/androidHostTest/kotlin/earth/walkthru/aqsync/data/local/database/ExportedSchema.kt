package earth.walkthru.aqsync.data.local.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * One exported Room schema, `core/database/schemas/.../<version>.json`, read on the host. It can write
 * that version's tables into an empty connection and describe the tables as SQLite would report them,
 * so a test compares a migrated file with the schema Room exported for the target version.
 */
class ExportedSchema(val version: Int) {
    private val database: JsonObject =
        Json.parseToJsonElement(File("$DIR/$version.json").readText()).jsonObject.getValue("database").jsonObject.also {
            check(it.getValue("version").jsonPrimitive.content.toInt() == version) { "$version.json declares another version" }
        }

    private val entities: List<JsonObject> = database.getValue("entities").jsonArray.map { it.jsonObject }

    /** Creates every table and index of this version, then Room's setup queries and `user_version`. */
    fun create(connection: SQLiteConnection) {
        for (entity in entities) {
            val table = entity.string("tableName")
            connection.execSQL(entity.string("createSql").replace("\${TABLE_NAME}", table))
            entity["indices"]?.jsonArray?.forEach { connection.execSQL(it.jsonObject.string("createSql").replace("\${TABLE_NAME}", table)) }
        }
        for (query in database.getValue("setupQueries").jsonArray) connection.execSQL(query.jsonPrimitive.content)
        connection.execSQL("PRAGMA user_version = $version")
    }

    /** Every table of this version as the schema declares it. */
    fun tables(): Map<String, TableShape> = entities.associate { entity ->
        val name = entity.string("tableName")
        val columns = entity.getValue("fields").jsonArray.map { it.jsonObject }.associate { field ->
            field.string("columnName") to ColumnShape(
                affinity = field.string("affinity"),
                notNull = field["notNull"]?.jsonPrimitive?.booleanOrNull ?: false,
                defaultValue = field["defaultValue"]?.jsonPrimitive?.contentOrNull,
            )
        }
        val primaryKey = entity.getValue("primaryKey").jsonObject.getValue("columnNames").jsonArray.map { it.jsonPrimitive.content }
        val indices = entity["indices"]?.jsonArray.orEmpty().map { it.jsonObject }.associate { index ->
            index.string("name") to IndexShape(
                unique = index["unique"]?.jsonPrimitive?.booleanOrNull ?: false,
                columns = index.getValue("columnNames").jsonArray.map { it.jsonPrimitive.content },
            )
        }
        name to TableShape(columns, primaryKey, indices)
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    companion object {
        /** Relative to the module, where Gradle runs host tests. */
        const val DIR: String = "schemas/earth.walkthru.aqsync.data.local.database.AppDatabase"

        /** The tables in [connection] as SQLite reports them, without SQLite's and Room's own tables. */
        fun read(connection: SQLiteConnection): Map<String, TableShape> {
            val names = query(connection, "SELECT name FROM sqlite_master WHERE type = 'table'") { it.getText(0) }
                .filterNot { it.startsWith("sqlite_") || it == "room_master_table" || it == "android_metadata" }
            return names.associateWith { table ->
                val columns = LinkedHashMap<String, ColumnShape>()
                val pk = sortedMapOf<Int, String>()
                query(connection, "PRAGMA table_info(`$table`)") { s ->
                    val name = s.getText(1)
                    columns[name] = ColumnShape(s.getText(2).uppercase(), s.getLong(3) != 0L, if (s.isNull(4)) null else s.getText(4))
                    s.getLong(5).toInt().takeIf { it > 0 }?.let { pk[it] = name }
                }
                val indices = query(connection, "PRAGMA index_list(`$table`)") { s -> Triple(s.getText(1), s.getLong(2) != 0L, s.getText(3)) }
                    .filter { (_, _, origin) -> origin == "c" }
                    .associate { (name, unique, _) ->
                        name to IndexShape(unique, query(connection, "PRAGMA index_info(`$name`)") { it.getLong(0) to it.getText(2) }.sortedBy { it.first }.map { it.second })
                    }
                TableShape(columns, pk.values.toList(), indices)
            }
        }

        private fun <T> query(connection: SQLiteConnection, sql: String, row: (androidx.sqlite.SQLiteStatement) -> T): List<T> =
            connection.prepare(sql).use { s -> buildList { while (s.step()) add(row(s)) } }
    }
}

/** A column as both sides can describe it. The default is compared only when the schema declares one, as Room does. */
data class ColumnShape(val affinity: String, val notNull: Boolean, val defaultValue: String?)

data class IndexShape(val unique: Boolean, val columns: List<String>)

data class TableShape(val columns: Map<String, ColumnShape>, val primaryKey: List<String>, val indices: Map<String, IndexShape>)
