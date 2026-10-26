package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.ble.DeviceSession
import earth.walkthru.aqsync.data.ble.NoDescriptorException
import earth.walkthru.aqsync.data.protocol.DeviceInfo
import earth.walkthru.aqsync.data.protocol.ParquetVerify
import earth.walkthru.aqsync.data.protocol.sha256Hex
import earth.walkthru.aqsync.net.BlockStore
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
import space.opensensor.model.BoardTemplate
import space.opensensor.model.BundledCatalog
import space.opensensor.model.TemplateKey

/**
 * Board templates the firmware served over `GET_DESCRIPTOR` (#191), verified and keyed by their full
 * SHA-256. [DeviceCapabilities] hands them to the catalog resolver ahead of the bundled ones.
 */
class ServedTemplates {
    private val _all = MutableStateFlow<List<BoardTemplate>>(emptyList())
    val all: StateFlow<List<BoardTemplate>> = _all.asStateFlow()

    fun add(template: BoardTemplate) {
        requireNotNull(template.key.desc) { "a served template carries its desc" }
        _all.update { held -> if (held.any { it.key.desc == template.key.desc }) held else held + template }
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
 * `<sha256>.json` and are re-verified against that name on [load].
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

    /** Registers every cached template that still verifies, deletes the rest. Returns how many loaded. */
    suspend fun load(): Int = withContext(io) {
        mutex.withLock {
            var loaded = 0
            for (block in store.list()) {
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
        val template = try {
            BoardTemplate.parse(bytes.decodeToString(), desc = hex)
        } catch (e: IllegalArgumentException) {
            return@withLock DescriptorOutcome.Rejected(e.message ?: "not a board template")
        }
        if (template.dict != dict) return@withLock DescriptorOutcome.Rejected("template dict differs from the device dict")
        withContext(io) { store.write(hex + SUFFIX, bytes) }
        served.add(template)
        DescriptorOutcome.Fetched(template)
    }

    private companion object {
        const val SUFFIX = ".json"
        val SHA256_HEX = Regex("^[0-9a-f]{64}$")
        val DESC_PREFIX = Regex("^[0-9a-f]{${TemplateKey.DESC_PREFIX_HEX}}$")
    }
}
