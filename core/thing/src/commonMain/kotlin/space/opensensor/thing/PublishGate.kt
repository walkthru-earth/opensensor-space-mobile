package space.opensensor.thing

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import space.opensensor.data.history.OpenSensorFooter
import space.opensensor.data.history.parquet.ByteReader
import space.opensensor.model.BoardTemplate
import space.opensensor.model.ThingId
import space.opensensor.publicfeed.H3Grid
import space.opensensor.publicfeed.platformH3Grid
import space.opensensor.stac.CanonicalJson
import space.opensensor.stac.FileItemResult
import space.opensensor.stac.FileItems

/**
 * Whether the owner opted a Thing in to publishing, from that Thing's settings with confirmation.
 * Off for every Thing by default ([NONE]).
 */
fun interface UploadConsent {
    fun optedIn(thing: String): Boolean

    companion object {
        /** No Thing is opted in. */
        val NONE = UploadConsent { false }
    }
}

/** Leave to carry one opted in Thing's files to the public catalog. Only [PublishGate.ticket] issues one. */
class PublishTicket internal constructor(
    val kind: KindId,
    val thing: String,
    val policy: PublishPolicy.OptInCcBy,
)

/** Who wrote the File Item of a [Publication]. */
enum class ItemWriter {
    /** The board wrote it as a sidecar beside its file. The phone carries it unchanged. */
    BOARD,

    /** The phone wrote it, for a file it recorded as its own Thing. */
    PHONE,
}

/**
 * One file cleared to upload, with [itemJson], the exact File Item text to store at [sidecarPath]
 * beside it. Only [PublishGate] makes one, so an upload that takes a [Publication] can carry no other
 * Item. For a [ItemWriter.BOARD] file [itemJson] is the board's own sidecar, never an Item the phone built.
 */
class Publication internal constructor(
    val thing: String,
    val fileName: String,
    val sidecarPath: String,
    val itemJson: String,
    val writer: ItemWriter,
)

/** What [PublishGate] says about one file. Only [Cleared] carries a [Publication]. */
sealed interface PublishCheck {
    data class Cleared(val publication: Publication) : PublishCheck

    /** The owner turned upload off for the Thing after the ticket was issued. */
    data object OptedOut : PublishCheck

    /** The file's footer names another Thing than the ticket. */
    data object OtherThing : PublishCheck

    /** The file gets no File Item for [reason], so the public catalog must not list it. */
    data class NoItem(val reason: FileItemResult.NoItem) : PublishCheck

    /** The board wrote no sidecar beside the file. */
    data object NoSidecar : PublishCheck

    /** The board's sidecar is not a JSON object. */
    data object SidecarUnreadable : PublishCheck

    /**
     * The board's sidecar is not the File Item its file, footer and template give. [at] is the first
     * path where they differ, such as `$.properties.grid:code`.
     */
    data class SidecarDiffers(val at: String) : PublishCheck

    /** The board path was given a ticket whose kind does not pull files from a board. */
    data object NotABoard : PublishCheck

    /**
     * The phone path was given a board Thing, by its kind or its footer, or any Thing that is not this
     * phone's own install. The phone never writes the Item of a board file.
     */
    data object NotThePhone : PublishCheck
}

/**
 * The one caller of [FileItems]. It issues a [PublishTicket] only for a Thing whose kind is a
 * [DataSource.Recording] source with [PublishPolicy.OptInCcBy] and whose owner opted it in
 * ([consent]). A [DataSource.PrivateRead] or [DataSource.RemoteFeed] kind, a [PublishPolicy.Never]
 * kind, an unknown kind and a Thing not opted in get none.
 *
 * The writer of a file writes its File Item (OD-2026-11-04-D), so a ticket's files take one of two
 * paths. [boardFile] is for a [DataSource.PullSync] Thing. The board wrote the Item, and the phone is
 * its transport and validator, so the phone rebuilds the Item only to check the board's sidecar and
 * clears the sidecar as the board wrote it. [phoneFile] is for a [DataSource.PhoneLog] Thing, this
 * phone's own install [phone], the one case where the Item the phone builds is the one published. It
 * refuses every board file. The gate never uploads, writes or logs.
 */
class PublishGate(
    private val kinds: ThingKinds,
    private val consent: UploadConsent = UploadConsent.NONE,
    private val phone: ThingId.PhoneInstall? = null,
) {
    /** A ticket for [thing] of [kind], or null when its data may not be published. */
    fun ticket(kind: KindId, thing: String): PublishTicket? {
        val source = kinds[kind]?.source as? DataSource.Recording ?: return null
        val policy = source.publish as? PublishPolicy.OptInCcBy ?: return null
        if (!consent.optedIn(thing)) return null
        return PublishTicket(kind, thing, policy)
    }

    /**
     * Checks the board's [sidecar], the text of the File Item the board wrote beside its file
     * [fileName], against the Item its verified bytes [reader], [footer] and [template] give, every
     * cell at the policy's `publishedRes`. Equal under [CanonicalJson] clears [sidecar] as it came,
     * byte for byte. The Item the phone built here is thrown away.
     */
    fun boardFile(
        ticket: PublishTicket,
        fileName: String,
        reader: ByteReader,
        footer: OpenSensorFooter?,
        template: BoardTemplate,
        sidecar: String?,
        h3: H3Grid = platformH3Grid(),
    ): PublishCheck {
        if (sourceOf(ticket) !is DataSource.PullSync) return PublishCheck.NotABoard
        precheck(ticket, footer)?.let { return it }
        val built = when (val result = FileItems.build(fileName, reader, footer, template, ticket.policy.publishedRes, h3)) {
            is FileItemResult.Item -> result
            is FileItemResult.NoItem -> return PublishCheck.NoItem(result)
        }
        if (sidecar == null) return PublishCheck.NoSidecar
        val board = try {
            Json.parseToJsonElement(sidecar) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return PublishCheck.SidecarUnreadable
        CanonicalJson.difference(Json.parseToJsonElement(built.json), board)?.let { return PublishCheck.SidecarDiffers(it) }
        return PublishCheck.Cleared(Publication(ticket.thing, fileName, built.sidecarPath, sidecar, ItemWriter.BOARD))
    }

    /**
     * The File Item of [fileName], a file this phone recorded as its own Thing, built from its verified
     * bytes [reader], [footer] and [template] with every cell at the policy's `publishedRes`. Only a
     * [DataSource.PhoneLog] ticket for [phone] whose footer names [phone] is cleared. A board kind, a
     * board footer and a gate without [phone] give [PublishCheck.NotThePhone].
     */
    fun phoneFile(
        ticket: PublishTicket,
        fileName: String,
        reader: ByteReader,
        footer: OpenSensorFooter?,
        template: BoardTemplate,
        h3: H3Grid = platformH3Grid(),
    ): PublishCheck {
        val own = phone?.uuid
        if (sourceOf(ticket) !is DataSource.PhoneLog || own == null || ticket.thing != own) return PublishCheck.NotThePhone
        if (footer != null && footer.thingId != own) return PublishCheck.NotThePhone
        precheck(ticket, footer)?.let { return it }
        return when (val result = FileItems.build(fileName, reader, footer, template, ticket.policy.publishedRes, h3)) {
            is FileItemResult.Item -> PublishCheck.Cleared(Publication(ticket.thing, fileName, result.sidecarPath, result.json, ItemWriter.PHONE))
            is FileItemResult.NoItem -> PublishCheck.NoItem(result)
        }
    }

    private fun sourceOf(ticket: PublishTicket): DataSource? = kinds[ticket.kind]?.source

    /** The refusal every path shares, consent withdrawn after the ticket or a footer of another Thing. */
    private fun precheck(ticket: PublishTicket, footer: OpenSensorFooter?): PublishCheck? = when {
        !consent.optedIn(ticket.thing) -> PublishCheck.OptedOut
        footer == null -> PublishCheck.NoItem(FileItemResult.NoThingId)
        footer.thingId != ticket.thing -> PublishCheck.OtherThing
        else -> null
    }
}
