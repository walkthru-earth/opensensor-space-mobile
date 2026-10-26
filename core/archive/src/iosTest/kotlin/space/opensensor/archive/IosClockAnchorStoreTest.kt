@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package space.opensensor.archive

import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest

class IosClockAnchorStoreTest : ClockAnchorStoreContract() {
    private val root = NSTemporaryDirectory().trimEnd('/') + "/clock-test-" + NSUUID().UUIDString()
    override fun newStore(): ClockAnchorStore = IosClockAnchorStore(root)

    override fun writeMalformedReceipt() {
        IosFiles.writeAtomically("$root/${clockTestAnchor.thing}/${clockTestAnchor.boot}/broken.anchor", "interrupted".encodeToByteArray())
        val moved = clockTestAnchor.copy(thing = "thing-b")
        IosFiles.writeAtomically("$root/${clockTestAnchor.thing}/${clockTestAnchor.boot}/misplaced.anchor", ClockAnchorReceipt.encode(moved))
    }

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }
}
