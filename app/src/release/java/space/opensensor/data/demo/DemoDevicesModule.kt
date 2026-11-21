package space.opensensor.data.demo

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** A release build binds no demo sensor (#310). The debug source set has the real one. */
@Module
@InstallIn(SingletonComponent::class)
object DemoDevicesModule {
    @Provides
    fun provideDemoDevices(): DemoDevices = DemoDevices.None
}
