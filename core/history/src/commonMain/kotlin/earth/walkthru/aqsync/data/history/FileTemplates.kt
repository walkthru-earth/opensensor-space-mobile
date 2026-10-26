package earth.walkthru.aqsync.data.history

import space.opensensor.model.BoardTemplate
import space.opensensor.model.BundledCatalog

/**
 * What one file's `opensensor` footer says about how to read it. [template] is the board template its
 * `template.sha256` or `dictionary.sha256` names, or null when nothing resolves them, and the file then
 * has no metric values. [sampleIntervalMs] is the footer `acquisition.sample_interval_ms`, else the
 * template default, else 0 for unknown (rules 12 and 23, the footer wins and nothing assumes 10 s).
 * [footer] is null for a file without a footer History can trust.
 */
data class FileTemplate(val template: BoardTemplate?, val sampleIntervalMs: Long, val footer: OpenSensorFooter? = null) {
    /** Each of [metrics] as this file's template defines it, null where the file cannot give it values. */
    fun bind(metrics: List<MetricSpec>): List<MetricSpec?> {
        val t = template ?: return metrics.map { null }
        return metrics.map { it.inTemplate(t) }
    }
}

/**
 * Resolves a file's board template from its footer. The default reads the bundled templates. Tests
 * and served templates pass their own.
 */
fun interface FileTemplates {
    /**
     * The template whose exact bytes hash to [desc] (footer `template.sha256`), else the one for [dict]
     * (footer `dictionary.sha256`), or null when neither resolves. Either is null when the footer has none.
     */
    fun template(dict: String?, desc: String?): BoardTemplate?

    /** The [FileTemplate] of a file with Parquet [keyValues]. */
    fun of(keyValues: Map<String, String>): FileTemplate {
        val footer = OpenSensorFooter.of(keyValues)
        val template = template(footer?.dictionarySha256, footer?.templateSha256)
        val fallback = template?.defaultSampleIntervalS?.takeIf { it > 0 && it.isFinite() }?.let { (it * MS_PER_S).toLong() }
        return FileTemplate(template, footer?.sampleIntervalMs ?: fallback ?: 0L, footer)
    }

    companion object {
        private const val MS_PER_S = 1000.0

        /**
         * The bundled templates, by template digest first and then by dict. A desc match whose dict
         * differs from the footer's is not used. A file of another dictionary keeps its rows and has no metrics.
         */
        val BUNDLED: FileTemplates = FileTemplates { dict, desc ->
            val byDesc = desc?.let { BundledCatalog.templateByDesc(it) }?.takeIf { dict == null || it.dict == dict }
            byDesc ?: dict?.let { BundledCatalog.template(it) }
        }
    }
}
