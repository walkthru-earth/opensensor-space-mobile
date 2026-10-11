package earth.walkthru.aqsync.collection

import earth.walkthru.aqsync.data.ble.TransportKind
import earth.walkthru.aqsync.data.sync.CollectionProgress
import earth.walkthru.aqsync.data.sync.CollectionSummary
import earth.walkthru.aqsync.link.DisconnectCause

/**
 * One device's collection as a single state machine (#56). Every state but [Idle] names its
 * request, and the non-terminal ones hold the PROCESS lease. Outcomes live here, never only in an
 * event, so a screen that missed a toast still reads how the last request ended.
 */
sealed interface CollectionState {
    /** The request this state belongs to, null for [Idle]. */
    val intentId: Long?

    /** True for the states that keep work running and leases held. */
    val active: Boolean get() = false

    data object Idle : CollectionState {
        override val intentId: Long? get() = null
    }

    /** Connecting and planning. [attempt] counts from 1 and grows with every retry. */
    data class Preparing(val intent: CollectionIntent, val step: PrepareStep, val attempt: Int) : CollectionState {
        override val intentId: Long get() = intent.id
        override val active: Boolean get() = true
    }

    /** Files are coming over [link]. */
    data class Transferring(val intent: CollectionIntent, val progress: CollectionProgress, val link: TransportKind) : CollectionState {
        override val intentId: Long get() = intent.id
        override val active: Boolean get() = true
    }

    /** Every planned file has an outcome. */
    data class Finalizing(val intent: CollectionIntent, val step: FinalizeStep) : CollectionState {
        override val intentId: Long get() = intent.id
        override val active: Boolean get() = true
    }

    /**
     * The link was lost for a [lost] cause that reconnecting can fix. [attempt] runs at
     * [retryAtEpochMs] and starts again from a fresh `LIST`.
     */
    data class Waiting(val intent: CollectionIntent, val lost: DisconnectCause, val attempt: Int, val retryAtEpochMs: Long) : CollectionState {
        override val intentId: Long get() = intent.id
        override val active: Boolean get() = true
    }

    data class Done(override val intentId: Long, val summary: CollectionSummary) : CollectionState

    data class Failed(override val intentId: Long, val failure: CollectionFailure) : CollectionState

    data class Stopped(override val intentId: Long, val by: StopSource) : CollectionState
}

enum class PrepareStep { CONNECT, CLOCK, LIST, PLAN }

/** One step today. An enum so a later step (a confirming `LIST`) keeps the state shape. */
enum class FinalizeStep { SUMMARY }

/** Why a collection ended without finishing. */
sealed interface CollectionFailure {
    /** The link failed for [cause], which retrying cannot fix or which kept coming back. */
    data class Link(val cause: DisconnectCause) : CollectionFailure

    /** This phone could not hold the files. Bytes already saved stay. */
    data class Storage(val kind: StorageFailure) : CollectionFailure

    /** The device answered [op] with `ERROR` [code] for the whole session, not one file. */
    data class Device(val code: Int, val op: Int) : CollectionFailure

    /** The OS would not keep the app or the radio awake for the collection. */
    data class KeepAliveRefused(val kind: KeepAliveKind) : CollectionFailure

    /** A fault no other case covers. [type] is the class name, never a message with names in it. */
    data class Unexpected(val type: String) : CollectionFailure
}

enum class StorageFailure {
    /** The planned bytes and the headroom do not fit. */
    NO_SPACE,

    /** The archive or its index could not be read or written. */
    IO,
}
