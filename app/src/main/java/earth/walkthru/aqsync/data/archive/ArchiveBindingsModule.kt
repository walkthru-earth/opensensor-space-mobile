package earth.walkthru.aqsync.data.archive

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import earth.walkthru.aqsync.archive.ArchiveStore

/** Android's file store behind the shared archive port. The Room adapters come from `DatabaseModule`. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ArchiveBindingsModule {
    @Binds
    abstract fun bindArchiveStore(store: FileArchiveStore): ArchiveStore
}
