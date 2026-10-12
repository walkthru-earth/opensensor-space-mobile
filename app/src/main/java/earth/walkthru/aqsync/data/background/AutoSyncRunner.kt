package earth.walkthru.aqsync.data.background

import earth.walkthru.aqsync.data.lan.LanDevice
import earth.walkthru.aqsync.data.local.database.KnownDevice

/*
 * Android names for the shared background sync in `:core:sync` (BackgroundSync.kt). The policy,
 * the runner and the report moved there for #23 so iOS runs the same logic. These aliases keep the
 * worker, the gateway and the tests on their old names. Pattern matching on a link uses the shared
 * `BackgroundSyncLink.Lan` and `BackgroundSyncLink.Ble`.
 */

/** One way the worker may reach a logger: LAN to a resolved [LanDevice], or the Bluetooth bond. */
typealias AutoSyncLink = BackgroundSyncLink<LanDevice>

/** Room, mDNS, the Bluetooth adapter and the repository, behind the shared gateway port. */
typealias AutoSyncGateway = BackgroundSyncGateway<KnownDevice, LanDevice>

/** One run over the auto-sync devices. */
typealias AutoSyncReport = BackgroundSyncReport<KnownDevice>

/** The shared runner over Android's known devices and mDNS results. [SyncWorker] wraps it in WorkManager plumbing. */
typealias AutoSyncRunner = BackgroundSyncRunner<KnownDevice, LanDevice>
