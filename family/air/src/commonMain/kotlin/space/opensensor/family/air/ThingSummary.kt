package space.opensensor.family.air

/**
 * One block of a mood strip. [step] null means no reading for that block, drawn hatched and
 * spoken "n/a". It is never guessed from its neighbours. [weight] is the block's share of the
 * strip's width, 1 for time blocks, an area count for the Discover spread.
 */
data class MoodBlock(val step: WhoStep?, val weight: Float = 1f)

/**
 * What one thing or one public cell says about one property, ready for a mood surface.
 * [scaled] is false for a property without a WHO scale. Its number still shows and no mood is
 * invented, so [step] is null and [blocks] is empty. [blocks] run oldest first.
 */
data class ThingSummary(
    val propertyId: String,
    val value: Double?,
    val unit: String,
    val step: WhoStep?,
    val scaled: Boolean,
    val updatedEpochMs: Long?,
    val blocks: List<MoodBlock>,
) {
    companion object {
        /** Missing and non finite values stay null, never zero. */
        fun of(propertyId: String, unit: String, value: Double?, updatedEpochMs: Long?, history: List<Double?>): ThingSummary {
            val scale = WhoGuidelines.forProperty(propertyId)
            val shown = value?.takeIf { it.isFinite() }
            return ThingSummary(
                propertyId = propertyId,
                value = shown,
                unit = unit,
                step = if (scale == null || shown == null) null else scale.stepOf(shown),
                scaled = scale != null,
                updatedEpochMs = updatedEpochMs,
                blocks = if (scale == null) {
                    emptyList()
                } else {
                    history.map { v -> MoodBlock(v?.takeIf { it.isFinite() }?.let(scale::stepOf)) }
                },
            )
        }
    }
}
