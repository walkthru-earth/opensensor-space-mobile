package space.opensensor

import space.opensensor.thing.OpenSensorBoardKind
import space.opensensor.thing.ThingKind
import space.opensensor.thing.ThingKinds

/** Every Thing kind of the iOS app, in recognition order, the same list as Android's ThingModule (#219). */
val IOS_THING_KINDS: List<ThingKind> = listOf(OpenSensorBoardKind)

private val launched: ThingKinds by lazy { ThingKinds(IOS_THING_KINDS).installMetrics() }

/**
 * The app's Thing kinds, built once. The first call installs their derived metric providers into the
 * metric catalog, so AppServices calls it before the engine or any screen reads a metric.
 */
fun launchThingKinds(): ThingKinds = launched
