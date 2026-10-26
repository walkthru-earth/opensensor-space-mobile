package space.opensensor.data.history

import kotlinx.datetime.toKotlinTimeZone
import java.time.ZoneId

/** Android screens keep their `ZoneId`. Named like the class so `ArchiveDayQuery(thing, start, zone)` compiles unchanged. */
@Suppress("FunctionName")
fun ArchiveDayQuery(thing: String?, dayStartS: Long, zone: ZoneId): ArchiveDayQuery = ArchiveDayQuery(thing, dayStartS, zone.toKotlinTimeZone())
