package space.opensensor.data.history

import kotlinx.datetime.toKotlinTimeZone
import java.time.ZoneId

/** Android screens keep their `ZoneId`. Named like the class so `ArchiveDayQuery(station, start, zone)` compiles unchanged. */
@Suppress("FunctionName")
fun ArchiveDayQuery(station: String?, dayStartS: Long, zone: ZoneId): ArchiveDayQuery = ArchiveDayQuery(station, dayStartS, zone.toKotlinTimeZone())
