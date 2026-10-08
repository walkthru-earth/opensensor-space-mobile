package earth.walkthru.aqsync.data.settings

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import earth.walkthru.aqsync.settings.SettingsKeys
import earth.walkthru.aqsync.settings.SettingsRepository
import earth.walkthru.aqsync.settings.createSettingsDataStore
import java.io.File
import javax.inject.Singleton

/**
 * The shared [SettingsRepository] at the file the old `preferencesDataStore("settings")` used,
 * `files/datastore/settings.preferences_pb`, so an upgrade keeps every choice. One instance per
 * process, as DataStore requires.
 */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {
    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepository =
        SettingsRepository(createSettingsDataStore(File(context.filesDir, SettingsKeys.RELATIVE_PATH).absolutePath))
}
