package earth.walkthru.aqsync.data.local.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import earth.walkthru.aqsync.data.local.database.AppDatabase
import earth.walkthru.aqsync.data.local.database.MetricBucketDao
import earth.walkthru.aqsync.data.local.database.ArchivedFileDao
import earth.walkthru.aqsync.data.local.database.DerivedDayDao
import earth.walkthru.aqsync.data.local.database.KnownDeviceDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class DatabaseModule {
    @Provides
    fun provideKnownDeviceDao(db: AppDatabase): KnownDeviceDao = db.knownDeviceDao()

    @Provides
    fun provideArchivedFileDao(db: AppDatabase): ArchivedFileDao = db.archivedFileDao()

    @Provides
    fun provideMetricBucketDao(db: AppDatabase): MetricBucketDao = db.metricBucketDao()

    @Provides
    fun provideDerivedDayDao(db: AppDatabase): DerivedDayDao = db.derivedDayDao()

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext appContext: Context): AppDatabase =
        Room.databaseBuilder(appContext, AppDatabase::class.java, "aq-sync.db")
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
}
