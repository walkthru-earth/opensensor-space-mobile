package earth.walkthru.aqsync.data.local.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import earth.walkthru.aqsync.data.local.database.AppDatabase
import earth.walkthru.aqsync.data.local.database.ArchivedFileDao
import earth.walkthru.aqsync.data.local.database.DerivedDayDao
import earth.walkthru.aqsync.data.local.database.KnownDeviceDao
import earth.walkthru.aqsync.data.local.database.MetricBucketDao
import earth.walkthru.aqsync.data.local.database.PrunedFileDao
import earth.walkthru.aqsync.data.local.database.SensorInstallationDao
import earth.walkthru.aqsync.data.local.database.StationEpochDao
import earth.walkthru.aqsync.data.local.security.LanCredentialStore
import earth.walkthru.aqsync.data.local.security.LanTokenCipher
import earth.walkthru.aqsync.data.local.security.LanTokenCipherContract
import earth.walkthru.aqsync.data.local.security.LanTokenMigration
import earth.walkthru.aqsync.data.local.security.SecureKnownDeviceDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class DatabaseModule {
    @Provides
    @Singleton
    fun provideTokenCipher(cipher: LanTokenCipher): LanTokenCipherContract = cipher

    @Provides
    @Singleton
    fun provideSecureKnownDeviceDao(db: AppDatabase, cipher: LanTokenCipherContract): SecureKnownDeviceDao =
        SecureKnownDeviceDao(db.knownDeviceDao(), cipher)

    @Provides
    fun provideKnownDeviceDao(dao: SecureKnownDeviceDao): KnownDeviceDao = dao

    @Provides
    fun provideLanCredentialStore(dao: SecureKnownDeviceDao): LanCredentialStore = dao

    @Provides
    fun provideLanTokenMigration(dao: SecureKnownDeviceDao): LanTokenMigration = dao

    @Provides
    fun provideArchivedFileDao(db: AppDatabase): ArchivedFileDao = db.archivedFileDao()

    @Provides
    fun provideMetricBucketDao(db: AppDatabase): MetricBucketDao = db.metricBucketDao()

    @Provides
    fun provideDerivedDayDao(db: AppDatabase): DerivedDayDao = db.derivedDayDao()

    @Provides
    fun provideStationEpochDao(db: AppDatabase): StationEpochDao = db.stationEpochDao()

    @Provides
    fun provideSensorInstallationDao(db: AppDatabase): SensorInstallationDao = db.sensorInstallationDao()

    @Provides
    fun providePrunedFileDao(db: AppDatabase): PrunedFileDao = db.prunedFileDao()

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext appContext: Context): AppDatabase =
        Room.databaseBuilder(appContext, AppDatabase::class.java, "aq-sync.db")
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
}
