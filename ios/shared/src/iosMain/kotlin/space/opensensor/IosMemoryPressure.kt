package space.opensensor

import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidReceiveMemoryWarningNotification
import platform.darwin.NSObjectProtocol
import space.opensensor.storage.MemoryPressure
import kotlin.concurrent.AtomicReference

/**
 * Feeds [MemoryPressure] from `UIApplicationDidReceiveMemoryWarningNotification` (#61). iOS sends
 * no milder level, so every warning is Critical. The observer runs on the posting thread, the
 * signal never blocks. Installed once for the life of the process.
 */
internal object IosMemoryPressure {
    private val observer = AtomicReference<NSObjectProtocol?>(null)

    fun install() {
        if (observer.value != null) return
        val added = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidReceiveMemoryWarningNotification,
            `object` = null,
            queue = null as NSOperationQueue?,
        ) { _ -> MemoryPressure.signal(MemoryPressure.Level.Critical) }
        // Two racing installs keep one observer.
        if (!observer.compareAndSet(null, added)) NSNotificationCenter.defaultCenter.removeObserver(added)
    }
}
