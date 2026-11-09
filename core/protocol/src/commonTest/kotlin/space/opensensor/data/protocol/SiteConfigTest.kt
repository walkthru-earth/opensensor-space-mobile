package space.opensensor.data.protocol

import space.opensensor.model.SiteField
import space.opensensor.model.ThingSite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The owner site keys, GET_CONFIG pages 5 and 6 and their own SET_CONFIG (firmware #148, mobile #272). */
class SiteConfigTest {
    private val page5 = """{"owner":{"title":"Accra Ridge","provider":"Walkthru","url":"https://opensensor.space"},"thing":{"deployment_id":"accra-1"}}"""
    private val page6 = """{"owner":{"description":"School roof"}}"""

    @Test
    fun `pages 5 and 6 encode the page byte`() {
        assertEquals(listOf(Opcode.GET_CONFIG.toByte(), 5), Request.GetConfigPage(Request.GetConfigPage.SITE).encode().toList())
        assertEquals(listOf(Opcode.GET_CONFIG.toByte(), 6), Request.GetConfigPage(Request.GetConfigPage.SITE_DESCRIPTION).encode().toList())
        assertFailsWith<IllegalArgumentException> { Request.GetConfigPage(256) }
    }

    @Test
    fun `the phone merges page 5 and page 6`() {
        val site = siteOf(DeviceConfig.parse(page5), DeviceConfig.parse(page6))
        assertEquals(ThingSite("Accra Ridge", "Walkthru", "https://opensensor.space", "School roof", "accra-1"), site)
    }

    @Test
    fun `a key a page left out stays null and never takes a default`() {
        val site = siteOf(DeviceConfig.parse("""{"owner":{"title":""}}"""), DeviceConfig.parse("""{"owner":{}}"""))
        assertEquals(ThingSite(title = ""), site)
        // Page 6 owns the description. A description on page 5 is not read.
        val misplaced = siteOf(DeviceConfig.parse("""{"owner":{"description":"x"}}"""), DeviceConfig.parse("""{"owner":{}}"""))
        assertNull(misplaced.description)
    }

    @Test
    fun `a page without owner parses with owner null`() {
        assertNull(DeviceConfig.parse("""{"ble":{"pair":"random"}}""").owner)
        assertNull(DeviceConfig.parse("""{"thing":{"mobility":"fixed"}}""").thing?.deploymentId)
    }

    @Test
    fun `site values encode as key lines and an emptied one as key with nothing after`() {
        val patch = ConfigPatch.build {
            site(SiteField.TITLE, "")
            site(SiteField.URL, "https://opensensor.space")
            site(SiteField.DEPLOYMENT_ID, "accra-1")
        }
        assertEquals("owner.title=\nowner.url=https://opensensor.space\nthing.deployment_id=accra-1\n", patch.encode())
        assertTrue(patch.isSite)
    }

    @Test
    fun `the change map sends every field it holds and nothing else`() {
        val changes = mapOf(SiteField.PROVIDER to "Walkthru", SiteField.DESCRIPTION to "")
        val patch = ConfigPatch.build { site(changes) }
        assertEquals(listOf("owner.provider", "owner.description"), patch.keys)
        assertEquals("owner.provider=Walkthru\nowner.description=\n", patch.encode())
    }

    @Test
    fun `site keys never share a SET_CONFIG with another key`() {
        assertFailsWith<IllegalStateException> {
            ConfigPatch.build { location("8828308281fffff", "GH").site(SiteField.TITLE, "Roof") }
        }
        assertFailsWith<IllegalStateException> {
            ConfigPatch.build { site(SiteField.DEPLOYMENT_ID, "accra-1").thingMobility(ThingMobility.FIXED) }
        }
        assertFailsWith<IllegalStateException> {
            ConfigPatch.build { site(SiteField.URL, "https://a.example").sensorIdentity("Acme", "X1", "X1-0042", batchCandidate = false) }
        }
        assertFalse(ConfigPatch.build { location("8828308281fffff", "GH") }.isSite)
        assertFalse(ConfigPatch.build { }.isSite, "an empty patch is not a site write")
    }

    @Test
    fun `a refused value throws a site problem whose message keeps the value out`() {
        val secretish = "http://private.example/" + "x".repeat(10)
        val e = assertFailsWith<ConfigValidationException> { ConfigPatch.build { site(SiteField.URL, secretish) } }
        assertEquals(ConfigProblem.SITE_VALUE, e.problem)
        assertTrue(e.message!!.startsWith("owner.url"), e.message)
        assertFalse(e.message!!.contains("private.example"), e.message)
        listOf(
            SiteField.TITLE to "a\nb",
            SiteField.PROVIDER to "a".repeat(65),
            SiteField.DESCRIPTION to "a\u0085b",
            SiteField.DEPLOYMENT_ID to "Accra",
            SiteField.DEPLOYMENT_ID to "unknown",
            SiteField.URL to "https://u@a.example",
        ).forEach { (field, value) ->
            assertEquals(ConfigProblem.SITE_VALUE, assertFailsWith<ConfigValidationException>("$field") { ConfigPatch.build { site(field, value) } }.problem)
        }
    }

    @Test
    fun `each site key maps to its wire name`() {
        assertEquals(
            mapOf(
                SiteField.TITLE to "owner.title",
                SiteField.PROVIDER to "owner.provider",
                SiteField.URL to "owner.url",
                SiteField.DESCRIPTION to "owner.description",
                SiteField.DEPLOYMENT_ID to "thing.deployment_id",
            ),
            SiteField.entries.associateWith(ConfigFields::siteKey),
        )
        assertEquals(SiteField.entries.map { ConfigFields.siteKey(it) }.toSet(), ConfigFields.SITE_KEYS)
        SiteField.entries.forEach { f ->
            val type = ConfigFields.of(ConfigFields.siteKey(f))!!.type
            assertEquals(FieldType.Text(0, f.maxBytes), type, "$f")
        }
    }
}
