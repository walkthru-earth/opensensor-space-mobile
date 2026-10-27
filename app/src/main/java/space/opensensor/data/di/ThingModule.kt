package space.opensensor.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import space.opensensor.family.air.AirDiscover
import space.opensensor.family.air.AirFamily
import space.opensensor.model.DerivedMetricProvider
import space.opensensor.publicfeed.DiscoverCollection
import space.opensensor.thing.OpenSensorBoardKind
import space.opensensor.thing.ThingKind
import space.opensensor.thing.ThingKinds
import javax.inject.Singleton

/**
 * The Thing kinds and sensor families this app knows, plain lists with no self registration (#219,
 * #188). Building it installs the families and the kinds' derived metric providers into the metric
 * catalog, and the families' Discover scales, so OpenSensorApplication injects it before anything
 * reads a metric. iOS lists the same kinds and families in ThingKindsIos.kt.
 */
@Module
@InstallIn(SingletonComponent::class)
object ThingModule {
    /** Every kind of this app, in recognition order. */
    val KINDS: List<ThingKind> = listOf(OpenSensorBoardKind)

    /** Every sensor family of this app. */
    val FAMILIES: List<DerivedMetricProvider> = listOf(AirFamily)

    @Provides
    @Singleton
    fun provideThingKinds(): ThingKinds {
        DiscoverCollection.installBundled(AirDiscover.COLLECTION)
        return ThingKinds(KINDS).installMetrics(FAMILIES)
    }
}
