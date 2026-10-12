package earth.walkthru.aqsync.publicfeed

import kotlinx.datetime.LocalDate

/** The bytes of a test fixture of this module, by its path under `src/commonTest/fixtures/`. */
expect fun fixtureBytes(path: String): ByteArray

/** The OpenAQ aggregates fixture (tools/discover-fixture/make_openaq_fixture.py). */
object OpenAqFixture {
    const val CELLS = "openaq-aggregates/cells/h3_res=5/data.parquet"
    const val MANIFEST = "openaq-aggregates/manifest.json"
    val asOf = LocalDate(2026, 10, 8)

    // Resolution 5 cells, in h3_index order.
    const val CAIRO = 600076239138455551L
    const val LAGOS = 600537624926486527L
    const val ACCRA = 601045185907916799L
    const val NAIROBI = 601132574533746687L

    /** The UTC midnight after [date], epoch milliseconds. */
    fun endOf(date: LocalDate): Long = (date.toEpochDays() + 1) * 86_400_000L

    fun oct(day: Int) = LocalDate(2026, 10, day)
}
