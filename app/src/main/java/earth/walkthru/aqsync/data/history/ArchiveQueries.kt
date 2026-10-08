package earth.walkthru.aqsync.data.history

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.toKotlinTimeZone
import java.time.ZoneId

/** Includes the range as well as both derived bounds, so paging and midnight cannot reuse a result. */
data class ArchiveSeriesQuery(val station: String?, val metric: MetricSpec, val range: HistoryRange, val window: HistoryWindow) {
    val fromS: Long get() = window.fromS
    val toS: Long get() = window.toS
}

data class ArchiveDayQuery(val station: String?, val dayStartS: Long, val zone: ZoneId = ZoneId.systemDefault()) {
    val window: HistoryWindow get() = HistoryWindow.dayContaining(dayStartS, zone.toKotlinTimeZone())
}

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
