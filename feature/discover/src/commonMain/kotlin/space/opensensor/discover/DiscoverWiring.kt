package space.opensensor.discover

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import space.opensensor.data.history.CellTrackReader
import space.opensensor.data.history.HistoryTiers
import space.opensensor.data.history.MetricCatalog
import space.opensensor.data.history.MetricSpec
import space.opensensor.data.sync.ThingPlace
import space.opensensor.data.sync.ThingPlaces
import space.opensensor.settings.DiscoverSource

/**
 * What Discover reads from the phone, given by each app (#303). Every source here is local. The
 * public feed stays inside [DiscoverController] and reads only while [publicSensors] is on.
 */
class DiscoverPorts(
    val source: Flow<DiscoverSource>,
    val publicSensors: Flow<Boolean>,
    /** Each own Thing's saved cell (#226). Only the cell, never coordinates. */
    val places: ThingPlaces,
    /** Each known sensor's name by Thing UUID. A Thing missing here is not shown. */
    val knownNames: Flow<Map<String, String?>>,
    /** The Things with a ready Bluetooth or LAN link, shown Live. */
    val linked: Flow<Set<String>>,
    val tiers: HistoryTiers,
    /** The Things with a file that went up (#273). */
    val uploaded: Flow<Set<String>>,
    /** Reads a moving Thing's rows on this phone (#263). */
    val tracks: CellTrackReader,
    /** Wall clock in nanoseconds, the end of a moving Thing's track. */
    val nowNs: () -> Long,
    /** Metrics the phone knows, used to find each own Thing's newest reading. */
    val metrics: Flow<List<MetricSpec>> = MetricCatalog.shared.union,
)

/**
 * Feeds [controller] from [ports] for as long as [scope] lives, the one wiring both apps use. The
 * controller outlives the screen, so the network switch reaches a read in flight even while
 * Settings is open, and turning public sensors off cancels it before another request leaves.
 */
fun followPhone(controller: DiscoverController, scope: CoroutineScope, ports: DiscoverPorts) {
    combine(ports.source, ports.publicSensors, ::Pair)
        .onEach { (source, publicSensors) -> controller.onSourceSettings(source, publicSensors) }
        .launchIn(scope)
    val saved = ports.places.observeAll()
    // The user's own Things, each at its saved cell (#226).
    observeKnownOwnThings(saved, ports.knownNames)
        .onEach(controller::showOwnThings)
        .launchIn(scope)
    // A moving Thing's last day of rows, its own cell per row (#263).
    val moving = saved.map { places -> places.filter { it.moving }.mapTo(mutableSetOf()) { it.thing } }
    observeOwnTracks(moving) { thing -> ports.tracks.observe(thing, OWN_TRACK_SPAN_NS, nowNs = ports.nowNs) }
        .onEach(controller::showOwnTracks)
        .launchIn(scope)
    // Each own Thing's newest reading, Live while a link is up, and whether a file of it went up (#273).
    val things = saved.map { places -> places.mapTo(mutableSetOf()) { it.thing } }
    observeOwnStatus(ports.tiers, things, ports.linked, ports.uploaded, ports.metrics)
        .onEach(controller::showOwnStatus)
        .launchIn(scope)
}

/** The saved places of the sensors this phone knows, named as the sensor list names them. */
fun observeKnownOwnThings(places: Flow<List<ThingPlace>>, names: Flow<Map<String, String?>>): Flow<List<OwnThing>> =
    combine(places, names) { saved, known ->
        knownOwnThings(saved.map { OwnThing(it.thing, "", it.cell, it.updatedEpochMs, it.moving) }, known)
    }
