package earth.walkthru.aqsync.data.archive

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import earth.walkthru.aqsync.archive.ArchiveIndex
import earth.walkthru.aqsync.archive.ArchiveStore
import earth.walkthru.aqsync.archive.PrunedFiles

/** Android's adapters behind the shared archive ports, for code that moves into `:core:*` (platform parity plan). */
@Module
@InstallIn(SingletonComponent::class)
abstract class ArchiveBindingsModule {
    @Binds
    abstract fun bindArchiveStore(store: FileArchiveStore): ArchiveStore

    @Binds
    abstract fun bindArchiveIndex(index: RoomArchiveIndex): ArchiveIndex

    @Binds
    abstract fun bindPrunedFiles(pruned: RoomPrunedFiles): PrunedFiles
}
