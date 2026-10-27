package space.opensensor.ui.permissions

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import space.opensensor.data.di.ThingModule
import space.opensensor.thing.PlatformNeedsTable
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The manifest declares exactly what the app shell and the enabled Thing kinds ask for in
 * [PlatformNeedsTable], no more and no less. Location is approximate only (Discover "Near me" and
 * Use phone location), never fine or background, and Bluetooth scanning stays `neverForLocation`
 * (docs/store-compliance.md). A new permission or service type lands in the table with its kind.
 */
class ManifestPermissionsTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private val needs = PlatformNeedsTable.of(ThingModule.KINDS).android

    private fun elements(tag: String): List<Element> {
        val file = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.isFile }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val nodes = factory.newDocumentBuilder().parse(file).getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun permissions(): Map<String, Element> = elements("uses-permission").associateBy { it.getAttributeNS(android, "name") }

    @Test
    fun declaresExactlyWhatTheEnabledKindsAskFor() {
        assertEquals(needs.permissions, permissions().keys)
    }

    @Test
    fun servicesUseExactlyTheDeclaredForegroundTypes() {
        val types = elements("service").flatMap { it.getAttributeNS(android, "foregroundServiceType").split('|') }.filter { it.isNotBlank() }.toSet()
        assertEquals(needs.foregroundServiceTypes, types)
    }

    @Test
    fun coarseLocationOnlyAndScanIsNeverForLocation() {
        val perms = permissions()
        assertEquals(setOf("android.permission.ACCESS_COARSE_LOCATION"), perms.keys.filter { "LOCATION" in it }.toSet())
        assertEquals("neverForLocation", perms.getValue("android.permission.BLUETOOTH_SCAN").getAttributeNS(android, "usesPermissionFlags"))
    }
}
