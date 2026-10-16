package earth.walkthru.aqsync.data.archive

import earth.walkthru.aqsync.archive.ArchiveRecord
import earth.walkthru.aqsync.data.local.database.ArchivedFile

// The Room entity (archived_file, v11) and the shared record carry the same columns. These two
// mappers are the only place they meet, so the schema stays untouched.

fun ArchivedFile.toRecord(): ArchiveRecord = ArchiveRecord(
    station = station,
    name = name,
    bytes = bytes,
    crc32 = crc32,
    sha256 = sha256,
    syncedAtEpochMs = syncedAtEpochMs,
    sourceDevice = sourceDevice,
    indexedAtEpochMs = indexedAtEpochMs,
    indexError = indexError,
    rows = rows,
    rowGroups = rowGroups,
    schemaVersion = schemaVersion,
    bootId = bootId,
    purpose = purpose,
    utcRows = utcRows,
    firstUtcNs = firstUtcNs,
    lastUtcNs = lastUtcNs,
    firstMonoUs = firstMonoUs,
    lastMonoUs = lastMonoUs,
    firstSeq = firstSeq,
    lastSeq = lastSeq,
    pmValidRows = pmValidRows,
    estFirstUtcNs = estFirstUtcNs,
    estLastUtcNs = estLastUtcNs,
    estAnchorFile = estAnchorFile,
)

/** [id] 0 lets Room assign one. The `(station, name)` unique index replaces an existing row. */
fun ArchiveRecord.toEntity(id: Long = 0): ArchivedFile = ArchivedFile(
    id = id,
    station = station,
    name = name,
    bytes = bytes,
    crc32 = crc32,
    sha256 = sha256,
    syncedAtEpochMs = syncedAtEpochMs,
    sourceDevice = sourceDevice,
    indexedAtEpochMs = indexedAtEpochMs,
    indexError = indexError,
    rows = rows,
    rowGroups = rowGroups,
    schemaVersion = schemaVersion,
    bootId = bootId,
    purpose = purpose,
    utcRows = utcRows,
    firstUtcNs = firstUtcNs,
    lastUtcNs = lastUtcNs,
    firstMonoUs = firstMonoUs,
    lastMonoUs = lastMonoUs,
    firstSeq = firstSeq,
    lastSeq = lastSeq,
    pmValidRows = pmValidRows,
    estFirstUtcNs = estFirstUtcNs,
    estLastUtcNs = estLastUtcNs,
    estAnchorFile = estAnchorFile,
)
