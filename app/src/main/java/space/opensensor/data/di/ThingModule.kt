package space.opensensor.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import space.opensensor.family.air.AirFamily
import space.opensensor.thing.OpenSensorBoardKind
import space.opensensor.thing.ThingKind
import space.opensensor.thing.ThingKinds
import javax.inject.Singleton

/**
 * The Thing kinds this app knows, a plain list with no self registration (#219). Building it calls
 * [AirFamily.install] with the kinds' derived metric providers (#188), which fills the metric catalog
 * and the Discover scales, so OpenSensorApplication injects it before anything reads a metric. iOS
 * lists the same kinds and makes the same call in ThingKindsIos.kt.
 */
@Module
@InstallIn(SingletonComponent::class)
object ThingModule {
    /** Every kind of this app, in recognition order. */
    val KINDS: List<ThingKind> = listOf(OpenSensorBoardKind)

    @Provides
    @Singleton
    fun provideThingKinds(): ThingKinds = ThingKinds(KINDS).also { AirFamily.install(it.metricProviders) }
}
