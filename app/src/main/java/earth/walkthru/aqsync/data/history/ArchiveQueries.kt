package earth.walkthru.aqsync.data.history

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** Includes the range as well as both derived bounds, so paging and midnight cannot reuse a result. */
data class ArchiveSeriesQuery(val station: String?, val metric: MetricSpec, val range: HistoryRange, val toS: Long) {
    val fromS: Long get() = toS - range.seconds
}

data class ArchiveDayQuery(val station: String?, val dayStartS: Long)

/** Carries provenance through asynchronous reads and independently combined UI label flows. */
data class ArchiveQueryResult<Q, V>(val query: Q, val value: V?) {
    fun forQuery(current: Q): V? = value.takeIf { query == current }
}

/** Switching queries cancels the old read and emits loading, while retaining the query on each result. */
@OptIn(ExperimentalCoroutinesApi::class)
fun <Q, V> Flow<Q>.archiveQueryResults(load: (Q) -> Flow<V?>): Flow<ArchiveQueryResult<Q, V>> = flatMapLatest { query ->
    flow {
        emit(ArchiveQueryResult<Q, V>(query, null))
        emitAll(load(query).map { ArchiveQueryResult(query, it) })
    }
}
