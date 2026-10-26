package space.opensensor.data.local.database

import android.content.Context
import androidx.room.Room

/** The app's database file in the Android databases directory, the same `opensensor.db` every build used. */
fun openAppDatabase(context: Context): AppDatabase {
    val app = context.applicationContext
    return AppDatabase.configured(Room.databaseBuilder<AppDatabase>(app, app.getDatabasePath(AppDatabase.FILE_NAME).absolutePath))
}
