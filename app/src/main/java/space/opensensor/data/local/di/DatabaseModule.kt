package space.opensensor.data.local.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import space.opensensor.archive.ArchiveIndex
import space.opensensor.archive.PrunedFiles
import space.opensensor.data.archive.RoomArchiveIndex
import space.opensensor.data.archive.RoomListCursors
import space.opensensor.data.archive.RoomPrunedFiles
import space.opensensor.data.local.database.AppDatabase
import space.opensensor.data.local.database.ArchivedFileDao
import space.opensensor.data.local.database.DerivedDayDao
import space.opensensor.data.local.database.KnownDeviceDao
import space.opensensor.data.local.database.ListCursorDao
import space.opensensor.data.local.database.MetricBucketDao
import space.opensensor.data.local.database.PrunedFileDao
import space.opensensor.data.local.database.SensorInstallationDao
import space.opensensor.data.local.database.ThingEpochDao
import space.opensensor.data.local.database.openAppDatabase
import space.opensensor.data.local.security.LanCredentialStore
import space.opensensor.data.local.security.LanTokenCipher
import space.opensensor.data.local.security.LanTokenCipherContract
import space.opensensor.data.local.security.LanTokenMigration
import space.opensensor.data.local.security.SecureKnownDeviceDao
import space.opensensor.data.sync.ListCursors
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
    fun provideThingEpochDao(db: AppDatabase): ThingEpochDao = db.thingEpochDao()

    @Provides
    fun provideSensorInstallationDao(db: AppDatabase): SensorInstallationDao = db.sensorInstallationDao()

    @Provides
    fun providePrunedFileDao(db: AppDatabase): PrunedFileDao = db.prunedFileDao()

    @Provides
    fun provideListCursorDao(db: AppDatabase): ListCursorDao = db.listCursorDao()

    /** The shared index adapter over `archived_file`, the same class iOS uses (#100). */
    @Provides
    @Singleton
    fun provideArchiveIndex(dao: ArchivedFileDao): ArchiveIndex = RoomArchiveIndex(dao)

    @Provides
    @Singleton
    fun providePrunedFiles(dao: PrunedFileDao): PrunedFiles = RoomPrunedFiles(dao)

    @Provides
    @Singleton
    fun provideListCursors(dao: ListCursorDao): ListCursors = RoomListCursors(dao)

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext appContext: Context): AppDatabase = openAppDatabase(appContext)
}
