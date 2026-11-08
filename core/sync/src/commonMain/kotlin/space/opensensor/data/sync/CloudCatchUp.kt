package space.opensensor.data.sync

import kotlinx.coroutines.flow.first
import space.opensensor.archive.ArchiveIndex
import space.opensensor.archive.ArchiveRecord
import space.opensensor.archive.FileIdentity
import space.opensensor.archive.PrunedFile
import space.opensensor.data.protocol.SafeName
import space.opensensor.model.FileChecksum

/**
 * Where a Thing's files sit in the cloud (bridge decision 13). [raw] holds the board files in the
 * Hive layout, `thing=<uuid>/year=YYYY/month=MM/day=DD/<file>.parquet`. [compacted] holds the day
 * roll ups, never inside the raw `thing=` folders. The planner reads a compacted file in the same
 * `thing=` and date folders under its own prefix. Each prefix is a key prefix of the listing, with
 * or without a final slash. A null [compacted] means the owner keeps no roll ups.
 */
data class CloudPrefixes(val raw: String, val compacted: String? = null) {
    internal val rawRoot: String get() = folder(raw)
    internal val compactedRoot: String? get() = compacted?.let(::folder)

    private fun folder(prefix: String): String = if (prefix.isEmpty() || prefix.endsWith('/')) prefix else "$prefix/"
}

/**
 * One object of a remote listing. [key] is the full object key, [bytes] its size, and [checksum]
 * the `file:checksum` multihash of its Item when the reader has it, else null. A bucket listing gives
 * the size but no SHA-256, so the checksum comes from the Item or an upload status listing.
 */
data class RemoteFile(val key: String, val bytes: Long, val checksum: String? = null) {
    /** The SHA-256 hex of [checksum], or null when it is unknown or no SHA-256 multihash. */
    val sha256: String? get() = checksum?.let(FileChecksum::sha256Of)
}

/** Which tree a remote key belongs to. */
enum class CloudFileKind { RAW, COMPACTED, OTHER }

/** What the catch up does with one remote file. */
sealed interface CatchUpDecision {
    /** Not in the archive, download it and commit it like a device file. */
    data object Fetch : CatchUpDecision

    /** Leave it. Nothing is downloaded and the archive does not change. */
    data class Skip(val reason: SkipReason) : CatchUpDecision

    /**
     * The archive or the listing holds other bytes under the same name. Nothing is downloaded and
     * nothing is overwritten, the owner sees it. [archived] is the local copy, when there is one.
     */
    data class Conflict(val reason: ConflictReason, val archived: ArchiveRecord? = null) : CatchUpDecision
}

enum class SkipReason {
    /** The archive holds this file, the same Thing, File Item id and SHA-256. */
    SAME_FILE,

    /** The archive holds this name at this size, but one side has no checksum to prove the bytes. */
    SAME_NAME_UNPROVEN,

    /** The archive holds these bytes under another name. One copy is enough. */
    SAME_BYTES_OTHER_NAME,

    /** The listing holds this file again, under another key. The first one is planned. */
    REPEAT_IN_LISTING,

    /** The user removed this file from the phone in Settings. */
    REMOVED_BY_USER,

    /** Raw files cover this UTC day, so its compacted file is never read beside them (decision 13). */
    RAW_COVERS_DAY,

    /** The archive holds this UTC day's compacted file, so its raw files are never read beside it (decision 13). */
    COMPACTED_COVERS_DAY,

    /** A key of another Thing. */
    OTHER_THING,

    /** Not a dated Parquet file of a known prefix, such as an Item, a Collection or an unsynced file. */
    NOT_A_DATA_FILE,
}

enum class ConflictReason {
    /** Same name, known checksums, other bytes. */
    OTHER_CHECKSUM,

    /** Same name, no checksum to compare, and another size. */
    OTHER_SIZE,

    /** The listing holds several different files for one name, or several compacted files for one day. */
    LISTING_DISAGREES,
}

/**
 * One planned remote file. [name] is its key below the prefix, the archive name it would get, and
 * [utcDate] the `YYYY-MM-DD` of its folders, both null for [CloudFileKind.OTHER].
 */
data class CatchUpEntry(
    val remote: RemoteFile,
    val kind: CloudFileKind,
    val name: String?,
    val utcDate: String?,
    val decision: CatchUpDecision,
)

/** The plan of one Thing's listing, one entry per remote file in listing order. */
data class CatchUpPlan(val thing: String, val entries: List<CatchUpEntry>) {
    val toFetch: List<CatchUpEntry> get() = entries.filter { it.decision == CatchUpDecision.Fetch }
    val conflicts: List<CatchUpEntry> get() = entries.filter { it.decision is CatchUpDecision.Conflict }

    /**
     * The archived files the listing proves are in the cloud, the same Thing, File Item id and
     * SHA-256. [CloudCatchUp.markUploaded] marks them. A file proven only by name and size is not here.
     */
    val uploaded: List<FileIdentity> = entries.mapNotNull { entry ->
        val sha = entry.remote.sha256
        val name = entry.name
        if (entry.kind != CloudFileKind.RAW || sha == null || name == null) return@mapNotNull null
        if ((entry.decision as? CatchUpDecision.Skip)?.reason != SkipReason.SAME_FILE) return@mapNotNull null
        FileIdentity.of(thing, name, sha)
    }.distinct()
}

/**
 * Plans a catch up or restore of one owned Thing from a remote listing against the phone archive
 * (#273). It is pure and does no I/O. A reader downloads [CatchUpPlan.toFetch] and commits each file
 * with the same size, CRC-32 and `PAR1` checks as a device download.
 *
 * - **Same file.** A board file whose File Item id and checksum match an archived file is skipped, so
 *   its rows are never stored or counted twice. Any other file, such as a compacted day file, is
 *   matched by its full Hive name, since its bare name repeats across days.
 * - **Never a second copy.** Bytes already archived under another name are skipped, a name already
 *   archived is never fetched again, and a file the listing repeats is planned once.
 * - **Never overwrite.** The same name with other bytes is a conflict, left for the owner.
 * - **Raw or compacted, never both (decision 13).** Each UTC day reads one tree. The archive decides
 *   first, archived board files make it a raw day and an archived compacted file a compacted day.
 *   Otherwise any raw file in the listing makes it a raw day. A raw day skips its compacted files, a
 *   compacted day skips its raw files, and a day with only a compacted file fetches that one file.
 */
object CatchUpPlanner {
    fun plan(
        thing: String,
        prefixes: CloudPrefixes,
        listing: List<RemoteFile>,
        archived: Collection<ArchiveRecord>,
        pruned: Collection<PrunedFile> = emptyList(),
    ): CatchUpPlan {
        val mine = archived.filter { it.thing == thing }
        val byKey = mine.groupBy { matchKey(it.name) }
        val bySha = mine.filter { it.sha256.isNotEmpty() }.groupBy { it.sha256 }
        val removed = pruned.filter { it.thing == thing }.mapTo(HashSet()) { matchKey(it.name) }

        // The tree of each UTC day. The archive decides first, then any raw file in the listing. Raw
        // files of a compacted day are settled before the repeat check, so they never stand for a
        // raw file of another day.
        val (archivedRaw, archivedCompacted) = mine.partition { FileIdentity.isBoardFile(it.name) }
        val rawDays = archivedRaw.mapNotNullTo(HashSet()) { dayOf(it.name) }
        val compactedDays = archivedCompacted.mapNotNullTo(HashSet()) { dayOf(it.name) } - rawDays
        val located = listing.map { remote ->
            val at = locate(thing, prefixes, remote)
            val covered = at.decision == null && at.kind == CloudFileKind.RAW && at.utcDate in compactedDays
            if (covered) Located(at.remote, at.kind, at.name, at.utcDate, skip(SkipReason.COMPACTED_COVERS_DAY)) else at
        }
        located.filter { it.decision == null && it.kind == CloudFileKind.RAW }.mapNotNullTo(rawDays) { it.utcDate }

        val repeats = repeatsInListing(located)
        val listedCompacted = located.filter { it.decision == null && it.kind == CloudFileKind.COMPACTED }
            .groupBy { it.utcDate }

        val entries = located.mapIndexed { index, at ->
            val decision = when {
                at.decision != null -> at.decision
                at.kind == CloudFileKind.COMPACTED && at.utcDate in rawDays -> skip(SkipReason.RAW_COVERS_DAY)
                at.kind == CloudFileKind.COMPACTED -> oneCompactedPerDay(at, listedCompacted.getValue(at.utcDate))
                    ?: againstArchive(at, byKey, bySha, removed)
                else -> repeats[index] ?: againstArchive(at, byKey, bySha, removed)
            }
            CatchUpEntry(at.remote, at.kind, at.name, at.utcDate, decision)
        }
        return CatchUpPlan(thing, entries)
    }

    /**
     * A day holds one compacted file. Several are planned once only when every one has the same
     * known checksum, then the first is checked against the archive and the rest are repeats. Any
     * other mix is a conflict in every key, so the planner never picks one by chance. Null means
     * [at] is the day's one file.
     */
    private fun oneCompactedPerDay(at: Located, day: List<Located>): CatchUpDecision? {
        if (day.size < 2) return null
        val shas = day.map { it.remote.sha256 }
        if (null in shas || shas.distinct().size > 1) return CatchUpDecision.Conflict(ConflictReason.LISTING_DISAGREES)
        return if (at === day.first()) null else skip(SkipReason.REPEAT_IN_LISTING)
    }

    private class Located(
        val remote: RemoteFile,
        val kind: CloudFileKind,
        val name: String?,
        val utcDate: String?,
        val decision: CatchUpDecision? = null,
    ) {
        val key: String? get() = name?.let(::matchKey)
    }

    /**
     * What names one file within a Thing. A board file's File Item id, the same in every tree and
     * folder. Any other file's full Hive name, since its bare name, such as `day.parquet`, repeats
     * across days. An id has no slash and a Hive name always has one, so the two never meet.
     */
    private fun matchKey(name: String): String = if (FileIdentity.isBoardFile(name)) FileIdentity.itemIdOf(name) else name

    private fun dayOf(name: String): String? = (HivePartition.of(name) as? HivePartition.Dated)?.isoDate

    private fun locate(thing: String, prefixes: CloudPrefixes, remote: RemoteFile): Located {
        val compactedRoot = prefixes.compactedRoot
        // The longer prefix first, so a compacted prefix nested in the raw one is still found.
        val roots = listOfNotNull(
            CloudFileKind.RAW to prefixes.rawRoot,
            compactedRoot?.let { CloudFileKind.COMPACTED to it },
        ).sortedByDescending { it.second.length }
        val (kind, root) = roots.firstOrNull { remote.key.startsWith(it.second) }
            ?: return Located(remote, CloudFileKind.OTHER, null, null, skip(SkipReason.NOT_A_DATA_FILE))
        val name = remote.key.removePrefix(root)
        val partition = HivePartition.of(name)
        if (!SafeName.isValid(name) || partition !is HivePartition.Dated) {
            return Located(remote, kind, null, null, skip(SkipReason.NOT_A_DATA_FILE))
        }
        if (partition.thing != thing) return Located(remote, kind, name, partition.isoDate, skip(SkipReason.OTHER_THING))
        return Located(remote, kind, name, partition.isoDate)
    }

    /**
     * Per listing index, the decision of a raw file the listing holds more than once, by [matchKey]
     * or by checksum. A group that agrees plans one file and skips the rest, the first that carries a
     * checksum, so its proof reaches [CatchUpPlan.uploaded]. A group with other bytes for one name is
     * a conflict in every key, so neither copy is picked by chance.
     */
    private fun repeatsInListing(located: List<Located>): Map<Int, CatchUpDecision> {
        val out = HashMap<Int, CatchUpDecision>()
        val open = located.withIndex().filter { it.value.decision == null && it.value.kind == CloudFileKind.RAW }
        for (group in open.groupBy { it.value.key }.values) {
            if (group.size < 2) continue
            if (!agree(group.map { it.value })) {
                group.forEach { out[it.index] = CatchUpDecision.Conflict(ConflictReason.LISTING_DISAGREES) }
            } else {
                val kept = group.firstOrNull { it.value.remote.sha256 != null } ?: group.first()
                group.filter { it !== kept }.forEach { out[it.index] = skip(SkipReason.REPEAT_IN_LISTING) }
            }
        }
        val bySha = open.filter { it.index !in out && it.value.remote.sha256 != null }
            .groupBy { it.value.remote.sha256 }
        for (group in bySha.values) group.drop(1).forEach { out[it.index] = skip(SkipReason.REPEAT_IN_LISTING) }
        return out
    }

    /** True when every file has one size and no two known checksums differ. */
    private fun agree(files: List<Located>): Boolean {
        if (files.map { it.remote.bytes }.distinct().size > 1) return false
        return files.mapNotNull { it.remote.sha256 }.distinct().size <= 1
    }

    private fun againstArchive(
        at: Located,
        byKey: Map<String, List<ArchiveRecord>>,
        bySha: Map<String, List<ArchiveRecord>>,
        removed: Set<String>,
    ): CatchUpDecision {
        val sha = at.remote.sha256
        val local = byKey[at.key].orEmpty()
        if (local.isNotEmpty()) {
            if (sha != null) {
                local.firstOrNull { it.sha256 == sha }?.let { return skip(SkipReason.SAME_FILE) }
                local.firstOrNull { it.sha256.isNotEmpty() }?.let {
                    return CatchUpDecision.Conflict(ConflictReason.OTHER_CHECKSUM, it)
                }
            }
            // One side has no checksum. Equal size keeps the archived copy, another size is a conflict.
            local.firstOrNull { it.bytes != at.remote.bytes }?.let {
                return CatchUpDecision.Conflict(ConflictReason.OTHER_SIZE, it)
            }
            return skip(SkipReason.SAME_NAME_UNPROVEN)
        }
        if (sha != null && bySha[sha] != null) return skip(SkipReason.SAME_BYTES_OTHER_NAME)
        if (at.key in removed) return skip(SkipReason.REMOVED_BY_USER)
        return CatchUpDecision.Fetch
    }

    private fun skip(reason: SkipReason) = CatchUpDecision.Skip(reason)
}

/**
 * Ties [CatchUpPlanner] to an [ArchiveIndex] and the archive's one [DeviceCoordinator]. It plans from
 * the Thing's archived records and the files the user removed, and marks the files a listing proves
 * are uploaded. It downloads nothing. Uploading stays off by default.
 */
class CloudCatchUp(private val index: ArchiveIndex, private val coordinator: DeviceCoordinator) {
    suspend fun plan(thing: String, prefixes: CloudPrefixes, listing: List<RemoteFile>): CatchUpPlan =
        CatchUpPlanner.plan(thing, prefixes, listing, index.observeThing(thing).first(), coordinator.pruned.forThing(thing))

    /**
     * Sets the uploaded marker of every archived file in [plan]'s [CatchUpPlan.uploaded], at
     * [atEpochMs], each under the Thing gate like every index write. Returns how many records
     * changed. A file already marked keeps its first mark. Never call it with the gate held.
     */
    suspend fun markUploaded(plan: CatchUpPlan, atEpochMs: Long): Int =
        plan.uploaded.count { identity -> coordinator.withThing(identity.thing) { index.markUploaded(identity, atEpochMs) } }
}
