package earth.walkthru.aqsync.storage

/**
 * What losing a file costs, which decides how it is written and whether anything may sweep it.
 *
 * - [Durable] cannot be rebuilt on the phone: verified archive copies, the archive index and clock
 *   anchors. Written with a full flush (`F_FULLFSYNC` on iOS) and a parent directory sync. Nothing
 *   automatic ever deletes it.
 * - [Rebuildable] is derived from Durable data (`derived/v1` day files) and is rebuilt when missing.
 * - [Scratch] is work in progress: partial downloads, temp files and export runs. The startup janitor
 *   sweeps what is stale.
 * - [Settings] is small preference state (DataStore, user defaults).
 */
enum class StorageClass { Durable, Rebuildable, Scratch, Settings }

/** One directory the app writes, and its [StorageClass]. A role maps to exactly one directory per platform. */
enum class StorageRole(val storageClass: StorageClass) {
    /** Verified archive copies, one directory per station. */
    Archive(StorageClass.Durable),

    /** The archive index (iOS receipts sit beside the finals, Android keeps it in Room). */
    Index(StorageClass.Durable),

    /** Clock anchors, the only record of a boot's UTC offset once the device rotated the file. */
    ClockAnchors(StorageClass.Durable),

    /** `derived/v1` day files, in the OS cache directory so the OS may purge them (#61). */
    Derived(StorageClass.Rebuildable),

    /** Where builds before #61 kept `derived/v1`, in app files. The startup janitor removes it whole. */
    LegacyDerived(StorageClass.Rebuildable),

    /** `archive-partial/`, resumable downloads and their metas. */
    Partials(StorageClass.Scratch),

    /** Temp files of [AtomicFiles], on the same volume as every other role so a rename never copies. */
    Temp(StorageClass.Scratch),

    /** Export runs for the share sheet. */
    Exports(StorageClass.Scratch),

    /** DataStore and other preference files. */
    Settings(StorageClass.Settings),
}
