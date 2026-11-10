package space.opensensor.data.history

import space.opensensor.archive.ArchiveRecord
import space.opensensor.model.BoardTemplate
import space.opensensor.model.RowCheck

/**
 * Which family [RowCheck] runs on the rows of a template (#91). The daily builder stores its flags in
 * the [FLAGS_COLUMN] of the day file, beside the raw columns, and rolls them into the [FLAGS_ID] and
 * [cleanId] tier companions. A source's check id is part of its day signature ([ArchiveHistoryTiers.sourceLine]),
 * so a rule change rebuilds exactly the checked days and nothing else.
 */
fun interface RowChecks {
    /** The check of [template]'s rows, or null when none applies or the template is unknown. */
    fun of(template: BoardTemplate?): RowCheck?

    /** The check of [file]'s rows under the template [templates] resolves for it before it opens. */
    fun of(file: ArchiveRecord, templates: FileTemplates): RowCheck? = of(templates.template(file.dictionarySha256, file.templateSha256))

    companion object {
        /** No check. The golden comparisons with the builders that predate the checks use it. */
        val NONE: RowChecks = RowChecks { null }

        /** The installed families' checks ([Metrics.providers]). The first provider with one for the template wins. */
        val INSTALLED: RowChecks = RowChecks { t -> t?.let { Metrics.providers.firstNotNullOfOrNull { p -> p.rowCheck(it) } } }

        /** The nullable INT32 day file column of each row's flags. Null for a row no check read, 0 for one that passed. */
        const val FLAGS_COLUMN = "qc_flags"

        /** The day file metadata key that lists the [RowCheck.id]s of its [FLAGS_COLUMN], comma separated. */
        const val CHECKS_KEY = "qc_checks"

        /**
         * The tier id of a bucket's flagged rows. Its valid rows are the flagged rows and its mean, min
         * and max the OR of their flags. A bucket with no flagged row has none.
         */
        const val FLAGS_ID = "~qc"

        /**
         * The tier id of [metricId] over the bucket's rows that passed every check, for a metric with a
         * health scale and a mean rollup. Present only where [FLAGS_ID] is. Its min and max equal its
         * mean, and its mean is 0 when no valid row is left ([TierBucket.validRows] 0).
         */
        fun cleanId(metricId: String): String = "$metricId$CLEAN_SUFFIX"

        private const val CLEAN_SUFFIX = "~clean"
    }
}
