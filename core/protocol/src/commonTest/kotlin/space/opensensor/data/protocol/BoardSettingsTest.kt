package space.opensensor.data.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** GET_CONFIG page [Request.GetConfigPage.BOARD_SETTINGS] values by `SET_CONFIG` key. */
class BoardSettingsTest {
    @Test
    fun `the page flattens to set config keys`() {
        val page = """{"panel":{"theme":"dark","refresh_s":60,"start_page":2},"sen6x":{"co2_asc":true,"altitude_m":120,"temp_offset_dc":-15}}"""
        val s = BoardSettings.parse(page)
        assertEquals(
            mapOf(
                "panel.theme" to "dark",
                "panel.refresh_s" to "60",
                "panel.start_page" to "2",
                "sen6x.co2_asc" to "1",
                "sen6x.altitude_m" to "120",
                "sen6x.temp_offset_dc" to "-15",
            ),
            s.values,
        )
        assertEquals("dark", s["panel.theme"])
    }

    @Test
    fun `null and arrays are no value`() {
        val s = BoardSettings.parse("""{"panel":{"theme":null,"pages":[1,2],"sleep_s":0,"night":false}}""")
        assertNull(s["panel.theme"])
        assertNull(s["panel.pages"])
        assertEquals("0", s["panel.sleep_s"])
        assertEquals("0", s["panel.night"])
    }

    @Test
    fun `reserved and secret keys never come from the board page`() {
        val s = BoardSettings.parse("""{"wifi":{"psk":"x","ssid":"home"},"ble":{"pin":"123456"},"thing":{"setup_ack":3},"panel":{"theme":"light"}}""")
        assertEquals(mapOf("panel.theme" to "light"), s.values)
    }

    @Test
    fun `an empty page holds no value`() {
        assertEquals(BoardSettings.EMPTY, BoardSettings.parse("{}"))
    }

    @Test
    fun `deep nesting stops at the depth limit`() {
        val s = BoardSettings.parse("""{"a":{"b":{"c":{"d":"kept","e":{"f":"dropped"}}}}}""")
        assertEquals(mapOf("a.b.c.d" to "kept"), s.values)
    }

    @Test
    fun `a page that is not an object is refused`() {
        assertFailsWith<IllegalArgumentException> { BoardSettings.parse("[1]") }
    }

    @Test
    fun `the board page is page 7`() {
        assertEquals(7, Request.GetConfigPage.BOARD_SETTINGS)
        val bytes = Request.GetConfigPage(Request.GetConfigPage.BOARD_SETTINGS).encode().toList()
        assertEquals(listOf(Opcode.GET_CONFIG.toByte(), 7.toByte()), bytes)
    }
}
