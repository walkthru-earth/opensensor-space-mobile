package space.opensensor.data.settings

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import space.opensensor.settings.SettingsKeys
import space.opensensor.settings.SettingsRepository
import space.opensensor.settings.createSettingsDataStore
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

    @Provides
    fun provideDeveloperLevel(settings: SettingsRepository): DeveloperLevel =
        DeveloperLevel { settings.detailLevel.first().developer }
}

/**
 * Whether the phone is at the Developer level right now. Automatic device upkeep reads it once per
 * connection: only Developer may keep Wi-Fi sync off on a sensor.
 */
fun interface DeveloperLevel {
    suspend fun developer(): Boolean
}
