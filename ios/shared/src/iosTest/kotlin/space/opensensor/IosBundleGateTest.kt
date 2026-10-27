package space.opensensor

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.Foundation.NSDictionary
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dictionaryWithContentsOfFile
import platform.Foundation.stringWithContentsOfFile
import platform.posix.getenv
import space.opensensor.thing.IosNeeds
import space.opensensor.thing.PlatformNeedsTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The iOS twin of Android's `ManifestPermissionsTest`. `Info.plist`, `PrivacyInfo.xcprivacy` and the
 * entitlements declare exactly what the app shell and the kinds in [IOS_THING_KINDS] ask for in
 * [PlatformNeedsTable], no more and no less. Tracking stays off whatever a kind declares
 * (docs/store-compliance.md). The simulator reads the files from the checkout that
 * `OPENSENSOR_IOS_DIR` names (ios/shared/build.gradle.kts).
 */
@OptIn(ExperimentalForeignApi::class)
class IosBundleGateTest {
    private val needs = PlatformNeedsTable.of(IOS_THING_KINDS).ios
    private val iosDir = requireNotNull(getenv("OPENSENSOR_IOS_DIR")?.toKString()) { "OPENSENSOR_IOS_DIR is not set" }

    private fun plist(path: String): Map<Any?, *> = requireNotNull(NSDictionary.dictionaryWithContentsOfFile("$iosDir/$path")) { "no plist at $path" }

    private fun strings(value: Any?): Set<String> = (value as? List<*>).orEmpty().map { it as String }.toSet()

    @Test
    fun infoPlistDeclaresExactlyTheNeededKeys() {
        val info = plist("App/Info.plist")
        assertEquals(needs.infoKeys, info.keys.map { it as String }.filter(IosNeeds::isGatedInfoKey).toSet())
        assertEquals(needs.bonjourServices, strings(info["NSBonjourServices"]))
        assertEquals(needs.backgroundModes, strings(info["UIBackgroundModes"]))
        assertEquals(needs.backgroundTaskIds, strings(info["BGTaskSchedulerPermittedIdentifiers"]))
    }

    @Test
    fun privacyManifestDeclaresExactlyTheNeededApisAndNoTracking() {
        val privacy = plist("App/PrivacyInfo.xcprivacy")
        assertEquals(false, privacy["NSPrivacyTracking"])
        assertEquals(emptySet(), strings(privacy["NSPrivacyTrackingDomains"]))
        val apis = (privacy["NSPrivacyAccessedAPITypes"] as List<*>).map { it as Map<*, *> }
            .associate { it["NSPrivacyAccessedAPIType"] as String to strings(it["NSPrivacyAccessedAPITypeReasons"]) }
        assertEquals(needs.accessedApis, apis)
        val collected = (privacy["NSPrivacyCollectedDataTypes"] as List<*>).map { (it as Map<*, *>)["NSPrivacyCollectedDataType"] as String }.toSet()
        assertEquals(needs.collectedDataTypes, collected)
    }

    @Test
    fun entitlementsDeclareExactlyTheNeededKeys() {
        val project = NSString.stringWithContentsOfFile("$iosDir/OpenSensor.xcodeproj/project.pbxproj", NSUTF8StringEncoding, null)
        assertTrue(project != null, "no project.pbxproj under $iosDir")
        val files = Regex("""CODE_SIGN_ENTITLEMENTS = "?([^";]+)"?;""").findAll(project).map { it.groupValues[1] }.toSet()
        val loose = NSFileManager.defaultManager.contentsOfDirectoryAtPath("$iosDir/App", null).orEmpty().map { it as String }.filter { it.endsWith(".entitlements") }
        assertTrue(loose.all { "App/$it" in files }, "entitlements files not set as CODE_SIGN_ENTITLEMENTS: $loose")
        val keys = files.flatMap { plist(it).keys.map { key -> key as String } }.toSet()
        assertEquals(needs.entitlements, keys)
    }
}
