package space.opensensor.data.di

import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import space.opensensor.data.coroutines.ApplicationScope
import space.opensensor.data.coroutines.DefaultDispatcher
import space.opensensor.data.coroutines.IoDispatcher
import space.opensensor.data.coroutines.MonotonicTime
import space.opensensor.data.coroutines.WallClock
import javax.inject.Singleton
import kotlin.time.TimeSource

/** Dispatchers, the process scope and clocks, injected so tests can run on virtual time. */
@Module
@InstallIn(SingletonComponent::class)
object CoroutinesModule {
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(@DefaultDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(
            SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e ->
                // A failed child must not take the process down or cancel its siblings.
                Log.e("ApplicationScope", "uncaught failure in application scope: ${e::class.simpleName}", e)
            },
        )

    @Provides
    fun provideWallClock(): WallClock = WallClock.System

    @Provides
    fun provideMonotonicTime(): MonotonicTime = TimeSource.Monotonic
}
