package space.opensensor.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * kotlinx serialization of the board template, the station Collection and the app profile. The wire
 * shapes stay internal, callers get the model types. Unknown fields are ignored, so a newer template
 * or Collection still parses.
 */
internal object ModelJson {
    private val json = Json { ignoreUnknownKeys = true }

    private const val TEMPLATE_TYPE = "OpenSensorTemplate"
    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

    /** Multihash prefix of a SHA-256 `file:checksum` (code 0x12, length 0x20). */
    private const val SHA256_MULTIHASH = "1220"

    fun parseTemplate(text: String, desc: String?): BoardTemplate {
        val dto = decode<TemplateDto>(text)
        require(dto.type == TEMPLATE_TYPE) { "not a board template, type is ${dto.type}" }
        val firmware = dto.firmware
        require(SHA256_HEX.matches(firmware.dict)) { "template ${dto.id} has a malformed dict digest" }
        require(desc == null || SHA256_HEX.matches(desc)) { "template ${dto.id} has a malformed desc digest" }
        val data = dto.itemAssets.data
        return BoardTemplate(
            key = TemplateKey(dict = firmware.dict, desc = desc),
            id = dto.id,
            schema = firmware.schema,
            title = dto.title,
            keywords = dto.keywords,
            instruments = dto.instruments,
            defaultSampleIntervalS = dto.sampleIntervalS,
            protoMin = firmware.protoMin,
            primaryDatetime = data.primaryDatetime,
            datastreams = data.columns.map { it.toDatastream(dto.id, firmware.liveKeys[it.name], firmware.unitLabels[it.name]) },
            liveKeys = firmware.liveKeys,
            dictionaryHref = dto.links.firstOrNull { it.rel == "describedby" && it.type != JSON_TYPE }?.href,
        )
    }

    fun parseCollection(text: String): StationCollection {
        val dto = decode<CollectionDto>(text)
        require(dto.type == "Collection") { "not a station Collection, type is ${dto.type}" }
        val columns = dto.itemAssets?.data?.columns.orEmpty()
        val describedBy = dto.links.filter { it.rel == "describedby" }
        return StationCollection(
            id = dto.id,
            title = dto.title,
            license = dto.license,
            sampleIntervalS = dto.sampleIntervalS,
            scales = dto.scales.mapValues { (id, scale) -> scale.toScale(id) },
            columnTitles = columns.mapNotNull { c -> c.title?.let { c.name to it } }.toMap(),
            columnScales = columns.mapNotNull { c -> c.scale?.let { c.name to it } }.toMap(),
            dict = describedBy.firstOrNull { it.type != JSON_TYPE }?.checksum?.let(::sha256OfMultihash),
            desc = describedBy.firstOrNull { it.type == JSON_TYPE }?.checksum?.let(::sha256OfMultihash),
        )
    }

    fun parseProfile(text: String): AppProfile {
        val dto = decode<ProfileDto>(text)
        require(SHA256_HEX.matches(dto.dict)) { "app profile has a malformed dict digest" }
        return AppProfile(
            dict = dto.dict,
            family = Family(dto.family),
            primary = dto.primary,
            hero = dto.hero,
            displayStep = dto.displayStep,
            labels = dto.labels.mapValues { (_, label) -> LocalizedText(label) },
            displayUnit = dto.displayUnit,
            scales = dto.scales,
            hidden = dto.hidden.toSet(),
        )
    }

    /** The SHA-256 hex of a multihash `file:checksum`, or null when it is not a SHA-256 multihash. */
    private fun sha256OfMultihash(checksum: String): String? =
        checksum.takeIf { it.startsWith(SHA256_MULTIHASH) }?.removePrefix(SHA256_MULTIHASH)?.takeIf { SHA256_HEX.matches(it) }

    private inline fun <reified T> decode(text: String): T = try {
        json.decodeFromString<T>(text)
    } catch (e: SerializationException) {
        throw IllegalArgumentException(e.message, e)
    }

    private const val JSON_TYPE = "application/json"

    private fun ColumnDto.toDatastream(board: String, liveKey: String?, unitLabel: String?): Datastream = Datastream(
        name = name,
        type = type,
        role = roles.firstOrNull()?.let(::Role),
        unit = unit,
        property = property?.takeIf { it.isNotEmpty() }?.let { ObservedProperty(it, standardName) },
        sensor = procedure?.takeIf { it.isNotEmpty() }?.let { Sensor(it, board) },
        fieldId = fieldId,
        validWhen = validWhen?.let(::gateOf),
        settleSeconds = settleS ?: 0.0,
        aggregate = aggregate?.let(::Aggregate),
        plausibleRange = plausibleRange?.let { r ->
            require(r.size == 2) { "column $name has a plausible range of ${r.size} values" }
            PlausibleRange(r[0], r[1])
        },
        classes = classes.map { CodeClass(it.value, it.name, it.colorHint, it.nodata) },
        derived = derived?.let { Derivation(it.from, it.method) },
        liveKey = liveKey,
        unitLabel = unitLabel,
    )

    private fun gateOf(o: JsonObject): ValidityGate {
        val column = o["column"]?.jsonPrimitive?.content
        require(!column.isNullOrEmpty()) { "a validity gate names no column" }
        val finite = o["finite"]
        if (finite != null) {
            require(finite.jsonPrimitive.booleanOrNull == true) { "gate on $column has finite other than true" }
            return ValidityGate.Finite(column)
        }
        val equals = o["equals"] as? JsonPrimitive ?: throw IllegalArgumentException("gate on $column has neither equals nor finite")
        val value = when {
            equals.isString -> GateValue.Text(equals.content)
            equals.booleanOrNull != null -> GateValue.Flag(equals.booleanOrNull!!)
            equals.doubleOrNull != null -> GateValue.Number(equals.doubleOrNull!!)
            else -> throw IllegalArgumentException("gate on $column has an unreadable value")
        }
        return ValidityGate.Equals(column, value)
    }

    private fun ScaleDto.toScale(id: String): Scale = Scale(
        id = id,
        source = source,
        unit = unit,
        guideline = guideline,
        steps = steps.map { ScaleStep(it.upper, labelOf(it.label), it.colorHint) },
    )

    private fun labelOf(e: JsonElement): LocalizedText = when (e) {
        is JsonPrimitive -> {
            require(e.isString) { "a label is a string or a language map" }
            LocalizedText.of(e.content)
        }
        is JsonObject -> LocalizedText(e.mapValues { (_, v) -> v.jsonPrimitive.content })
        else -> throw IllegalArgumentException("a label is a string or a language map")
    }
}

@Serializable
internal class TemplateDto(
    val type: String,
    val id: String,
    val title: String? = null,
    val keywords: List<String> = emptyList(),
    val instruments: List<String> = emptyList(),
    @SerialName("opensensor:sample_interval_s") val sampleIntervalS: Double,
    @SerialName("item_assets") val itemAssets: ItemAssetsDto,
    val links: List<LinkDto> = emptyList(),
    val firmware: FirmwareDto,
)

@Serializable
internal class FirmwareDto(
    val schema: String,
    @SerialName("proto_min") val protoMin: Int,
    val dict: String,
    @SerialName("live_keys") val liveKeys: Map<String, String> = emptyMap(),
    @SerialName("unit_labels") val unitLabels: Map<String, String> = emptyMap(),
)

@Serializable
internal class ItemAssetsDto(val data: DataAssetDto)

@Serializable
internal class DataAssetDto(
    @SerialName("table:primary_datetime") val primaryDatetime: String? = null,
    @SerialName("table:columns") val columns: List<ColumnDto> = emptyList(),
)

@Serializable
internal class ColumnDto(
    val name: String,
    val type: String = "",
    val title: String? = null,
    val unit: String? = null,
    val roles: List<String> = emptyList(),
    @SerialName("cf:standard_name") val standardName: String? = null,
    @SerialName("opensensor:property") val property: String? = null,
    @SerialName("opensensor:procedure") val procedure: String? = null,
    @SerialName("opensensor:field_id") val fieldId: Int? = null,
    @SerialName("opensensor:valid_when") val validWhen: JsonObject? = null,
    @SerialName("opensensor:settle_s") val settleS: Double? = null,
    @SerialName("opensensor:aggregate") val aggregate: String? = null,
    @SerialName("opensensor:plausible_range") val plausibleRange: List<Double>? = null,
    @SerialName("opensensor:scale") val scale: String? = null,
    @SerialName("opensensor:derived") val derived: DerivedDto? = null,
    @SerialName("classification:classes") val classes: List<ClassDto> = emptyList(),
)

@Serializable
internal class DerivedDto(val from: List<String>, val method: String)

@Serializable
internal class ClassDto(
    val value: Long,
    val name: String,
    @SerialName("color_hint") val colorHint: String? = null,
    val nodata: Boolean = false,
)

@Serializable
internal class LinkDto(
    val rel: String,
    val href: String,
    val type: String? = null,
    @SerialName("file:checksum") val checksum: String? = null,
)

@Serializable
internal class CollectionDto(
    val type: String,
    val id: String,
    val title: String? = null,
    val license: String? = null,
    @SerialName("opensensor:sample_interval_s") val sampleIntervalS: Double? = null,
    @SerialName("opensensor:scales") val scales: Map<String, ScaleDto> = emptyMap(),
    @SerialName("item_assets") val itemAssets: CollectionItemAssetsDto? = null,
    val links: List<LinkDto> = emptyList(),
)

@Serializable
internal class CollectionItemAssetsDto(val data: DataAssetDto? = null)

@Serializable
internal class ScaleDto(
    val source: String,
    val steps: List<StepDto>,
    val unit: String? = null,
    val guideline: Double? = null,
)

@Serializable
internal class StepDto(
    val upper: Double?,
    val label: JsonElement,
    @SerialName("color_hint") val colorHint: String? = null,
)

@Serializable
internal class ProfileDto(
    val dict: String,
    val family: String,
    val primary: List<String> = emptyList(),
    val hero: String? = null,
    @SerialName("display_step") val displayStep: Map<String, Double> = emptyMap(),
    val labels: Map<String, Map<String, String>> = emptyMap(),
    @SerialName("display_unit") val displayUnit: Map<String, String> = emptyMap(),
    val scales: Map<String, String> = emptyMap(),
    val hidden: List<String> = emptyList(),
)
