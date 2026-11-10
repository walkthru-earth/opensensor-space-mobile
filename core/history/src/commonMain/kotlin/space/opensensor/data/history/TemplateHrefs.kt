package space.opensensor.data.history

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import space.opensensor.data.protocol.ParquetVerify
import space.opensensor.data.protocol.sha256Hex
import space.opensensor.model.BoardTemplate
import space.opensensor.net.BodyLimit
import space.opensensor.net.HttpRangeEngine
import space.opensensor.net.HttpRangeException
import space.opensensor.net.HttpRangeResponse
import space.opensensor.net.HttpTransportException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A file footer's link to its board template, the full [desc] (`template.sha256`) the bytes must hash
 * to, the footer [dict] (`dictionary.sha256`) and the [href] (`template.href`) to fetch them from.
 */
data class TemplateLink(val desc: String, val dict: String?, val href: String) {
    companion object {
        /** The link of [footer], null when it names no template digest or no href. */
        fun of(footer: OpenSensorFooter?): TemplateLink? {
            val desc = footer?.templateSha256 ?: return null
            val href = footer.templateHref ?: return null
            return TemplateLink(desc.lowercase(), footer.dictionarySha256?.lowercase(), href)
        }
    }
}

/** What [TemplateHrefs.fetch] did for one [TemplateLink]. Only [Failed] and [Rejected] sent a request that did not bind. */
sealed interface HrefOutcome {
    /** The bytes hashed to the desc, parsed and now resolve as a served template. */
    data class Fetched(val template: BoardTemplate) : HrefOutcome

    /** A bundled, served or cached template already has the desc. Nothing was sent. */
    data object Known : HrefOutcome

    /** The network switch is off. Nothing was sent, and a later request may try again. */
    data object Off : HrefOutcome

    /** The href is not an https link on the firmware template host. Nothing was sent. */
    data object Refused : HrefOutcome

    /** This app run already tried the desc, or tried as many descs as it may. Nothing was sent. */
    data object Tried : HrefOutcome

    /** The request failed, a status other than 200, a redirect, a body over the cap, a timeout or no network. */
    data class Failed(val reason: String) : HrefOutcome

    /** The bytes arrived and were dropped, a SHA-256 other than the desc, not a template or another dict. */
    data class Rejected(val reason: String) : HrefOutcome
}

/**
 * Fetches the template a file footer names when nothing resolves its desc (#260). A file whose template
 * is neither bundled nor served keeps its rows and has no metric values. Its footer `template.href`
 * names immutable bytes whose SHA-256 is the footer desc, so one anonymous, read only GET recovers it.
 *
 * - Only while [allowed] is true, the Settings switch that already gates the public Discover feed and
 *   the basemap. Off, no request is made.
 * - Only an https href on the firmware template host, a commit pinned path under
 *   `opensensor-space-firmware/docs/shared/templates/` ([isFirmwareTemplate]). No redirect is followed,
 *   a 3xx answer fails. The app builds [engine] with redirects off as well.
 * - At most [maxBytes] of body and [timeout] for the GET. Binding is not timed, it may wait while a
 *   board serves a template over BLE.
 * - Each desc at most once per app run, and a failure is remembered, so a bad desc never loops. At most
 *   [MAX_DESCS] descs per run.
 * - The bytes bind only when their SHA-256 is the desc. [BoardDescriptors.adopt] checks it again,
 *   parses them and caches them next to the served templates, so [FileTemplates] and LIVE resolve them
 *   and [TemplateRederive] derives History again. A tampered body is dropped and [log] gets the desc,
 *   never the body or the href.
 *
 * Nothing here blocks sync. [request] only queues a link, and [run] fetches in the History start.
 */
class TemplateHrefs(
    private val engine: HttpRangeEngine,
    private val descriptors: BoardDescriptors,
    private val allowed: Flow<Boolean>,
    private val timeout: Duration = TIMEOUT,
    private val maxBytes: Long = MAX_BYTES,
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()

    /** The descs this run tried, whatever came of it. */
    private val tried = HashSet<String>()

    private val requests = Channel<TemplateLink>(capacity = QUEUE, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Queues [link] for [run]. Never blocks and never fails. A link already tried is skipped there. */
    fun request(link: TemplateLink) {
        requests.trySend(link)
    }

    /**
     * Until cancelled, fetches each [request]ed link, and each time [allowed] turns on (at start too) the
     * links [unresolved] finds among files already indexed. One link at a time.
     */
    suspend fun run(unresolved: suspend () -> List<TemplateLink>) {
        coroutineScope {
            launch {
                allowed.distinctUntilChanged().collect { on ->
                    if (!on) return@collect
                    val links = try {
                        unresolved()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("template links unreadable: ${e::class.simpleName}")
                        emptyList()
                    }
                    links.forEach(::request)
                }
            }
            for (link in requests) fetch(link)
        }
    }

    /** Fetches [link] once, under the rules above. Never throws, except to cancel. */
    suspend fun fetch(link: TemplateLink): HrefOutcome {
        val desc = link.desc.lowercase()
        if (!SHA256_HEX.matches(desc) || !isFirmwareTemplate(link.href)) return HrefOutcome.Refused
        if (!allowed.first()) return HrefOutcome.Off
        var claimed = false
        val outcome = try {
            // Inside the try, so a cache read that fails ends this link and never the loop in run.
            if (descriptors.knows(desc)) return HrefOutcome.Known
            if (!claim(desc)) return HrefOutcome.Tried
            claimed = true
            // Only the GET is timed. Binding may wait on the descriptor lock while a board serves one.
            val response = withTimeout(timeout) {
                engine.get(link.href, range = null, ifRange = null, ifNoneMatch = null, limit = BodyLimit(maxBytes, maxBytes))
            }
            bind(response, desc, link.dict)
        } catch (e: TimeoutCancellationException) {
            HrefOutcome.Failed("timeout")
        } catch (e: CancellationException) {
            // Cancelled is not a failure, so the next run of this process may try the desc again.
            if (claimed) withContext(NonCancellable) { mutex.withLock { tried.remove(desc) } }
            throw e
        } catch (e: HttpRangeException) {
            // An engine throws it only for a body over the limit, every status reaches download.
            HrefOutcome.Failed("body over $maxBytes bytes")
        } catch (e: HttpTransportException) {
            HrefOutcome.Failed("no network")
        } catch (e: Exception) {
            HrefOutcome.Failed(e::class.simpleName ?: "error")
        }
        when (outcome) {
            is HrefOutcome.Fetched -> log("template $desc fetched from its href")
            is HrefOutcome.Rejected -> log("template $desc rejected, ${outcome.reason}")
            is HrefOutcome.Failed -> log("template $desc not fetched, ${outcome.reason}")
            else -> Unit
        }
        return outcome
    }

    /** Binds the template [response] carries when it is a 200 whose body hashes to [desc]. */
    private suspend fun bind(response: HttpRangeResponse, desc: String, dict: String?): HrefOutcome {
        if (response.status in REDIRECTS) return HrefOutcome.Failed("redirect")
        if (response.status != OK) return HrefOutcome.Failed("HTTP ${response.status}")
        if (ParquetVerify.sha256Hex(response.body) != desc) return HrefOutcome.Rejected("sha256 differs from the footer")
        return when (val kept = descriptors.adopt(desc, dict, response.body)) {
            is DescriptorOutcome.Fetched -> HrefOutcome.Fetched(kept.template)
            DescriptorOutcome.Known -> HrefOutcome.Known
            is DescriptorOutcome.Rejected -> HrefOutcome.Rejected(kept.reason)
            DescriptorOutcome.NoDescriptor, DescriptorOutcome.Unsupported -> HrefOutcome.Rejected("not a template digest")
        }
    }

    /** Takes [desc] for this run. False when it was taken, or when the run has taken [MAX_DESCS]. */
    private suspend fun claim(desc: String): Boolean = mutex.withLock {
        if (desc in tried || tried.size >= MAX_DESCS) false else tried.add(desc)
    }

    companion object {
        /** The longest a template GET may take, connect to last byte. */
        val TIMEOUT: Duration = 10.seconds

        /** The largest template body read. The bundled templates are under 30 KiB. */
        const val MAX_BYTES: Long = 256L * 1024

        /** The most descs one app run fetches. A real fleet has a handful of templates. */
        const val MAX_DESCS = 64

        private const val QUEUE = 64
        private const val OK = 200
        private val REDIRECTS = 300..399
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

        /**
         * The firmware template host and path, `https://raw.githubusercontent.com/walkthru-earth/
         * opensensor-space-firmware/<40 hex commit>/docs/shared/templates/<board>/<dict>.json`, as the
         * firmware footer writes it (`kTemplateHref`, firmware `tools/pinned_uris.py`). The file is named
         * by the 64 hex dict, and its bytes hash to the desc. No port, user, query, fragment, escape or
         * dot segment fits.
         */
        private val FIRMWARE_TEMPLATE = Regex(
            "^https://raw\\.githubusercontent\\.com/walkthru-earth/opensensor-space-firmware/[0-9a-f]{40}" +
                "/docs/shared/templates/[a-z0-9][a-z0-9-]*/[0-9a-f]{64}\\.json$",
        )

        /** True when [href] is an https link on the firmware template host, the only one fetched. */
        fun isFirmwareTemplate(href: String): Boolean = FIRMWARE_TEMPLATE.matches(href)
    }
}
