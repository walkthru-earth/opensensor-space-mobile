package space.opensensor.shell

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every key saves and restores through [AppNavConfig], the module both platforms' back stacks use. */
class AppNavConfigTest {
    private val json = Json { serializersModule = AppNavConfig.serializersModule }
    private val serializer = PolymorphicSerializer(NavKey::class)

    private val everyKey: List<AppKey> = listOf(
        DiscoverKey,
        ScanKey,
        SettingsKey,
        TodayKey("AA:BB"),
        HistoryKey("AA:BB"),
        FilesKey("AA:BB"),
        DeviceKey("AA:BB"),
        DebugKey("AA:BB"),
        FileMetricsKey("station", "2026-10-09.parquet"),
    )

    @Test
    fun `every app key round trips through the saved state module`() {
        for (key in everyKey) {
            val text = json.encodeToString(serializer, key)
            assertEquals(key, json.decodeFromString(serializer, text), text)
        }
    }

    @Test
    fun `the list covers every key in the sealed hierarchy`() {
        assertEquals(sealedSubclassNames(), everyKey.map { it::class.simpleName }.toSet())
    }

    @Test
    fun `every tab starts at its own root`() {
        assertEquals(listOf(DiscoverKey, ScanKey, SettingsKey), TopTab.entries.map { it.root })
    }
}

/** The sealed subclasses by name, read from the serializer so the test needs no reflection on iOS. */
private fun sealedSubclassNames(): Set<String?> {
    // A sealed descriptor has two elements, "type" and "value". The second lists the subclasses.
    val value = AppKey.serializer().descriptor.getElementDescriptor(1)
    return (0 until value.elementsCount).map { value.getElementName(it).substringAfterLast('.') }.toSet()
}
