package space.opensensor.data.local.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Host fakes of [ArchivedFileDao] implement this instead, so the thing-scoped and aggregate
 * queries follow the fake's own `observeAll` and `forThing` with the same meaning as the SQL.
 */
interface DerivedArchivedFileQueries : ArchivedFileDao {
    private fun ArchivedFile.identity() = ArchivedFileIdentity(thing, name, bytes, crc32)

    private fun ArchivedFile.unindexed() = !isIndexed

    private fun thingRows(thing: String): Flow<List<ArchivedFile>> = observeAll().map { rows -> rows.filter { it.thing == thing } }

    override fun observeIdentitiesForThing(thing: String): Flow<List<ArchivedFileIdentity>> =
        thingRows(thing).map { rows -> rows.sortedBy { it.name }.map { it.identity() } }

    override suspend fun identitiesForThing(thing: String): List<ArchivedFileIdentity> =
        forThing(thing).sortedBy { it.name }.map { it.identity() }

    override fun observeCountForThing(thing: String): Flow<Int> = thingRows(thing).map { it.size }

    override fun observeAnyForThing(thing: String): Flow<Boolean> = thingRows(thing).map { it.isNotEmpty() }

    override fun observeUnindexedCountForThing(thing: String): Flow<Int> = thingRows(thing).map { rows -> rows.count { it.unindexed() } }

    override fun observeThingGlances(): Flow<List<ThingArchiveGlance>> = observeAll().map { rows ->
        rows.groupBy { it.thing }.map { (thing, list) ->
            ThingArchiveGlance(thing, list.size, list.maxOfOrNull { it.syncedAtEpochMs }, list.mapNotNull { it.lastUtcNs }.maxOrNull())
        }
    }

    override suspend fun anyIndexedNames(descs: List<String>, dicts: List<String>): Boolean =
        observeAll().first().any { it.isIndexed && (it.templateSha256 in descs || it.dictionarySha256 in dicts) }

    override fun observeAnyNeverIndexed(): Flow<Boolean> = observeAll().map { rows -> rows.any { it.indexedAtEpochMs == null } }

    override fun observeLatestTimed(): Flow<List<ThingTimedEnd>> = observeAll().map { rows ->
        rows.filter { r ->
            r.indexedAtEpochMs != null && r.indexError == null && (r.purpose == null || r.purpose == "telemetry") &&
                ((r.utcRows ?: 0) > 0 || (r.estFirstUtcNs != null && r.estLastUtcNs != null))
        }.groupBy { it.thing }.map { (thing, list) ->
            ThingTimedEnd(thing, list.mapNotNull { r -> if ((r.utcRows ?: 0) > 0) r.lastUtcNs else r.estLastUtcNs }.maxOrNull())
        }
    }
}
