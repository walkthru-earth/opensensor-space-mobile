package space.opensensor.collection

/**
 * What keeps a collection alive while the app is in the background. Android maps PROCESS to the
 * connected device foreground service and WIFI to a Wi-Fi lock (#57). iOS maps PROCESS to a
 * background task and has no WIFI lock (#58).
 */
enum class KeepAliveKind { PROCESS, WIFI }

/** Hands out leases. Platform adapters live in the app modules. */
fun interface KeepAlive {
    /**
     * Take a lease of [kind] for [intent], or null when the OS refuses it. Never suspends, so a
     * tap can call it before its first suspension point.
     */
    fun acquire(kind: KeepAliveKind, intent: CollectionIntent): Lease?

    companion object {
        /** Grants every lease and holds nothing, for hosts with no background limits. */
        val None: KeepAlive = KeepAlive { _, _ -> Lease {} }
    }
}

/** One held keep alive. [close] is idempotent and never throws past the adapter. */
fun interface Lease {
    fun close()
}
