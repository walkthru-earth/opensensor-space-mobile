package space.opensensor.ui.permissions

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import space.opensensor.data.di.ThingModule
import space.opensensor.thing.PlatformNeedsTable
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The merged manifest, with every library manifest folded in, declares exactly what the app shell
 * and the enabled Thing kinds ask for in [PlatformNeedsTable], no more and no less. Location is
 * approximate only (Discover "Near me" and Use phone location), never fine or background, and
 * Bluetooth scanning stays `neverForLocation` (docs/store-compliance.md). A new permission or service
 * type lands in the table with its kind. app/build.gradle.kts hands the merged file over as
 * `opensensor.mergedManifest`.
 */
class ManifestPermissionsTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private val needs = PlatformNeedsTable.of(ThingModule.KINDS).android
    private val manifest = run {
        val path = requireNotNull(System.getProperty("opensensor.mergedManifest")) { "opensensor.mergedManifest is not set" }
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(File(path)).documentElement
    }

    /**
     * androidx.core declares this signature permission under the application id so the app's own
     * not exported dynamic receivers can be protected. It grants nothing to another app.
     */
    private val dynamicReceiver = "${manifest.getAttribute("package")}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

    private fun elements(tag: String): List<Element> {
        val nodes = manifest.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun permissions(): Map<String, Element> = elements("uses-permission").associateBy { it.getAttributeNS(android, "name") }

    @Test
    fun declaresExactlyWhatTheEnabledKindsAskFor() {
        assertEquals(needs.permissions + dynamicReceiver, permissions().keys)
    }

    @Test
    fun theOnlyDeclaredPermissionIsTheSignatureOneForDynamicReceivers() {
        val declared = elements("permission")
        assertEquals(listOf(dynamicReceiver), declared.map { it.getAttributeNS(android, "name") })
        assertEquals("signature", declared.single().getAttributeNS(android, "protectionLevel"))
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
