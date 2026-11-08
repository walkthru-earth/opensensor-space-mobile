package space.opensensor.publicfeed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [H3Grid.gridDisk] on the platform binding, `com.uber:h3` on the JVM and the vendored C library in the
 * iOS simulator. Vectors from Python `h3` 4.5.0.
 */
class H3GridDiskTest {
    private val grid = platformH3Grid()

    // Central Accra at res 8.
    private val accra = H3Index.fromHex("887576970bfffff")

    // The first res 8 pentagon, base cell 4.
    private val pentagon = H3Index.fromHex("8808000001fffff")

    private fun hex(cells: List<Long>) = cells.map(H3Index::toHex).sorted()

    @Test
    fun `k 0 is the cell alone`() {
        assertEquals(listOf(accra), grid.gridDisk(accra, 0))
    }

    @Test
    fun `k 1 around a res 8 hexagon is the cell and its 6 neighbours`() {
        val disk = grid.gridDisk(accra, 1)
        assertEquals(
            listOf(
                "8875769701fffff",
                "8875769703fffff",
                "8875769709fffff",
                "887576970bfffff",
                "887576971dfffff",
                "8875769755fffff",
                "8875769757fffff",
            ),
            hex(disk),
        )
    }

    @Test
    fun `k 2 around a res 8 hexagon holds 19 distinct res 8 cells`() {
        val disk = grid.gridDisk(accra, 2)
        assertEquals(19, disk.size)
        assertEquals(19, disk.toSet().size)
        assertTrue(accra in disk)
        assertTrue(grid.gridDisk(accra, 1).all { it in disk })
        assertTrue(disk.all { H3Index.isCell(it) && H3Index.resolution(it) == 8 })
    }

    @Test
    fun `a pentagon has 5 neighbours so its disks are smaller`() {
        val one = grid.gridDisk(pentagon, 1)
        assertEquals(
            listOf("8808000001fffff", "8808000005fffff", "8808000007fffff", "8808000009fffff", "880800000bfffff", "880800000dfffff"),
            hex(one),
        )
        val two = grid.gridDisk(pentagon, 2)
        assertEquals(16, two.size)
        assertTrue(0L !in two)
    }

    @Test
    fun `a negative k is refused`() {
        assertFailsWith<IllegalArgumentException> { grid.gridDisk(accra, -1) }
    }
}
