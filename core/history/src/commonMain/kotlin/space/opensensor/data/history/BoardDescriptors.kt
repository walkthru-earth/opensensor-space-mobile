package space.opensensor.data.history

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import space.opensensor.data.ble.DeviceSession
import space.opensensor.data.ble.NoDescriptorException
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.ParquetVerify
import space.opensensor.data.protocol.sha256Hex
import space.opensensor.model.BoardTemplate
import space.opensensor.model.BundledCatalog
import space.opensensor.model.TemplateKey
import space.opensensor.net.BlockStore

/**
 * Board templates the firmware served over `GET_DESCRIPTOR` (#191), verified and keyed by their full
 * SHA-256. [DeviceCapabilities] hands them to the catalog resolver ahead of the bundled ones. Holds at
 * most [limit], the oldest goes first, so a board that keeps serving fresh templates cannot grow it.
 */
class ServedTemplates(private val limit: Int = BoardDescriptors.MAX_TEMPLATES) {
    private val _all = MutableStateFlow<List<BoardTemplate>>(emptyList())
    val all: StateFlow<List<BoardTemplate>> = _all.asStateFlow()

    fun add(template: BoardTemplate) {
        requireNotNull(template.key.desc) { "a served template carries its desc" }
        _all.update { held -> if (held.any { it.key.desc == template.key.desc }) held else (held + template).takeLast(limit) }
    }

    /** Drops the template whose full desc is [desc], if held. */
    fun remove(desc: String) {
        _all.update { held -> held.filterNot { it.key.desc == desc } }
    }

    companion object {
        /** The app's one set, shared by Android and iOS. */
        val shared = ServedTemplates()
    }
}

/** What [BoardDescriptors.ensure] did for one device. */
sealed interface DescriptorOutcome {
    /** A bundled or cached template already matches the device's desc, nothing was sent. */
    data object Known : DescriptorOutcome

    /** The template was downloaded, verified, cached and registered. */
    data class Fetched(val template: BoardTemplate) : DescriptorOutcome

    /** The device announces no well formed desc, so it serves no template. Nothing was sent. */
    data object Unsupported : DescriptorOutcome

    /** The device answered `ERROR` 17, it has no template to serve. */
    data object NoDescriptor : DescriptorOutcome

    /** The bytes failed verification and were dropped. */
    data class Rejected(val reason: String) : DescriptorOutcome
}

/**
 * Fetches, verifies and caches the board template a device serves (#191). The bytes are kept only
 * when their SHA-256 starts with the device's 16 hex `desc`, they parse as an `OpenSensorTemplate`
 * and their `firmware.dict` is the device's `dict`. Verified templates live in [store] as
 * `<sha256>.json` and are re-verified against that name on [load]. At most [MAX_TEMPLATES] are kept,
 * the oldest file is deleted first.
 *
 * The 16 hex prefix is all a device gives before any file is synced. A synced file's `opensensor` footer
 * then gives the full trusted `template.sha256`, which [confirm] takes. A served template that shares
 * its prefix but not its full digest is deleted, and [ensure] rejects bytes that still differ from it.
 * Bundled templates are never touched, their full desc is pinned by the build.
 *
 * Every [store] call blocks, so the work runs on [io].
 */
class BoardDescriptors(
    private val store: BlockStore,
    private val served: ServedTemplates = ServedTemplates.shared,
    private val bundled: List<BoardTemplate> = BundledCatalog.templates,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    /** The full `template.sha256` the footers named, by its 16 hex prefix. In memory only. */
    private val footerDigests = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Registers the newest cached templates that still verify, deletes the rest. Returns how many loaded. */
    suspend fun load(): Int = withContext(io) {
        mutex.withLock {
            var loaded = 0
            for (block in store.list().sortedByDescending { it.modifiedEpochMs }) {
                if (loaded == MAX_TEMPLATES) {
                    store.delete(block.name)
                    continue
                }
                val hex = block.name.removeSuffix(SUFFIX)
                val bytes = store.read(block.name) ?: continue
                val template = if (block.name.endsWith(SUFFIX) && SHA256_HEX.matches(hex) && ParquetVerify.sha256Hex(bytes) == hex) {
                    runCatching { BoardTemplate.parse(bytes.decodeToString(), desc = hex) }.getOrNull()
                } else {
                    null
                }
                if (template == null) {
                    store.delete(block.name)
                } else {
                    served.add(template)
                    loaded++
                }
            }
            loaded
        }
    }

    /** [ensure] over the open [session], whose `INFO` is [info]. */
    suspend fun ensure(info: DeviceInfo, session: DeviceSession): DescriptorOutcome = ensure(info) { session.downloadDescriptor() }

    /**
     * Makes sure a template for [info]'s `desc` is known, calling [download] only when neither a
     * bundled nor a served template already matches. [download] must run on a paired, open link.
     */
    suspend fun ensure(info: DeviceInfo, download: suspend () -> ByteArray): DescriptorOutcome = mutex.withLock {
        val desc = info.desc?.lowercase()
        val dict = info.dict
        if (desc == null || dict == null || !DESC_PREFIX.matches(desc)) return@withLock DescriptorOutcome.Unsupported
        if ((bundled + served.all.value).any { it.key.matchesDesc(desc) }) return@withLock DescriptorOutcome.Known
        val bytes = try {
            download()
        } catch (e: CancellationException) {
            throw e
        } catch (e: NoDescriptorException) {
            return@withLock DescriptorOutcome.NoDescriptor
        }
        val hex = ParquetVerify.sha256Hex(bytes)
        if (!hex.startsWith(desc)) return@withLock DescriptorOutcome.Rejected("sha256 does not start with desc")
        footerDigests.value[desc]?.let { trusted ->
            if (trusted != hex) return@withLock DescriptorOutcome.Rejected("sha256 differs from the file footer")
        }
        val template = try {
            BoardTemplate.parse(bytes.decodeToString(), desc = hex)
        } catch (e: IllegalArgumentException) {
            return@withLock DescriptorOutcome.Rejected(e.message ?: "not a board template")
        }
        if (template.dict != dict) return@withLock DescriptorOutcome.Rejected("template dict differs from the device dict")
        withContext(io) {
            store.list().sortedBy { it.modifiedEpochMs }.dropLast(MAX_TEMPLATES - 1).forEach { store.delete(it.name) }
            store.write(hex + SUFFIX, bytes)
        }
        served.add(template)
        DescriptorOutcome.Fetched(template)
    }

    /**
     * Takes a synced file's footer `template.sha256` as the trusted full digest for its 16 hex prefix.
     * Deletes every served template, on disk and in [ServedTemplates], that shares the prefix but not the
     * digest, so the next [ensure] downloads again and rejects bytes that still differ. A bundled desc is
     * never deleted. A value that is not 64 hex digits deletes nothing. A digest already taken returns at
     * once, so a caller may pass every file's footer. It is taken only after the deletes succeed. Returns
     * how many templates were deleted.
     */
    suspend fun confirm(sha256: String): Int {
        val full = sha256.lowercase()
        if (!SHA256_HEX.matches(full)) return 0
        val prefix = full.take(TemplateKey.DESC_PREFIX_HEX)
        if (footerDigests.value[prefix] == full) return 0
        return mutex.withLock {
            val pinned = bundled.mapNotNullTo(HashSet()) { it.key.desc }
            fun contradicted(desc: String) = desc.startsWith(prefix) && desc != full && desc !in pinned
            // Out of memory first, so a failing disk delete never leaves the template served.
            val held = served.all.value.mapNotNull { it.key.desc }.filter(::contradicted)
            held.forEach(served::remove)
            val files = withContext(io) {
                store.list().map { it.name }.filter { it.endsWith(SUFFIX) && contradicted(it.removeSuffix(SUFFIX)) }
                    .onEach { store.delete(it) }
            }
            // Taken only once the disk is clean, so a failed delete is retried by the next confirm.
            footerDigests.update { it + (prefix to full) }
            (held + files.map { it.removeSuffix(SUFFIX) }).toSet().size
        }
    }

    companion object {
        /** Most templates kept on disk and in [ServedTemplates]. A real fleet has a handful of boards. */
        const val MAX_TEMPLATES = 16

        private const val SUFFIX = ".json"
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
        private val DESC_PREFIX = Regex("^[0-9a-f]{${TemplateKey.DESC_PREFIX_HEX}}$")
    }
}
