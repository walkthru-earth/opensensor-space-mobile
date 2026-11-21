package space.opensensor.device

import space.opensensor.data.sync.PlanField
import space.opensensor.data.sync.SetupPlan
import space.opensensor.model.LocalizedText
import space.opensensor.model.SetupManifest
import space.opensensor.model.StepId
import space.opensensor.settings.DetailLevel

/** One Device settings card, as the board template's `firmware.settings` declares it. */
sealed interface DeviceSection {
    /** The area, its mobility and the owner site, one card for the `location`, `mobility` and `site` ids. */
    data object Location : DeviceSection

    data object Wifi : DeviceSection

    data object Pairing : DeviceSection

    data object Lan : DeviceSection

    data object Bonds : DeviceSection

    /** The fitted sensor module and its sticker, for the `sensor` section. */
    data object Sensor : DeviceSection

    /** A board section, a form of its declared page 7 keys under its template [label]. */
    data class Board(val id: StepId, val fields: List<PlanField>, val label: LocalizedText? = null) : DeviceSection {
        /** The template's title in [language], null when the section has none and keeps Sensor settings. */
        fun title(language: String): String? = label?.get(language)
    }

    companion object {
        /** The section that carries the sticker card. Its keys, if any, are an ordinary board form under it. */
        val SENSOR = StepId("sensor")

        /**
         * The cards of [manifest] at [level], in the template order. A repeated section keeps its first
         * place. Pairing and paired phones show from Detailed, pairing also at Simple when it needs
         * attention. Sync over Wi-Fi shows at Developer only, as every other level keeps it on by itself.
         * The `sensor` section shows the sticker card from Detailed on a board with sensor identity, then
         * a form of its keys like any board section. A board section without a key the app may show is
         * left out, and so is `name`, a phone nickname that is not a board card.
         */
        fun of(manifest: SetupManifest, level: DetailLevel, pairingNeedsAttention: Boolean, sensorIdentity: Boolean): List<DeviceSection> {
            val cards = ArrayList<DeviceSection>()
            val seen = HashSet<StepId>()
            fun add(card: DeviceSection?) {
                if (card != null && card !in cards) cards.add(card)
            }
            manifest.settings.forEach { declared ->
                if (!seen.add(declared.id)) return@forEach
                when (declared.id) {
                    StepId.LOCATION, StepId.MOBILITY, StepId.SITE -> add(Location)
                    StepId.WIFI -> add(Wifi)
                    StepId.PAIRING -> add(Pairing.takeIf { level.detailed || pairingNeedsAttention })
                    StepId.LAN -> add(Lan.takeIf { level.developer })
                    StepId.BONDS -> add(Bonds.takeIf { level.detailed })
                    StepId.NAME -> Unit
                    else -> {
                        if (declared.id == SENSOR) add(Sensor.takeIf { level.detailed && sensorIdentity })
                        val fields = SetupPlan.fieldsOf(manifest, declared.keys)
                        if (fields.isNotEmpty()) add(Board(declared.id, fields, declared.label))
                    }
                }
            }
            return cards
        }
    }
}
