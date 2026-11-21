package space.opensensor.data.demo

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** A debug build binds the shared demo sensor (#310). The release source set binds none. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DemoDevicesModule {
    @Binds
    abstract fun bindDemoDevices(sensors: DebugDemoDevices): DemoDevices
}
