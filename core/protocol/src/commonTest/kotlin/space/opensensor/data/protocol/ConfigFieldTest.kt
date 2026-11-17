package space.opensensor.data.protocol

import space.opensensor.model.SiteField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The app allowlist of SET_CONFIG keys and its floor rules (#225). */
class ConfigFieldTest {
    /** Every builder method once, so the table and the builder cannot drift apart. */
    private val everyBuilderKey: Set<String> = buildSet {
        addAll(ConfigPatch.build { blePair(PairMode.FIXED).blePin("000000").clearBonds() }.keys)
        addAll(ConfigPatch.build { wifiOn(true).wifiSsid("home").wifiPsk("").lanOn(true).rotateToken() }.keys)
        addAll(ConfigPatch.build { location("", "").thingMobility(ThingMobility.FIXED) }.keys)
        addAll(ConfigPatch.build { sensorIdentity("Acme", "X1", "X1-0042", batchCandidate = false) }.keys)
        addAll(ConfigPatch.build { site(SiteField.entries.associateWith { "" }) }.keys)
        addAll(ConfigPatch.build { setupAck(version = 7, ack = 0) }.keys)
    }

    @Test
    fun `the table mirrors the builder keys`() {
        assertEquals(everyBuilderKey, ConfigFields.ALL.map { it.key }.toSet())
        assertEquals(ConfigFields.ALL.size, ConfigFields.ALL.map { it.key }.toSet().size)
    }

    @Test
    fun `every table key names the card it is set from`() {
        val expected = mapOf(
            "ble.pair" to ConfigGroup.PAIRING,
            "ble.pin" to ConfigGroup.PAIRING,
            "ble.clear_bonds" to ConfigGroup.PAIRING,
            "wifi.on" to ConfigGroup.WIFI,
            "wifi.ssid" to ConfigGroup.WIFI,
            "wifi.psk" to ConfigGroup.WIFI,
            "lan.on" to ConfigGroup.LAN,
            "lan.rotate_token" to ConfigGroup.LAN,
            "location.cell" to ConfigGroup.LOCATION,
            "location.resolution" to ConfigGroup.LOCATION,
            "location.country" to ConfigGroup.LOCATION,
            "thing.mobility" to ConfigGroup.LOCATION,
            "sensor.vendor" to ConfigGroup.SENSOR,
            "sensor.model" to ConfigGroup.SENSOR,
            "sensor.serial" to ConfigGroup.SENSOR,
            "sensor.batch_candidate" to ConfigGroup.SENSOR,
            "owner.title" to ConfigGroup.SITE,
            "owner.provider" to ConfigGroup.SITE,
            "owner.url" to ConfigGroup.SITE,
            "owner.description" to ConfigGroup.SITE,
            "thing.deployment_id" to ConfigGroup.SITE,
            "thing.setup_ack" to ConfigGroup.OTHER,
            "thing.setup_v" to ConfigGroup.OTHER,
        )
        assertEquals(expected.keys, ConfigFields.ALL.map { it.key }.toSet())
        expected.forEach { (key, group) -> assertEquals(group, ConfigFields.groupOf(key), key) }
    }

    @Test
    fun `a key the app does not know is other`() {
        assertEquals(ConfigGroup.LOCATION, ConfigFields.groupOf("thing.mobility"))
        assertEquals(ConfigGroup.OTHER, ConfigFields.groupOf("owner.other"))
        assertEquals(ConfigGroup.OTHER, ConfigFields.groupOf("debug.level"))
        assertEquals(ConfigGroup.OTHER, ConfigFields.groupOf("wifi"))
        assertEquals(ConfigGroup.OTHER, ConfigFields.groupOf(""))
    }

    @Test
    fun `secret keys come from the table`() {
        assertEquals(setOf("wifi.psk", "ble.pin"), ConfigFields.SECRET_KEYS)
        assertEquals(ConfigFields.SECRET_KEYS, ConfigPatch.SECRET_KEYS)
        assertTrue(ConfigPatch.build { wifiPsk("aaaaaaaa") }.carriesSecret)
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
            ConfigField("lan.rotate_token", FieldType.Toggle, Applies.IMMEDIATELY, confirm = Confirm.Never, disrupts = false),
            ConfigField("ble.pair", FieldType.Choice(listOf("random", "none", "open")), Applies.IMMEDIATELY),
        )
        val merged = ConfigFields.merge(lax).associateBy { it.key }
        assertEquals(ConfigFields.of("lan.rotate_token"), merged["lan.rotate_token"])
        assertEquals(ConfigFields.of("ble.pair"), merged["ble.pair"])
    }

    @Test
    fun `a schema can only make an app key stricter`() {
        val strict = listOf(
            ConfigField("lan.on", FieldType.Toggle, Applies.IMMEDIATELY, confirm = Confirm.When("0")),
            ConfigField("ble.pair", FieldType.Choice(listOf("random")), Applies.NEXT_BOOT, confirm = Confirm.When("fixed")),
            ConfigField("wifi.on", FieldType.Toggle, Applies.IMMEDIATELY, disrupts = true),
        )
        val merged = ConfigFields.merge(strict).associateBy { it.key }
        assertTrue(merged.getValue("lan.on").needsConfirm("0"))
        assertFalse(merged.getValue("lan.on").needsConfirm("1"))
        assertEquals(Confirm.Always, merged.getValue("ble.pair").confirm)
        assertTrue(merged.getValue("wifi.on").disrupts)
    }

    @Test
    fun `a secret is never declarable`() {
        val secrets = listOf(
            ConfigField("wifi.psk", FieldType.Text(0, 255), Applies.NEXT_BOOT, secret = false),
            ConfigField("ble.pin", FieldType.Digits(6), Applies.NEXT_BOOT, secret = true),
            ConfigField("wifi.ssid", FieldType.Text(1, 32), Applies.IMMEDIATELY, secret = true),
            ConfigField("panel.code", FieldType.Text(0, 8), Applies.IMMEDIATELY, secret = true, page = BOARD),
        )
        assertTrue(ConfigFields.merge(secrets).isEmpty())
        assertEquals(setOf("wifi.psk", "ble.pin"), ConfigFields.SECRET_KEYS)
    }

    @Test
    fun `a board key under a reserved prefix is refused`() {
        val reserved = ConfigFields.RESERVED_PREFIXES.map { prefix ->
            ConfigField("${prefix}extra", FieldType.Toggle, Applies.IMMEDIATELY, page = BOARD)
        } + ConfigField("location.phone_cell", FieldType.H3Cell, Applies.NEXT_SAMPLE, page = BOARD)
        assertEquals(7, ConfigFields.RESERVED_PREFIXES.size)
        assertTrue(ConfigFields.merge(reserved).isEmpty())
        assertTrue(ConfigFields.merge(listOfNotNull(ConfigFields.declared("wifi.extra", "boolean", emptyList(), "immediately", BOARD))).isEmpty())
    }

    @Test
    fun `a board key outside the reserved prefixes is accepted`() {
        val theme = ConfigFields.declared("panel.theme", "enum", listOf("light", "dark"), "immediately", BOARD)!!
        val refresh = ConfigFields.declared("panel.refresh_s", "integer", listOf("60", "3600"), "next sample", BOARD)!!
        val asc = ConfigFields.declared("sen6x.co2_asc", "boolean", emptyList(), "next boot", BOARD)!!
        val name = ConfigFields.declared("panel.label", "text", emptyList(), "immediately", BOARD)!!
        val merged = ConfigFields.merge(listOf(theme, refresh, asc, name))
        assertEquals(listOf(theme, refresh, asc, name), merged)
        assertEquals(FieldType.Choice(listOf("light", "dark")), theme.type)
        assertEquals(FieldType.IntBetween(60, 3600), refresh.type)
        assertEquals(Applies.NEXT_SAMPLE, refresh.applies)
        assertEquals(FieldType.Toggle, asc.type)
        assertEquals(FieldType.Text(0, ConfigFields.DECLARED_TEXT_MAX_BYTES), name.type)
        assertTrue(merged.none { it.secret })
        assertEquals(ConfigGroup.OTHER, ConfigFields.groupOf("panel.theme"))
    }

    @Test
    fun `a board action always confirms and disrupts`() {
        val clean = ConfigFields.declared("sen6x.fan_clean", "boolean", emptyList(), "action", BOARD)!!
        assertEquals(FieldType.Action, clean.type)
        val merged = ConfigFields.merge(listOf(clean.copy(confirm = Confirm.Never, disrupts = false))).single()
        assertEquals(Confirm.Always, merged.confirm)
        assertTrue(merged.disrupts)
        assertEquals(Applies.ONCE, merged.applies)
        assertTrue(merged.needsConfirm("1"))
    }

    @Test
    fun `a type that does not parse is dropped`() {
        assertNull(ConfigFields.declared("sen6x.temp_offset", "decimal", listOf("-10", "10"), "immediately", BOARD))
        assertNull(ConfigFields.declared("panel.theme", "enum", listOf("light", "dark"), "soon", BOARD))
        assertNull(ConfigFields.declared("panel.start_page", "integer", emptyList(), "immediately", BOARD))
        assertNull(ConfigFields.declared("panel.start_page", "integer", listOf("5", "1"), "immediately", BOARD))
        assertNull(ConfigFields.declared("panel.start_page", "integer", listOf("1", "x"), "immediately", BOARD))
        assertNull(ConfigFields.declared("panel.theme", "enum", emptyList(), "immediately", BOARD))
        assertNull(ConfigFields.declared("panel.theme", "enum", listOf("a", "a"), "immediately", BOARD))
        assertNull(ConfigFields.declared("panel.theme", "enum", listOf("a\nb"), "immediately", BOARD))
        assertNull(ConfigFields.declared("panel.reset", "integer", listOf("0", "1"), "action", BOARD))
        assertNull(ConfigFields.declared("panel.label", "text", listOf("0", "200"), "immediately", BOARD))
    }

    @Test
    fun `a board key must read back from the board settings page`() {
        val offPage = listOf(
            ConfigField("panel.theme", FieldType.Choice(listOf("light")), Applies.IMMEDIATELY, page = 1),
            ConfigField("panel.sleep_s", FieldType.IntBetween(0, 10), Applies.IMMEDIATELY),
        )
        assertTrue(ConfigFields.merge(offPage).isEmpty())
    }

    @Test
    fun `a board key name must be dotted lowercase`() {
        val names = listOf("Panel.theme", "panel", "panel.", ".theme", "panel theme.x", "panel.t=x", "a." + "b".repeat(60))
        val fields = names.map { ConfigField(it, FieldType.Toggle, Applies.IMMEDIATELY, page = BOARD) }
        assertTrue(ConfigFields.merge(fields).isEmpty())
    }

    @Test
    fun `an app only type is never a board type`() {
        val fields = listOf(
            ConfigField("panel.pin", FieldType.Digits(4), Applies.IMMEDIATELY, page = BOARD),
            ConfigField("panel.cell", FieldType.H3Cell, Applies.IMMEDIATELY, page = BOARD),
            ConfigField("panel.country", FieldType.Country, Applies.IMMEDIATELY, page = BOARD),
            ConfigField("panel.once", FieldType.Toggle, Applies.ONCE, page = BOARD),
        )
        assertTrue(ConfigFields.merge(fields).isEmpty())
    }

    @Test
    fun `a repeated key keeps its first entry`() {
        val a = ConfigField("panel.theme", FieldType.Choice(listOf("light")), Applies.IMMEDIATELY, page = BOARD)
        val b = a.copy(type = FieldType.Choice(listOf("dark")))
        assertEquals(listOf(a), ConfigFields.merge(listOf(a, b)))
    }

    @Test
    fun `the builder sends a board key in range`() {
        val sleep = ConfigFields.declared("panel.sleep_s", "integer", listOf("0", "600"), "immediately", BOARD)!!
        val theme = ConfigFields.declared("panel.theme", "enum", listOf("light", "dark"), "immediately", BOARD)!!
        val clean = ConfigFields.merge(listOf(ConfigFields.declared("sen6x.fan_clean", "boolean", emptyList(), "action", BOARD)!!)).single()
        val label = ConfigFields.declared("panel.label", "text", listOf("1", "8"), "immediately", BOARD)!!
        val patch = ConfigPatch.build { field(sleep, "600").field(theme, "dark").field(clean, "1").field(label, "Kitchen") }
        assertEquals("panel.sleep_s=600\npanel.theme=dark\nsen6x.fan_clean=1\npanel.label=Kitchen\n", patch.encode())
        assertFalse(patch.carriesSecret)
    }

    @Test
    fun `the builder refuses an out of range value`() {
        val sleep = ConfigFields.declared("panel.sleep_s", "integer", listOf("0", "600"), "immediately", BOARD)!!
        val theme = ConfigFields.declared("panel.theme", "enum", listOf("light", "dark"), "immediately", BOARD)!!
        val asc = ConfigFields.declared("sen6x.co2_asc", "boolean", emptyList(), "immediately", BOARD)!!
        val label = ConfigFields.declared("panel.label", "text", listOf("1", "8"), "immediately", BOARD)!!
        val bad = listOf(
            sleep to "601", sleep to "-1", sleep to "010", sleep to "+5", sleep to "", sleep to "1e2",
            theme to "blue", theme to "", asc to "2", asc to "true",
            label to "", label to "nine char", label to "a\nb", label to "a\u007Fb",
        )
        for ((field, value) in bad) {
            val e = assertFailsWith<ConfigValidationException>(value) { ConfigPatch.build { field(field, value) } }
            assertEquals(ConfigProblem.FIELD_VALUE, e.problem)
            assertEquals("${field.key} refused a value", e.message, "the message never echoes the value")
        }
    }

    @Test
    fun `the builder refuses a secret or reserved field`() {
        val tries = listOf(
            ConfigFields.of("wifi.psk")!!,
            ConfigFields.of("ble.pin")!!,
            ConfigFields.of("wifi.ssid")!!,
            ConfigField("wifi.extra", FieldType.Toggle, Applies.IMMEDIATELY, page = BOARD),
            ConfigField("panel.code", FieldType.Text(0, 8), Applies.IMMEDIATELY, secret = true, page = BOARD),
            ConfigField("panel.cell", FieldType.H3Cell, Applies.IMMEDIATELY, page = BOARD),
            // An action that skipped merge, so it lacks the confirmation a board action gets.
            ConfigField("sen6x.fan_clean", FieldType.Action, Applies.ONCE, page = BOARD),
        )
        for (field in tries) {
            assertFailsWith<IllegalArgumentException>(field.key) { ConfigPatch.build { field(field, "1") } }
        }
    }

    private companion object {
        const val BOARD = Request.GetConfigPage.BOARD_SETTINGS
    }
}
