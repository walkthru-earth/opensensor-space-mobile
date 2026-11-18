package space.opensensor.data.sync

import space.opensensor.data.protocol.ConfigField
import space.opensensor.data.protocol.ConfigFields
import space.opensensor.data.protocol.DeviceConfig
import space.opensensor.data.protocol.DeviceInfo
import space.opensensor.data.protocol.SetupAck
import space.opensensor.data.protocol.ThingMobility
import space.opensensor.model.BoardTemplate
import space.opensensor.model.BundledCatalog
import space.opensensor.model.ConfigValues
import space.opensensor.model.H3Cell
import space.opensensor.model.LocalizedText
import space.opensensor.model.SetupManifest
import space.opensensor.model.StepId

/** A board key a generic step or section shows, after the app floors, with its template [label]. */
data class PlanField(val field: ConfigField, val label: LocalizedText?)

/** How the wizard draws a [PlanStep]. */
enum class PlanStepKind {
    /** A component of the app catalog, [StepId.KNOWN]. */
    CATALOG,

    /** A board id with typed keys, drawn as a form of its [PlanStep.fields]. */
    GENERIC,

    /** A required board id the app cannot draw. Setup stays pending until the app is updated. */
    UNSUPPORTED,
}

/**
 * One step of a [SetupPlan]. [ackIndex] is its bit in `thing.setup_ack`, its place in the template
 * `firmware.setup`, and null for the location step the app adds itself. [acked] is that bit as the
 * device reports it for the plan's [SetupPlan.version]. [satisfied] is the fact the step needs, read
 * from page 1 (a cell for a fixed Thing's location, a known mobility), true for a step that needs none.
 * [label] is the template's title of a board step, null for a catalog step, which has its own.
 */
data class PlanStep(
    val id: StepId,
    val kind: PlanStepKind,
    val required: Boolean,
    val ackIndex: Int?,
    val acked: Boolean,
    val satisfied: Boolean,
    val fields: List<PlanField> = emptyList(),
    val label: LocalizedText? = null,
) {
    /** The fact holds and, for a declared step, the device holds its bit. */
    val done: Boolean get() = kind != PlanStepKind.UNSUPPORTED && satisfied && (ackIndex == null || acked)

    /** Skip is offered on an optional step, and Skip sets its bit too. */
    val skippable: Boolean get() = !required && kind != PlanStepKind.UNSUPPORTED
}

/**
 * The setup steps of one Thing, the same on both phones. It merges the board's template
 * `firmware.setup` ([SetupManifest]), the device's page 1 record (`thing.setup_v` and
 * `thing.setup_ack`, read through [SetupAck]) and the app floors.
 *
 * - The steps keep the manifest order. Find, Pair, Permission and Done are the app's own and are
 *   never in a plan.
 * - A catalog id is a [PlanStepKind.CATALOG] step, any keys the manifest lists for it are ignored.
 *   Only location and mobility can be required, as only they have a fact on page 1. Every other catalog
 *   step stays optional whatever `req` says.
 * - A board id whose keys survive [fieldsOf] is [PlanStepKind.GENERIC]. Without such keys an optional
 *   one is dropped, never pending and never acked, and a required one is [PlanStepKind.UNSUPPORTED].
 * - Location is required on a fixed Thing whatever the manifest says. When the manifest omits it, a
 *   fixed Thing gets the app's own location step first, which has no bit and is done by the cell alone.
 * - A step at index [SetupAck.MAX_STEPS] or later has no bit, and is dropped.
 * - A repeated id keeps its first place.
 *
 * [version] is [SetupAck.version] of every declared step id, null when the manifest declares none.
 * Setup is [pending] while any planned step is not [PlanStep.done].
 */
data class SetupPlan(val steps: List<PlanStep>, val version: Int?, private val doneBits: Int = 0) {
    /** Some step is not done. */
    val pending: Boolean get() = steps.any { !it.done }

    /** A required step needs a newer app. */
    val unsupported: Boolean get() = steps.any { it.kind == PlanStepKind.UNSUPPORTED }

    fun step(id: StepId): PlanStep? = steps.firstOrNull { it.id == id }

    /**
     * The `thing.setup_ack` to send once [id] is done or skipped, the device's bits for this list plus
     * its own. Null when the step has no bit, so nothing is written for it.
     */
    fun ackWith(id: StepId): Int? = ackWith(listOf(id))

    /** The `thing.setup_ack` that sets the bits of every step of [ids] at once, null when none has a bit. */
    fun ackWith(ids: Collection<StepId>): Int? {
        if (version == null) return null
        val bits = ids.mapNotNull { step(it)?.ackIndex }.fold(0) { acc, index -> acc or (1 shl index) }
        return if (bits == 0) null else doneBits or bits
    }

    companion object {
        /** The plan of [manifest] for a device whose page 1 is [config], null before it was read. */
        fun of(manifest: SetupManifest, config: DeviceConfig?): SetupPlan {
            val declared = manifest.setup
            val version = declared.takeIf { it.isNotEmpty() }?.let { list -> SetupAck.version(list.map { it.id.id }) }
            val bits = version?.let { SetupAck.done(config?.thing, it) } ?: 0
            val fixed = isFixed(config)
            val located = !fixed || hasCell(config)
            val seen = HashSet<StepId>()
            val steps = ArrayList<PlanStep>()
            declared.forEachIndexed { index, entry ->
                if (index >= SetupAck.MAX_STEPS || !seen.add(entry.id)) return@forEachIndexed
                val acked = (bits and (1 shl index)) != 0
                val step = when {
                    entry.id.isKnown -> {
                        PlanStep(
                            id = entry.id,
                            kind = PlanStepKind.CATALOG,
                            required = when (entry.id) {
                                StepId.LOCATION -> entry.required || fixed
                                StepId.MOBILITY -> entry.required
                                else -> false
                            },
                            ackIndex = index,
                            acked = acked,
                            satisfied = when (entry.id) {
                                StepId.LOCATION -> located
                                StepId.MOBILITY -> !entry.required || config?.thing?.thingMobility != null
                                else -> true
                            },
                        )
                    }
                    else -> {
                        val fields = fieldsOf(manifest, entry.keys)
                        when {
                            fields.isNotEmpty() -> PlanStep(entry.id, PlanStepKind.GENERIC, entry.required, index, acked, satisfied = true, fields = fields, label = entry.label)
                            entry.required -> PlanStep(entry.id, PlanStepKind.UNSUPPORTED, required = true, ackIndex = index, acked = acked, satisfied = false, label = entry.label)
                            else -> null
                        }
                    }
                }
                step?.let(steps::add)
            }
            if (fixed && StepId.LOCATION !in seen) {
                steps.add(0, PlanStep(StepId.LOCATION, PlanStepKind.CATALOG, required = true, ackIndex = null, acked = false, satisfied = located))
            }
            return SetupPlan(steps, version, bits)
        }

        /**
         * The board keys among [keys] a form may show, in order. Each goes through
         * [ConfigFields.declared] and [ConfigFields.merge], and only a board key read back from the
         * board settings page is kept. A secret, an app key, a key under a reserved prefix, a type
         * that does not parse and a key the manifest does not declare are left out.
         */
        fun fieldsOf(manifest: SetupManifest, keys: List<String>): List<PlanField> {
            val labels = HashMap<String, LocalizedText?>()
            val offered = manifest.keysOf(keys).mapNotNull { spec ->
                if (ConfigFields.of(spec.key) != null || ConfigFields.isReserved(spec.key)) return@mapNotNull null
                val applies = spec.applies ?: return@mapNotNull null
                val values = valuesOf(spec.values) ?: return@mapNotNull null
                labels[spec.key] = spec.label
                ConfigFields.declared(spec.key, spec.type.id, values, applies.id, spec.page)
            }
            return ConfigFields.merge(offered).map { PlanField(it, labels[it.key]) }
        }

        /** The `values` of a key in the text form [ConfigFields.declared] reads, null for a bound that is not whole. */
        private fun valuesOf(values: ConfigValues?): List<String>? = when (values) {
            null -> emptyList()
            is ConfigValues.Choices -> values.values
            is ConfigValues.Range -> {
                val min = values.min?.let(::whole)
                val max = values.max?.let(::whole)
                if (min == null || max == null) null else listOf(min, max)
            }
        }

        private fun whole(value: Double): String? =
            value.takeIf { it % 1.0 == 0.0 && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()?.toString()

        /**
         * The setup manifest of the board that sent [info], from its [served] template or the bundled
         * one, or [SetupManifest.NONE] when neither is known.
         */
        fun manifestOf(info: DeviceInfo?, served: List<BoardTemplate>): SetupManifest {
            val template = info?.dict?.let { BundledCatalog.resolve(it, info.desc, served) }
            return template?.setup ?: SetupManifest.NONE
        }

        /**
         * A Thing stays in one cell when page 1 says `fixed`, or names no mobility while it reports a
         * `location` object, the firmware default. Before page 1 is read it counts as fixed, so the
         * wizard shows the location step. A mobility this build does not know is not fixed.
         */
        internal fun isFixed(config: DeviceConfig?): Boolean {
            if (config == null) return true
            val mobility = config.thing?.mobility ?: return config.location != null
            return ThingMobility.fromWire(mobility) == ThingMobility.FIXED
        }

        /** Any valid H3 cell counts, at any resolution, as the board clears its setup bit for any stored cell. */
        private fun hasCell(config: DeviceConfig?): Boolean = config?.location?.cell?.let { H3Cell.isCellHex(it) } == true
    }
}
