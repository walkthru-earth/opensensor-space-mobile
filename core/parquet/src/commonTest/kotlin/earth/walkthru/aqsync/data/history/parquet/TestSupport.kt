package earth.walkthru.aqsync.data.history.parquet

import kotlin.math.abs

// Shared by the common Parquet tests, which run on the JVM (`jvmTest`) and on the iOS simulator
// (`iosSimulatorArm64Test`). The asserts keep JUnit's argument order (message first) so the
// oracle comparisons read the same as before the tests became common.

/** Bytes of a fixture under `core/parquet/src/test/resources`, for example `parquet/expected.json`. */
expect fun fixtureBytes(path: String): ByteArray

fun fixtureText(path: String): String = fixtureBytes(path).decodeToString()

/** Codecs the tests read every fixture with: built-ins plus ZSTD where the platform test has it (JVM). */
expect val testCodecs: ParquetCodecs

/** Keeps a written file for the external PyArrow and DuckDB validator (JVM: `build/<dir>/`; iOS: nothing). */
expect fun saveSample(dir: String, name: String, bytes: ByteArray)

/** A [ParquetSink] that collects everything in memory. */
class MemorySink : ParquetSink {
    private val out = ByteOutput()
    override fun write(bytes: ByteArray) = out.write(bytes)
    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Builds little- or big-endian test inputs (WKB, PLAIN pages, Snappy blocks) without `java.nio`. */
class TestBytes(private val littleEndian: Boolean = true) {
    private val out = ByteOutput()

    fun put(b: Int): TestBytes = apply { out.write(b) }
    fun put(bytes: ByteArray): TestBytes = apply { out.write(bytes) }
    fun putInt(v: Int): TestBytes = apply { if (littleEndian) out.writeIntLe(v) else for (i in 3 downTo 0) out.write(v ushr (8 * i)) }
    fun putLong(v: Long): TestBytes = apply { if (littleEndian) out.writeLongLe(v) else for (i in 7 downTo 0) out.write((v ushr (8 * i)).toInt()) }
    fun putDouble(v: Double): TestBytes = putLong(v.toRawBits())
    fun toByteArray(): ByteArray = out.toByteArray()
}

/** A decimal string (Python's arbitrary precision sum) reduced modulo 2^64, as a wrapping Long sum is. */
fun decimalMod64(text: String): Long {
    val negative = text.startsWith("-")
    var acc = 0L
    for (c in text.removePrefix("-")) acc = acc * 10 + (c - '0')
    return if (negative) -acc else acc
}

fun assertEquals(expected: Any?, actual: Any?) = kotlin.test.assertEquals(expected, actual)

fun assertEquals(message: String, expected: Any?, actual: Any?) = kotlin.test.assertEquals(expected, actual, message)

fun assertEquals(message: String, expected: Double, actual: Double, delta: Double) {
    if (expected.isNaN() && actual.isNaN()) return
    if (expected == actual) return
    kotlin.test.assertTrue(abs(expected - actual) <= delta, "$message: expected $expected, actual $actual (delta $delta)")
}

fun assertEquals(expected: Double, actual: Double, delta: Double) = assertEquals("", expected, actual, delta)

fun assertTrue(condition: Boolean) = kotlin.test.assertTrue(condition)

fun assertTrue(message: String?, condition: Boolean) = kotlin.test.assertTrue(condition, message)

fun assertNotNull(actual: Any?) {
    kotlin.test.assertNotNull(actual)
}

fun assertNotNull(message: String, actual: Any?) {
    kotlin.test.assertNotNull(actual, message)
}

fun assertNull(actual: Any?) = kotlin.test.assertNull(actual)

fun assertNull(message: String, actual: Any?) = kotlin.test.assertNull(actual, message)

fun assertArrayEquals(message: String, expected: ByteArray, actual: ByteArray) = kotlin.test.assertContentEquals(expected, actual, message)

fun fail(message: String = "failed"): Nothing = kotlin.test.fail(message)
