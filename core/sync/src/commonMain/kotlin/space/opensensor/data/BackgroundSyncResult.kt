package space.opensensor.data

import space.opensensor.data.ble.TransportKind

/**
 * Outcome of one background sync of one device, for the run's summary. Android fills it from
 * `DeviceFacade.syncInBackground`, iOS from the Swift adapters that drive
 * `SensorSync`. Moved here from `app/` for #23 so both platforms report the same shape.
 */
data class BackgroundSyncResult(
    val address: String,
    val deviceName: String?,
    val transport: TransportKind?,
    val newFiles: Int,
    val newBytes: Long,
    val failures: Int,
    /** Non-null when nothing was attempted (already connected elsewhere, connect failed, …). */
    val skipped: String? = null,
    /**
     * The transfer stopped before every planned file had an outcome (link lost, cancelled): a
     * transient condition worth a backed-off retry, unlike per-file failures which the next
     * period picks up anyway because those files are still missing from the archive.
     */
    val incomplete: Boolean = false,
) {
    val attempted: Boolean get() = skipped == null
}
