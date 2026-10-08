package earth.walkthru.aqsync.ui.permissions

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The manifest declares only what the app uses. Location is approximate only (Discover "Near me"),
 * never fine or background, and Bluetooth scanning stays `neverForLocation`
 * (docs/store-compliance.md). A new permission must be added here on purpose.
 */
class ManifestPermissionsTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private fun permissions(): Map<String, Element> {
        val file = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.isFile }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("uses-permission")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associateBy { it.getAttributeNS(android, "name") }
    }

    @Test
    fun declaresExactlyTheReviewedSet() {
        assertEquals(
            setOf(
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.BLUETOOTH_CONNECT",
                "android.permission.INTERNET",
                "android.permission.ACCESS_LOCAL_NETWORK",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.WAKE_LOCK",
                "android.permission.REQUEST_COMPANION_RUN_IN_BACKGROUND",
                "android.permission.REQUEST_COMPANION_USE_DATA_IN_BACKGROUND",
                "android.permission.REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND",
                "android.permission.REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE",
                "android.permission.RECEIVE_BOOT_COMPLETED",
                "android.permission.ACCESS_COARSE_LOCATION",
            ),
            permissions().keys,
        )
    }

    @Test
    fun coarseLocationOnlyAndScanIsNeverForLocation() {
        val perms = permissions()
        assertEquals(setOf("android.permission.ACCESS_COARSE_LOCATION"), perms.keys.filter { "LOCATION" in it }.toSet())
        assertEquals("neverForLocation", perms.getValue("android.permission.BLUETOOTH_SCAN").getAttributeNS(android, "usesPermissionFlags"))
    }
}
