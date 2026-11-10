package space.opensensor.stac

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import space.opensensor.data.history.OpenSensorFooter
import space.opensensor.model.ThingSite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Collection rebuilt from each Thing's owner config, board template, footer and example Item equals
 * the example Collection of stac-opensensor v0.1.0 (tag v0.1.0, commit
 * 83787768cc8451109d48a03d4ef5493ffcd403dd) as JSON values, as `scripts/check-profile.mjs` rebuilds it.
 * The fixtures and their source paths are listed in `fixtures/stac-opensensor/README.md`.
 */
class ThingCollectionsTest {
    @Test
    fun aRebuiltFixedCollectionEqualsTheExample() {
        assertSameCollection("collection-fixed.json", "owner-fixed.json", "footers/m5stack-cores3.json", FIXED_EXAMPLE)
    }

    @Test
    fun aRebuiltMovingCollectionEqualsTheExample() {
        assertSameCollection("collection-moving.json", "owner-moving.json", "footers/waveshare-esp32-s3-sim7670g.json", MOVING_EXAMPLE)
    }

    @Test
    fun theOwnerSiteGivesTheTitleDescriptionAndProducer() {
        val fixture = ThingOwner.parse(text("owner-fixed.json"))
        val site = ThingSite(
            title = fixture.title,
            provider = "Osu air sensor owner",
            url = "",
            description = fixture.description,
            deploymentId = "osu-roof-1",
        )
        val owner = ThingOwner.of(site, fixture.prefix, HOST)
        assertEquals(fixture, owner.copy(columns = fixture.columns, scales = fixture.scales))
    }

    @Test
    fun anOwnerUrlIsTheProducersLinkAndEmptyValuesAreLeftOut() {
        val owner = ThingOwner.of(ThingSite(title = "", provider = "Osu school", url = "https://example.org/osu"), PREFIX, HOST)
        assertNull(owner.title)
        assertNull(owner.description)
        assertEquals(listOf(StacProvider("Osu school", listOf("producer", "licensor"), "https://example.org/osu"), HOST), owner.providers)
        assertEquals(listOf(HOST), ThingOwner.of(ThingSite(provider = " "), PREFIX, HOST).providers)
        val collection = build(owner)
        assertFalse("title" in collection)
        assertFalse("description" in collection)
    }

    @Test
    fun contactsAddTheContactsExtension() {
        val contact = buildJsonObject { put("name", "Osu school") }
        val collection = build(ThingOwner.of(ThingSite(provider = "Osu school"), PREFIX, HOST).copy(contacts = JsonArray(listOf(contact))))
        val extensions = collection["stac_extensions"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(ThingCollections.EXTENSIONS, extensions)
        assertEquals(JsonArray(listOf(contact)), collection["contacts"])
        val none = build(ThingOwner.of(ThingSite(provider = "Osu school"), PREFIX, HOST))
        assertEquals(ThingCollections.EXTENSIONS - ThingCollections.CONTACTS_EXTENSION, none["stac_extensions"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse("contacts" in none)
    }

    @Test
    fun scalesAreListedOnlyWhenAColumnNamesOne() {
        val titled = ThingOwner.of(ThingSite(provider = "Osu school"), PREFIX, HOST)
            .copy(columns = mapOf("ambient_temperature_c" to ColumnLabel(title = "Air temperature")), scales = buildJsonObject { put("x", 1) })
        val collection = build(titled)
        assertFalse("opensensor:scales" in collection)
        val column = collection["item_assets"]!!.jsonObject["data"]!!.jsonObject["table:columns"]!!.jsonArray
            .map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == "ambient_temperature_c" }
        assertEquals("Air temperature", column["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun theLedgerIsTheEarliestStartAndTheUnionOfTheBoxes() {
        fun item(start: String, vararg bbox: Double) = buildJsonObject {
            put("bbox", JsonArray(bbox.map { JsonPrimitive(it) }))
            put("properties", buildJsonObject { put("start_datetime", start) })
        }
        val ledger = CollectionLedger.of(
            listOf(item("2026-10-24T13:15:00Z", 1.0, 2.0, 3.0, 4.0), item("2026-10-24T13:00:00.4Z", 0.5, 2.5, 2.0, 5.0)),
        )
        assertEquals(CollectionLedger("2026-10-24T13:00:00.4Z", listOf(0.5, 2.0, 3.0, 5.0)), ledger)
        assertNull(CollectionLedger.of(emptyList()))
        assertNull(CollectionLedger.of(listOf(buildJsonObject { put("properties", buildJsonObject { put("start_datetime", "x") }) })))
    }

    @Test
    fun anOwnerConfigMayOnlyLabelAColumn() {
        assertFailsWith<IllegalArgumentException> {
            ThingOwner.parse("""{"prefix":"p","columns":{"a":{"title":"A","unit":"K"}}}""")
        }
        assertFailsWith<IllegalArgumentException> { ThingOwner.parse("""{"title":"no prefix"}""") }
    }

    @Test
    fun aCollectionThatDiffersIsFound() {
        val expected = json("collection-fixed.json")
        val moved = JsonObject(expected + ("opensensor:mobility" to JsonPrimitive("moving")))
        assertEquals("$.opensensor:mobility", CanonicalJson.difference(expected, moved))
    }

    private fun assertSameCollection(collectionName: String, ownerName: String, footerName: String, itemName: String) {
        val expected = json(collectionName)
        val footerJson = json(footerName)
        val footer = assertNotNull(OpenSensorFooter.parse(footerJson.toString()))
        val href = footerJson["template"]!!.jsonObject["href"]!!.jsonPrimitive.content
        val built = ThingCollections.build(
            template = templateOf(footer),
            footer = assertNotNull(CollectionFooter.of(footer, href)),
            owner = ThingOwner.parse(text(ownerName)),
            ledger = assertNotNull(CollectionLedger.of(listOf(json(itemName)))),
        )
        val at = CanonicalJson.difference(expected, built)
        assertNull(at, "rebuilt Collection differs at $at\n$built\nfrom the example\n$expected")
    }

    /** A Collection of the fixed example Thing for [owner]. */
    private fun build(owner: ThingOwner): JsonObject {
        val footerJson = json("footers/m5stack-cores3.json")
        val footer = assertNotNull(OpenSensorFooter.parse(footerJson.toString()))
        val ledger = assertNotNull(CollectionLedger.of(listOf(json(FIXED_EXAMPLE))))
        val collectionFooter = assertNotNull(CollectionFooter.of(footer, "https://example.org/template.json"))
        return ThingCollections.build(templateOf(footer), collectionFooter, owner, ledger).also {
            assertTrue(it["partition:glob"]!!.jsonPrimitive.content.startsWith("s3://$PREFIX/thing=$THING/"))
        }
    }

    /** The board template the [footer] names, as the template file's JSON. */
    private fun templateOf(footer: OpenSensorFooter): JsonObject {
        val board = if (footer.dictionarySha256 == coreS3Template.dict) "m5stack-cores3" else "waveshare-esp32-s3-sim7670g"
        return json("templates/$board/${footer.dictionarySha256}.json")
    }

    private fun text(path: String): String = stacFile(path).decodeToString()

    private fun json(path: String): JsonObject = Json.parseToJsonElement(text(path)).jsonObject

    private companion object {
        const val PREFIX = "walkthru-earth/opensensor-space"
        val HOST = StacProvider("Walkthru.Earth", listOf("host"), "https://walkthru.earth")
    }
}
