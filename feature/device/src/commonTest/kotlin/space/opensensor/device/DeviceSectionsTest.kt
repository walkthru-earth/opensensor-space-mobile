package space.opensensor.device

import space.opensensor.model.ConfigApplies
import space.opensensor.model.ConfigKeySpec
import space.opensensor.model.ConfigKeyType
import space.opensensor.model.ConfigValues
import space.opensensor.model.ManifestSection
import space.opensensor.model.SetupManifest
import space.opensensor.model.StepId
import space.opensensor.settings.DetailLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceSectionsTest {
    private val all = listOf("sensor", "wifi", "location", "site", "pairing", "lan", "bonds", "display")

    private fun manifest(ids: List<String>, keys: Map<String, ConfigKeySpec> = DISPLAY_KEYS) = SetupManifest(
        settings = ids.map { ManifestSection(StepId(it), keys = if (it == "display") listOf("panel.theme") else emptyList()) },
        configKeys = keys,
    )

    private fun sections(level: DetailLevel, attention: Boolean = false, identity: Boolean = true, ids: List<String> = all) =
        DeviceSection.of(manifest(ids), level, pairingNeedsAttention = attention, sensorIdentity = identity)

    @Test
    fun theCardsFollowTheTemplateOrder() {
        val developer = sections(DetailLevel.DEVELOPER)
        assertEquals(
            listOf(DeviceSection.Sensor, DeviceSection.Wifi, DeviceSection.Location, DeviceSection.Pairing, DeviceSection.Lan, DeviceSection.Bonds),
            developer.dropLast(1),
        )
        assertEquals(StepId("display"), (developer.last() as DeviceSection.Board).id)
        val reversed = sections(DetailLevel.DEVELOPER, ids = all.reversed())
        assertEquals(DeviceSection.Bonds, reversed[1])
    }

    @Test
    fun syncOverWifiIsDeveloperOnly() {
        assertTrue(DeviceSection.Lan !in sections(DetailLevel.SIMPLE))
        assertTrue(DeviceSection.Lan !in sections(DetailLevel.DETAILED))
        assertTrue(DeviceSection.Lan in sections(DetailLevel.DEVELOPER))
    }

    @Test
    fun pairingAndPairedPhonesShowFromDetailed() {
        val simple = sections(DetailLevel.SIMPLE)
        assertTrue(DeviceSection.Pairing !in simple && DeviceSection.Bonds !in simple && DeviceSection.Sensor !in simple)
        val detailed = sections(DetailLevel.DETAILED)
        assertTrue(DeviceSection.Pairing in detailed && DeviceSection.Bonds in detailed && DeviceSection.Sensor in detailed)
    }

    @Test
    fun pairingThatNeedsAttentionShowsAtSimple() {
        val simple = sections(DetailLevel.SIMPLE, attention = true)
        assertTrue(DeviceSection.Pairing in simple)
        assertTrue(DeviceSection.Bonds !in simple)
    }

    @Test
    fun theSensorCardNeedsSensorIdentity() {
        assertTrue(DeviceSection.Sensor !in sections(DetailLevel.DEVELOPER, identity = false))
    }

    @Test
    fun theSensorSectionAlsoShowsItsBoardKeys() {
        val manifest = SetupManifest(
            settings = listOf(ManifestSection(StepId("sensor"), keys = listOf("panel.theme"))),
            configKeys = DISPLAY_KEYS,
        )
        val detailed = DeviceSection.of(manifest, DetailLevel.DETAILED, pairingNeedsAttention = false, sensorIdentity = true)
        assertEquals(DeviceSection.Sensor, detailed.first())
        assertEquals(StepId("sensor"), (detailed[1] as DeviceSection.Board).id)
        val simple = DeviceSection.of(manifest, DetailLevel.SIMPLE, pairingNeedsAttention = false, sensorIdentity = false)
        assertEquals(listOf(StepId("sensor")), simple.map { (it as DeviceSection.Board).id })
    }

    @Test
    fun aRepeatedBoardSectionKeepsItsFirstPlace() {
        val list = sections(DetailLevel.SIMPLE, ids = listOf("display", "wifi", "display"))
        assertEquals(2, list.size)
        assertEquals(DeviceSection.Wifi, list[1])
    }

    @Test
    fun locationMobilityAndSiteShareOneCard() {
        val list = sections(DetailLevel.SIMPLE, ids = listOf("mobility", "wifi", "location", "site"))
        assertEquals(listOf(DeviceSection.Location, DeviceSection.Wifi), list)
    }

    @Test
    fun aBoardSectionWithoutAKeyTheAppMayShowIsLeftOut() {
        // A key without `applies` is never offered.
        val unusable = mapOf("panel.theme" to ConfigKeySpec("panel.theme", ConfigKeyType.ENUM, ConfigValues.Choices(listOf("light")), null, page = 7))
        assertEquals(emptyList(), DeviceSection.of(manifest(listOf("display", "name"), unusable), DetailLevel.DEVELOPER, false, true))
    }

    @Test
    fun aTemplateWithoutSettingsHasNoCards() {
        assertEquals(emptyList(), DeviceSection.of(SetupManifest.NONE, DetailLevel.DEVELOPER, true, true))
    }

    private companion object {
        val DISPLAY_KEYS = mapOf(
            "panel.theme" to ConfigKeySpec("panel.theme", ConfigKeyType.ENUM, ConfigValues.Choices(listOf("light", "dark")), ConfigApplies.IMMEDIATELY, page = 7),
        )
    }
}
