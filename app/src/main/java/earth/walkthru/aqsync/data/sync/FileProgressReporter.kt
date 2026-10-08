package earth.walkthru.aqsync.data.sync

/** Bound UI/rate-meter work without delaying writes, final byte counts or recovery events. */
internal class FileProgressReporter(
    private val name: String,
    private val bytesTotal: Long,
    private val resumedFrom: Long,
    private val onProgress: (FileProgress) -> Unit,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var last: FileProgress? = null
    private var lastReportedAt = 0L

    fun report(bytesDone: Long, windowsRecovered: Int, force: Boolean = false) {
        val previous = last
        if (previous?.bytesDone == bytesDone && previous.windowsRecovered == windowsRecovered) return
        val now = monotonicMs()
        if (!force && previous != null && bytesDone < bytesTotal && previous.windowsRecovered == windowsRecovered && now - lastReportedAt < INTERVAL_MS) return
        val progress = FileProgress(name, bytesDone, bytesTotal, resumedFrom, windowsRecovered)
        last = progress
        lastReportedAt = now
        onProgress(progress)
    }

    private companion object {
        const val INTERVAL_MS = 100L
    }
}
