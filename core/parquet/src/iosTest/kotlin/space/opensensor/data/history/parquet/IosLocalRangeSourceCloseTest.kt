package space.opensensor.data.history.parquet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/**
 * [close] racing reads on other threads. The descriptor of a closed source is often reused at once by
 * the next open, so a read that reached it would return the other file's bytes.
 */
class IosLocalRangeSourceCloseTest {
    @Test
    fun `reads racing close return the file bytes or fail and never another file`() = runBlocking<Unit> {
        val a = ByteArray(8192) { 0x41 }
        val b = ByteArray(8192) { 0x42 }
        val fileA = tempLocalFile("close-a", a)
        val fileB = tempLocalFile("close-b", b)
        repeat(50) {
            val source = LocalRangeSource(fileA)
            val reads = AtomicInt(0)
            val readers = List(4) {
                async(Dispatchers.Default) {
                    while (true) {
                        val view = try {
                            source.readRanges(listOf(0L until 8192L)).single()
                        } catch (_: IllegalStateException) {
                            break
                        }
                        assertContentEquals(a, view.toByteArray())
                        reads.incrementAndGet()
                        yield()
                    }
                }
            }
            // Close while reads are in flight, then hold a new descriptor open while they drain.
            while (reads.value < 4) yield()
            source.close()
            LocalRangeSource(fileB).use { other ->
                assertContentEquals(b, other.readTail(8192).toByteArray())
                readers.awaitAll()
            }
        }
    }

    @Test
    fun `a read after close is refused`() = runBlocking<Unit> {
        val source = LocalRangeSource(tempLocalFile("closed", ByteArray(16)))
        source.close()
        assertFailsWith<IllegalStateException> { source.readTail(4) }
    }
}
