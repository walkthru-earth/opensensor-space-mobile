package space.opensensor

import space.opensensor.family.air.AirDiscover
import space.opensensor.family.air.AirFamily
import space.opensensor.model.DerivedMetricProvider
import space.opensensor.publicfeed.DiscoverCollection
import space.opensensor.thing.OpenSensorBoardKind
import space.opensensor.thing.ThingKind
import space.opensensor.thing.ThingKinds

/** Every Thing kind of the iOS app, in recognition order, the same list as Android's ThingModule (#219). */
internal val IOS_THING_KINDS: List<ThingKind> = listOf(OpenSensorBoardKind)

/** Every sensor family of the iOS app, the same list as Android's ThingModule (#188). */
internal val IOS_FAMILIES: List<DerivedMetricProvider> = listOf(AirFamily)

/** The app's Thing kinds, built once. Kotlin reads them here, Swift only starts them. */
internal val iosThingKinds: ThingKinds by lazy {
    DiscoverCollection.installBundled(AirDiscover.COLLECTION)
    ThingKinds(IOS_THING_KINDS).installMetrics(IOS_FAMILIES)
}

/**
 * Builds the app's Thing kinds once and installs the families and the kinds' derived metric
 * providers into the metric catalog, and the families' Discover scales, so AppServices calls it
 * before the engine or any screen reads a metric. Nothing of the registry crosses to Swift.
 */
fun launchThingKinds() {
    iosThingKinds
}
