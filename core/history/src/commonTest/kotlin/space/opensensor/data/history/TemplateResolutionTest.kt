package space.opensensor.data.history

import space.opensensor.model.BoardTemplate
import space.opensensor.model.BundledCatalog
import space.opensensor.model.TemplateKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * History reads a file by its footer digests through [FileTemplates], and LIVE joins a device by its
 * `INFO` digests through [BundledCatalog.thing]. Both run [space.opensensor.model.TemplateIndex.resolve]
 * (#259), so every case below resolves to the same template on both paths.
 */
class TemplateResolutionTest {
    private val bundled = BundledCatalog.templates[0]
    private val other = BundledCatalog.templates.first { it.dict != bundled.dict }

    /** A board's served revision of [bundled]'s dict under a fresh desc. */
    private fun revision(desc: String): BoardTemplate = bundled.copy(key = TemplateKey(bundled.dict, desc))

    private data class Case(val name: String, val dict: String?, val desc: String?, val expected: BoardTemplate?)

    private fun cases(servedOld: BoardTemplate, servedNew: BoardTemplate, shadow: BoardTemplate): List<Case> = listOf(
        Case("the exact desc of a bundled template", bundled.dict, bundled.key.desc, bundled),
        Case("the exact desc of a served template", bundled.dict, servedOld.key.desc, servedOld),
        Case("a served template wins over a bundled one with the same desc", other.dict, other.key.desc, shadow),
        Case("an unknown desc never stands in with its dict", bundled.dict, "ab".repeat(32), null),
        Case("a desc whose template has another dict", bundled.dict, other.key.desc, null),
        Case("no desc takes the newest served template of the dict", bundled.dict, null, servedNew),
        Case("no desc and an unknown dict", "cd".repeat(32), null, null),
    )

    @Test
    fun historyAndLiveResolveEveryCaseAlike() {
        val servedOld = revision("01".repeat(32))
        val servedNew = revision("02".repeat(32))
        // Same key as a bundled template, told apart by its datastreams.
        val shadow = other.copy(datastreams = other.datastreams.take(1))
        val served = ServedTemplates().apply {
            add(servedOld)
            add(servedNew)
            add(shadow)
        }
        val history = FileTemplates.over(served)
        for (case in cases(servedOld, servedNew, shadow)) {
            val fromFile = history.template(case.dict, case.desc)
            val fromDevice = case.dict?.let { BundledCatalog.thing("st-1", it, desc = case.desc?.take(TemplateKey.DESC_PREFIX_HEX), served = served.all.value) }?.template
            assertSame(case.expected, fromFile, "History, ${case.name}")
            assertSame(case.expected, fromDevice, "LIVE, ${case.name}")
        }
    }

    @Test
    fun withoutServedTemplatesBothPathsUseTheBundledOnesAlike() {
        val cases = listOf(
            bundled.dict to bundled.key.desc,
            bundled.dict to null,
            bundled.dict to "ab".repeat(32),
            bundled.dict to other.key.desc,
        )
        for ((dict, desc) in cases) {
            val fromFile = FileTemplates.BUNDLED.template(dict, desc)
            val fromDevice = BundledCatalog.thing("st-1", dict, desc = desc?.take(TemplateKey.DESC_PREFIX_HEX))?.template
            assertEquals(fromFile, fromDevice, "dict $dict desc $desc")
        }
        assertSame(bundled, FileTemplates.BUNDLED.template(bundled.dict, null))
    }
}
