package space.opensensor

import space.opensensor.thing.OpenSensorBoardKind
import space.opensensor.thing.ThingKind
import space.opensensor.thing.ThingKinds

/** Every Thing kind of the iOS app, in recognition order, the same list as Android's ThingModule (#219). */
internal val IOS_THING_KINDS: List<ThingKind> = listOf(OpenSensorBoardKind)

/** The app's Thing kinds, built once. Kotlin reads them here, Swift only starts them. */
internal val iosThingKinds: ThingKinds by lazy { ThingKinds(IOS_THING_KINDS).installMetrics() }

/**
 * Builds the app's Thing kinds once and installs their derived metric providers into the metric
 * catalog, so AppServices calls it before the engine or any screen reads a metric. Nothing of the
 * registry crosses to Swift.
 */
fun launchThingKinds() {
    iosThingKinds
}
