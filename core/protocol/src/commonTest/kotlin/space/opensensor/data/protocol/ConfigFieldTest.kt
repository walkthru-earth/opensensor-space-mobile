package space.opensensor.data.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The app allowlist of SET_CONFIG keys and its floor rules (#225). */
class ConfigFieldTest {
    /** Every builder method once, so the table and the builder cannot drift apart. */
    private val everyBuilderKey: Set<String> = buildSet {
        addAll(ConfigPatch.build { blePair(PairMode.FIXED).blePin("000000").clearBonds() }.keys)
        addAll(ConfigPatch.build { wifiOn(true).wifiSsid("home").wifiPsk("").lanOn(true).rotateToken() }.keys)
        addAll(ConfigPatch.build { location("", 8, "") }.keys)
        addAll(ConfigPatch.build { sensorIdentity("Acme", "X1", "X1-0042", batchCandidate = false) }.keys)
    }

    @Test
    fun `the table mirrors the builder keys`() {
        assertEquals(everyBuilderKey, ConfigFields.ALL.map { it.key }.toSet())
        assertEquals(ConfigFields.ALL.size, ConfigFields.ALL.map { it.key }.toSet().size)
    }

    @Test
    fun `secret keys come from the table`() {
        assertEquals(setOf("wifi.psk", "ble.pin"), ConfigFields.SECRET_KEYS)
        assertEquals(ConfigFields.SECRET_KEYS, ConfigPatch.SECRET_KEYS)
        assertTrue(ConfigPatch.build { wifiPsk("x") }.carriesSecret)
        assertFalse(ConfigPatch.build { wifiSsid("home") }.carriesSecret)
    }

    @Test
    fun `confirmations follow the table`() {
        assertTrue(ConfigFields.of("ble.pair")!!.needsConfirm("none"))
        assertFalse(ConfigFields.of("ble.pair")!!.needsConfirm("random"))
        assertTrue(ConfigFields.of("ble.clear_bonds")!!.needsConfirm("1"))
        assertTrue(ConfigFields.of("lan.rotate_token")!!.needsConfirm("1"))
        assertFalse(ConfigFields.of("wifi.on")!!.needsConfirm("0"))
    }

    @Test
    fun `app floor rules win over a schema`() {
        val lax = listOf(
            ConfigField("wifi.psk", FieldType.Text(0, 255), Applies.NEXT_BOOT, secret = false),
            ConfigField("lan.rotate_token", FieldType.Toggle, Applies.IMMEDIATELY, confirm = Confirm.Never, disrupts = false),
            ConfigField("ble.pair", FieldType.Choice(listOf("random", "none", "open")), Applies.IMMEDIATELY),
        )
        val merged = ConfigFields.merge(lax).associateBy { it.key }
        assertEquals(ConfigFields.of("wifi.psk"), merged["wifi.psk"])
        assertEquals(ConfigFields.of("lan.rotate_token"), merged["lan.rotate_token"])
        assertEquals(ConfigFields.of("ble.pair"), merged["ble.pair"])
        assertEquals(ConfigFields.SECRET_KEYS, ConfigFields.secretKeys(lax))
    }

    @Test
    fun `a schema can only make a key stricter`() {
        val strict = listOf(
            ConfigField("wifi.ssid", FieldType.Text(1, 32), Applies.IMMEDIATELY, secret = true),
            ConfigField("lan.on", FieldType.Toggle, Applies.IMMEDIATELY, confirm = Confirm.When("0")),
            ConfigField("ble.pair", FieldType.Choice(listOf("random")), Applies.NEXT_BOOT, confirm = Confirm.When("fixed")),
        )
        val merged = ConfigFields.merge(strict).associateBy { it.key }
        assertTrue(merged.getValue("wifi.ssid").secret)
        assertTrue(merged.getValue("lan.on").needsConfirm("0"))
        assertFalse(merged.getValue("lan.on").needsConfirm("1"))
        assertEquals(Confirm.Always, merged.getValue("ble.pair").confirm)
        assertTrue("wifi.ssid" in ConfigFields.secretKeys(strict))
    }

    @Test
    fun `an unknown schema key fails closed`() {
        val unknown = listOf(
            ConfigField("location.phone_cell", FieldType.H3Cell, Applies.NEXT_SAMPLE),
            ConfigField("ota.url", FieldType.Text(1, 200), Applies.IMMEDIATELY),
        )
        assertTrue(ConfigFields.merge(unknown).isEmpty())
    }
}
