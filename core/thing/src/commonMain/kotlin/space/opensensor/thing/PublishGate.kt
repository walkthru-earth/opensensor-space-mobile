package space.opensensor.thing

import space.opensensor.data.history.OpenSensorFooter
import space.opensensor.data.history.parquet.ByteReader
import space.opensensor.model.BoardTemplate
import space.opensensor.publicfeed.H3Grid
import space.opensensor.publicfeed.platformH3Grid
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

/** Leave to build the public STAC Item of one Thing's files. Only [PublishGate.ticket] issues one. */
class PublishTicket internal constructor(
    val kind: KindId,
    val thing: String,
    val policy: PublishPolicy.OptInCcBy,
)

/**
 * The one caller of [FileItems]. It issues a [PublishTicket] only for a Thing whose kind is a
 * [DataSource.Recording] source with [PublishPolicy.OptInCcBy] and whose owner opted it in
 * ([consent]). A [DataSource.PrivateRead] or [DataSource.RemoteFeed] kind, a [PublishPolicy.Never]
 * kind, an unknown kind and a Thing not opted in get none. It builds Items only, it never uploads.
 */
class PublishGate(
    private val kinds: ThingKinds,
    private val consent: UploadConsent = UploadConsent.NONE,
) {
    /** A ticket for [thing] of [kind], or null when its data may not be published. */
    fun ticket(kind: KindId, thing: String): PublishTicket? {
        val source = kinds[kind]?.source as? DataSource.Recording ?: return null
        val policy = source.publish as? PublishPolicy.OptInCcBy ?: return null
        if (!consent.optedIn(thing)) return null
        return PublishTicket(kind, thing, policy)
    }

    /**
     * The Item of one of the ticket's Thing's files ([FileItems.build]), with no cell finer than the
     * policy's `maxRes`. A file whose footer names another Thing gets [FileItemResult.NoThingId].
     */
    fun item(
        ticket: PublishTicket,
        fileName: String,
        reader: ByteReader,
        footer: OpenSensorFooter?,
        template: BoardTemplate,
        h3: H3Grid = platformH3Grid(),
        dataBaseUrl: String = FileItems.DATA_BASE_URL,
    ): FileItemResult {
        if (footer?.thingId != ticket.thing) return FileItemResult.NoThingId
        return FileItems.build(fileName, reader, footer, template, h3, dataBaseUrl, maxRes = ticket.policy.maxRes)
    }
}
