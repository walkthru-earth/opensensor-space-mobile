package space.opensensor.model

import space.opensensor.units.UcumDisplay

/**
 * How the app shows the columns of one board template, keyed by [dict]. [derive] builds it from the
 * template's display hints, the thing Collection and the bundled property and scale registries. A
 * bundled profile JSON is only an overlay of [family], [hero] and [hidden], which [select] lays on top.
 */
data class AppProfile(
    val dict: String,
    val family: Family = Family.GENERIC,
    /** The column a screen opens on, see [derive], or null when the template measures nothing. */
    val primary: String? = null,
    /** Optional hero card id that a family plugin renders. */
    val hero: String? = null,
    /** Column labels by language. */
    val labels: Map<String, LocalizedText> = emptyMap(),
    /** Decimals each column shows with. See [decimalsOf]. */
    val decimals: Map<String, Int> = emptyMap(),
    /** Unit text per column, such as `µg/m³` for `ug/m3`. A column without one shows its template unit. */
    val displayUnit: Map<String, String> = emptyMap(),
    /** The scale each column is judged on. A column without one is neutral. */
    val scales: Map<String, Scale> = emptyMap(),
    /** Columns the board writes but never fills, such as the CoreS3 ambient columns. Never shown, not even in LIVE. */
    val hidden: Set<String> = emptySet(),
) {
    init {
        require(decimals.values.all { it in 0..Datastream.MAX_DECIMALS }) { "profile $dict has decimals out of range" }
    }

    /** The decimals [datastream] shows with, from [decimals], else 0 for an integer type and 2 otherwise. */
    fun decimalsOf(datastream: Datastream): Int = decimals[datastream.name] ?: defaultDecimals(datastream)

    /** The label of [datastream], from [labels], else its name with `_` as spaces. */
    fun labelOf(datastream: Datastream): LocalizedText = labels[datastream.name] ?: nameLabel(datastream)

    /** The unit text of [datastream], from [displayUnit], else its template unit, null for a unitless column. */
    fun displayUnitOf(datastream: Datastream): String? = displayUnit[datastream.name] ?: datastream.unit

    companion object {
        /**
         * Parses a bundled profile overlay, `dict`, `family`, `hero` and `hidden`. Unknown fields are ignored.
         *
         * @throws IllegalArgumentException when the JSON is not a valid app profile.
         */
        fun parse(json: String): AppProfile = ModelJson.parseProfile(json)

        /** [derive] of [template] with [overlay]'s family, hero and hidden columns when it is for [template]'s dictionary. */
        fun select(template: BoardTemplate, overlay: AppProfile?, collection: ThingCollection? = null): AppProfile =
            derive(template, collection, overlay?.takeIf { it.dict == template.dict })

        /**
         * The profile of [template], so any template renders with no app change. First hit wins.
         *
         * - Label. Column `title_i18n` in the language, then the Collection's column title, then the
         *   column `title`, then the registry label of its `opensensor:property`, then its name with `_`
         *   as spaces.
         * - Decimals. Column `opensensor:decimals`, then the registry, then 0 for an integer type and 2 otherwise.
         * - Display unit. The [UcumDisplay] symbol of its UCUM unit, then the firmware unit label, then the unit.
         * - Scale. The Collection's scale for the column, then the column `opensensor:scale` in the
         *   Collection's or the template's `opensensor:scales`, then the registry scale of its property.
         *   A scale in another unit than the column's is never applied.
         * - Primary. The Collection `opensensor:primary`, then the template's, then the first
         *   `physicalMeasurement` column with a scale, then the first `physicalMeasurement` column.
         *
         * [overlay] adds its family and hero and hides its hidden columns, which never become primary.
         */
        fun derive(
            template: BoardTemplate,
            collection: ThingCollection? = null,
            overlay: AppProfile? = null,
            properties: PropertyRegistry = BundledCatalog.properties,
            registryScales: ScaleRegistry = BundledCatalog.scales,
        ): AppProfile {
            val hidden = overlay?.hidden.orEmpty()
            val columns = template.datastreams
            val scales = columns.mapNotNull { ds -> scaleOf(ds, template, collection, properties, registryScales)?.let { ds.name to it } }.toMap()
            val shown = columns.filter { it.name !in hidden }
            val measured = shown.filter { it.role == Role.PHYSICAL_MEASUREMENT }
            val primary = listOfNotNull(collection?.primary, template.primary).firstOrNull { name -> shown.any { it.name == name } }
                ?: measured.firstOrNull { it.name in scales }?.name
                ?: measured.firstOrNull()?.name
            return AppProfile(
                dict = template.dict,
                family = overlay?.family ?: Family.GENERIC,
                primary = primary,
                hero = overlay?.hero,
                labels = columns.associate { it.name to labelOf(it, collection, properties) },
                decimals = columns.associate { it.name to (it.decimals ?: properties.of(it.property)?.decimals ?: defaultDecimals(it)) },
                displayUnit = columns.mapNotNull { ds -> ds.unit?.let { ds.name to displayUnitOf(it, ds.unitLabel) } }.toMap(),
                scales = scales,
                hidden = hidden,
            )
        }

        private fun defaultDecimals(datastream: Datastream): Int = if (datastream.isInteger) 0 else 2

        private fun nameLabel(datastream: Datastream): LocalizedText = LocalizedText.of(datastream.name.replace('_', ' '))

        private fun labelOf(ds: Datastream, collection: ThingCollection?, properties: PropertyRegistry): LocalizedText {
            val untagged = collection?.columnTitles?.get(ds.name) ?: ds.title
            if (untagged != null || ds.titleI18n.isNotEmpty()) {
                return LocalizedText(ds.titleI18n + listOfNotNull(untagged?.let { LocalizedText.UNTAGGED to it }))
            }
            return properties.of(ds.property)?.label ?: nameLabel(ds)
        }

        private fun displayUnitOf(unit: String, unitLabel: String?): String {
            val symbol = UcumDisplay.of(unit)
            return if (symbol != unit) symbol else unitLabel ?: unit
        }

        private fun scaleOf(
            ds: Datastream,
            template: BoardTemplate,
            collection: ThingCollection?,
            properties: PropertyRegistry,
            registryScales: ScaleRegistry,
        ): Scale? {
            val named = collection?.columnScales?.get(ds.name) ?: ds.scale
            val scale = if (named != null) {
                collection?.scales?.get(named) ?: template.scales[named]
            } else {
                registryScales[properties.of(ds.property)?.scaleId]
            }
            return scale?.takeIf { it.unit == null || it.unit == ds.unit }
        }
    }
}
