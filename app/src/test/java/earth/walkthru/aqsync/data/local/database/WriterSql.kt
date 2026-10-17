package earth.walkthru.aqsync.data.local.database

import androidx.room.execSQL
import androidx.room.useWriterConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy

/**
 * A migration's `SupportSQLiteDatabase`, routed to the bundled driver's writer connection. A Room
 * database built on a driver has no open helper, and a migration here only runs `execSQL`.
 */
fun AppDatabase.writerSql(): SupportSQLiteDatabase = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
    check(method.name == "execSQL" && args?.size == 1) { "only execSQL is routed, not ${method.name}" }
    runBlocking { useWriterConnection { it.execSQL(args[0] as String) } }
    null
} as SupportSQLiteDatabase
