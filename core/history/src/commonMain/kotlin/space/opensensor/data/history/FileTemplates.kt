package space.opensensor.data.history

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import space.opensensor.archive.ArchiveRecord
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
 * Resolves a file's board template from its footer. The app reads [SERVED], the templates boards
 * served and then the bundled ones. Tests pass [BUNDLED] or their own.
 */
fun interface FileTemplates {
    /**
     * The template whose exact bytes hash to [desc] (footer `template.sha256`), else the one for [dict]
     * (footer `dictionary.sha256`), or null when neither resolves. Either is null when the footer has none.
     */
    fun template(dict: String?, desc: String?): BoardTemplate?

    /** Emits a new value whenever [template] may answer differently, so History derives again. */
    val changes: StateFlow<Any?> get() = UNCHANGING

    /**
     * Which template [record]'s footer digests resolve to now, its full desc or `dict:<dict>` for a
     * bundled one without, else null. Part of every day signature, so a template that starts or stops
     * resolving rebuilds exactly the days of its files.
     */
    fun digestOf(record: ArchiveRecord): String? = template(record.dictionarySha256, record.templateSha256)?.let { it.key.desc ?: "dict:${it.dict}" }

    /** The [FileTemplate] of a raw file with Parquet [keyValues], from its `opensensor` footer. */
    fun of(keyValues: Map<String, String>): FileTemplate = of(OpenSensorFooter.of(keyValues))

    /** The [FileTemplate] of a derived day file with Parquet [keyValues], from its app-private [AppDay] entry. */
    fun ofDay(keyValues: Map<String, String>): FileTemplate = of(AppDay.of(keyValues)?.asFooter())

    /** The [FileTemplate] that [footer] names. */
    fun of(footer: OpenSensorFooter?): FileTemplate {
        val template = template(footer?.dictionarySha256, footer?.templateSha256)
        val fallback = template?.defaultSampleIntervalS?.takeIf { it > 0 && it.isFinite() }?.let { (it * MS_PER_S).toLong() }
        return FileTemplate(template, footer?.sampleIntervalMs ?: fallback ?: 0L, footer)
    }

    companion object {
        private const val MS_PER_S = 1000.0

        private val UNCHANGING: StateFlow<Any?> = MutableStateFlow(Unit)

        /**
         * The bundled templates, by template digest first and then by dict. A desc match whose dict
         * differs from the footer's is not used. A file of another dictionary keeps its rows and has no metrics.
         */
        val BUNDLED: FileTemplates = FileTemplates { dict, desc ->
            val byDesc = desc?.let { BundledCatalog.templateByDesc(it) }?.takeIf { dict == null || it.dict == dict }
            byDesc ?: dict?.let { BundledCatalog.template(it) }
        }

        /** [over] the app's [ServedTemplates.shared]. */
        val SERVED: FileTemplates = over(ServedTemplates.shared)

        /**
         * The templates in [served], as boards served them over `GET_DESCRIPTOR` (#191), then the bundled
         * ones, both by template digest first and then by dict. [served] is read at each call, so a file
         * synced after its board served a template binds its metrics even when no bundled template has
         * its dict. A desc match whose dict differs from the footer's is not used. Its [changes] are
         * [served]'s, so a template a board serves or the cache loads derives History again.
         */
        fun over(served: ServedTemplates): FileTemplates = object : FileTemplates {
            override val changes: StateFlow<Any?> get() = served.all

            override fun template(dict: String?, desc: String?): BoardTemplate? {
                val held = served.all.value
                val byDesc = desc?.let { d -> held.firstOrNull { it.key.desc == d } ?: BundledCatalog.templateByDesc(d) }
                    ?.takeIf { dict == null || it.dict == dict }
                return byDesc ?: dict?.let { d -> held.lastOrNull { it.dict == d } ?: BundledCatalog.template(d) }
            }
        }
    }
}
