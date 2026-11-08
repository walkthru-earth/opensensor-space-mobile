package space.opensensor.data.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import space.opensensor.data.protocol.DeviceConfig

/**
 * One sensor in the foreground Bluetooth scan. [advertFlags] is the advert flags byte, null when the
 * advert carried no service data. [seenMs] is when the phone last heard it, null when the platform
 * does not say.
 */
data class SetupSighting(val id: String, val advertFlags: Int?, val seenMs: Long?)

/**
 * A sensor that needs setup now (#272). [opened] is true once the wizard opened for it in this
 * foreground session, by itself or from a tap, so it shows a resume entry instead of opening again.
 */
data class SetupNeed(val id: String, val opened: Boolean)

/**
 * Which sensors ask for setup, the same on both phones (#272). [ThingSetupPolicy] decides for each
 * sensor from its advert flags and the last CONFIG page 1 the phone read from it.
 *
 * - Platforms only feed it. The scan gives [SetupSighting]s, a connected sensor's page 1 read gives
 *   [pageRead], and the app leaving the foreground calls [endSession]. Nothing here reads or writes
 *   a sensor, so no page 1 read happens because of it.
 * - Page 1 is the fallback for firmware whose advert has no setup flag. An advert counts only when
 *   it was heard after the last page 1 read of that sensor, so a cached advert with bit 6 cannot
 *   undo the page that says setup is done.
 * - The wizard opens by itself once per sensor per foreground session. [opened] spends that open,
 *   for an automatic open and for any wizard run the user started. Ids match case insensitively.
 *
 * Everything it holds lasts one foreground session and stays in memory.
 */
class ThingSetupWatch {
    private data class Session(val pages: Map<String, Page> = emptyMap(), val opened: Set<String> = emptySet())

    private val session = MutableStateFlow(Session())

    /** A connected sensor [id] returned page 1 [config] at [readMs], from Device settings or the wizard. */
    fun pageRead(id: String, config: DeviceConfig, readMs: Long) {
        session.update { it.copy(pages = it.pages + (id.lowercase() to Page(id, config, readMs))) }
    }

    /** The wizard opened for [id]. It will not open by itself for that sensor again this session. */
    fun opened(id: String) {
        session.update { it.copy(opened = it.opened + id.lowercase()) }
    }

    /** The app left the foreground. The next session starts with nothing read and nothing opened. */
    fun endSession() {
        session.value = Session()
    }

    /** The sensors among [sightings], and those with a page 1 read, that need setup now. */
    fun needs(sightings: Flow<List<SetupSighting>>): Flow<List<SetupNeed>> =
        combine(sightings, session) { seen, s -> decide(seen, s.pages.values.toList(), s.opened) }

    /** Page 1 of [id] as read at [readMs], the input of [decide]. */
    data class Page(val id: String, val config: DeviceConfig, val readMs: Long)

    companion object {
        /**
         * The pure rule behind [needs]. Sighted sensors come first in their order, then sensors known
         * only from a page read. [opened] holds lowercase ids.
         */
        fun decide(sightings: List<SetupSighting>, pages: List<Page>, opened: Set<String>): List<SetupNeed> {
            val byId = pages.associateBy { it.id.lowercase() }
            val out = LinkedHashMap<String, SetupNeed>()
            for (s in sightings) {
                val key = s.id.lowercase()
                if (key in out) continue
                val page = byId[key]
                // The policy takes advert flags only from an advert newer than the last page 1 read.
                val fresh = page == null || (s.seenMs != null && s.seenMs > page.readMs)
                val flags = s.advertFlags.takeIf { fresh }
                if (ThingSetupPolicy.needsSetup(flags, page?.config)) out[key] = SetupNeed(s.id, key in opened)
            }
            for ((key, page) in byId) {
                if (key in out || sightings.any { it.id.equals(key, ignoreCase = true) }) continue
                if (ThingSetupPolicy.needsSetup(null, page.config)) out[key] = SetupNeed(page.id, key in opened)
            }
            return out.values.toList()
        }
    }
}
