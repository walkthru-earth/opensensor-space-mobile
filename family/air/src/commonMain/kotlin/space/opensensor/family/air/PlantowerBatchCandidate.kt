package space.opensensor.family.air

/** AirGradient's count-based candidate for the specified Plantower batch; not a local calibration. */
object PlantowerBatchCandidate {
    const val BATCH = "PMS5003T-20260408"
    const val FACTOR = 0.003964

    private val eligibleSerial = Regex("PMS5003T-20260408[0-9]{1,9}")

    /** The estimate, only with the opt-in [enabled], a valid particle sensor ([pmValid]) and an eligible [serial]. */
    fun estimate(serial: String?, enabled: Int?, pmValid: Boolean, particlesGt03Per01l: Long?): Double? {
        if (enabled != 1 || !pmValid ||
            serial == null || !eligibleSerial.matches(serial) || particlesGt03Per01l == null || particlesGt03Per01l < 0
        ) {
            return null
        }
        return FACTOR * particlesGt03Per01l
    }
}
