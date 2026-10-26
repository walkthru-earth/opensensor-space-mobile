package earth.walkthru.aqsync.data.history

import space.opensensor.model.BoardTemplate
import space.opensensor.model.BundledCatalog

/**
 * What one file's footer says about how to read it. [template] is the board template of its
 * `dictionary_sha256`, or null when nothing resolves it, and the file then has no metric values.
 * [sampleIntervalMs] is the footer `sample_interval_ms`, else the template default, else 0 for unknown
 * (rules 12 and 23, the footer wins and nothing assumes 10 s).
 */
data class FileTemplate(val template: BoardTemplate?, val sampleIntervalMs: Long) {
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
    /** The template for footer [dict], or null when none resolves it. [dict] is null for a footer without one. */
    fun template(dict: String?): BoardTemplate?

    /** The [FileTemplate] of a file with footer [keyValues]. */
    fun of(keyValues: Map<String, String>): FileTemplate {
        val template = template(keyValues[DICTIONARY_KEY])
        val footer = keyValues[SAMPLE_INTERVAL_KEY]?.toLongOrNull()?.takeIf { it > 0 }
        val fallback = template?.defaultSampleIntervalS?.takeIf { it > 0 && it.isFinite() }?.let { (it * MS_PER_S).toLong() }
        return FileTemplate(template, footer ?: fallback ?: 0L)
    }

    companion object {
        const val DICTIONARY_KEY = "dictionary_sha256"
        const val SAMPLE_INTERVAL_KEY = "sample_interval_ms"
        private const val MS_PER_S = 1000.0

        /** The bundled templates by dict. A file of another dictionary keeps its rows and has no metrics. */
        val BUNDLED: FileTemplates = FileTemplates { dict -> dict?.let { BundledCatalog.template(it) } }
    }
}
