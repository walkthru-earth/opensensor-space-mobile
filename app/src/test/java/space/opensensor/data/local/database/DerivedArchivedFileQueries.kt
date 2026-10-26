package space.opensensor.data.local.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Host fakes of [ArchivedFileDao] implement this instead, so the station-scoped and aggregate
 * queries follow the fake's own `observeAll` and `forStation` with the same meaning as the SQL.
 */
interface DerivedArchivedFileQueries : ArchivedFileDao {
    private fun ArchivedFile.identity() = ArchivedFileIdentity(station, name, bytes, crc32)

    private fun ArchivedFile.unindexed() = !isIndexed

    private fun stationRows(station: String): Flow<List<ArchivedFile>> = observeAll().map { rows -> rows.filter { it.station == station } }

    override fun observeIdentitiesForStation(station: String): Flow<List<ArchivedFileIdentity>> =
        stationRows(station).map { rows -> rows.sortedBy { it.name }.map { it.identity() } }

    override suspend fun identitiesForStation(station: String): List<ArchivedFileIdentity> =
        forStation(station).sortedBy { it.name }.map { it.identity() }

    override fun observeCountForStation(station: String): Flow<Int> = stationRows(station).map { it.size }

    override fun observeAnyForStation(station: String): Flow<Boolean> = stationRows(station).map { it.isNotEmpty() }

    override fun observeUnindexedCountForStation(station: String): Flow<Int> = stationRows(station).map { rows -> rows.count { it.unindexed() } }

    override fun observeStationGlances(): Flow<List<StationArchiveGlance>> = observeAll().map { rows ->
        rows.groupBy { it.station }.map { (station, list) ->
            StationArchiveGlance(station, list.size, list.maxOfOrNull { it.syncedAtEpochMs }, list.mapNotNull { it.lastUtcNs }.maxOrNull())
        }
    }

    override fun observeAnyNeverIndexed(): Flow<Boolean> = observeAll().map { rows -> rows.any { it.indexedAtEpochMs == null } }

    override fun observeLatestTimed(): Flow<List<StationTimedEnd>> = observeAll().map { rows ->
        rows.filter { r ->
            r.indexedAtEpochMs != null && r.indexError == null && (r.purpose == null || r.purpose == "telemetry") &&
                ((r.utcRows ?: 0) > 0 || (r.estFirstUtcNs != null && r.estLastUtcNs != null))
        }.groupBy { it.station }.map { (station, list) ->
            StationTimedEnd(station, list.mapNotNull { r -> if ((r.utcRows ?: 0) > 0) r.lastUtcNs else r.estLastUtcNs }.maxOrNull())
        }
    }
}
