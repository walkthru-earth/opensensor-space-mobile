package space.opensensor.data.archive

import space.opensensor.data.local.database.ThingSourceDao
import space.opensensor.data.local.database.ThingSourceEntity
import space.opensensor.data.sync.ThingSource
import space.opensensor.data.sync.ThingSources

/** The shared [ThingSources] over the `thing_source` table, on both phones. */
class RoomThingSources(private val dao: ThingSourceDao) : ThingSources {
    override suspend fun get(thing: String): ThingSource? = dao.get(thing)?.toSource()

    override suspend fun all(): List<ThingSource> = dao.all().map { it.toSource() }

    override suspend fun recordIfAbsent(source: ThingSource): Boolean =
        dao.insertIfAbsent(ThingSourceEntity(source.thing, source.kind, source.kindVersion, source.configJson)) != -1L

    private fun ThingSourceEntity.toSource() = ThingSource(thing, kind, kindVersion, configJson)
}
