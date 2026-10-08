package earth.walkthru.aqsync.data.history

import earth.walkthru.aqsync.data.history.parquet.ParquetSink
import kotlin.math.abs

// Shared by the history tests that read real device files, on the JVM (`jvmTest`) and on the iOS
// simulator (`iosSimulatorArm64Test`). The fixtures are the ones :core:parquet publishes, never a copy.
// The asserts keep JUnit's argument order (message first) so the tests read as they did on the JVM.
// Tests that import `kotlin.test` asserts by name keep those, an explicit import wins over this file.

/** Bytes of a fixture under `core/parquet/src/test/resources`, for example `parquet/expected.json`. */
expect fun fixtureBytes(path: String): ByteArray

/** A [ParquetSink] that collects everything in memory. */
class MemorySink : ParquetSink {
    private val chunks = ArrayList<ByteArray>()

    override fun write(bytes: ByteArray) {
        chunks += bytes.copyOf()
    }

    fun toByteArray(): ByteArray {
        val out = ByteArray(chunks.sumOf { it.size })
        var at = 0
        for (c in chunks) { c.copyInto(out, at); at += c.size }
        return out
    }
}

fun assertEquals(expected: Any?, actual: Any?) = kotlin.test.assertEquals(expected, actual)

fun assertEquals(message: String, expected: Any?, actual: Any?) = kotlin.test.assertEquals(expected, actual, message)

fun assertEquals(expected: Double, actual: Double, delta: Double) {
    kotlin.test.assertTrue(abs(expected - actual) <= delta, "expected $expected, actual $actual (delta $delta)")
}

fun assertEquals(expected: Float, actual: Float, delta: Float) {
    kotlin.test.assertTrue(abs(expected - actual) <= delta, "expected $expected, actual $actual (delta $delta)")
}

fun assertTrue(condition: Boolean) = kotlin.test.assertTrue(condition)

fun assertTrue(message: String, condition: Boolean) = kotlin.test.assertTrue(condition, message)

fun assertFalse(condition: Boolean) = kotlin.test.assertFalse(condition)

fun assertNotNull(actual: Any?) {
    kotlin.test.assertNotNull(actual)
}

fun assertNull(actual: Any?) = kotlin.test.assertNull(actual)

fun assertNull(message: String, actual: Any?) = kotlin.test.assertNull(actual, message)
