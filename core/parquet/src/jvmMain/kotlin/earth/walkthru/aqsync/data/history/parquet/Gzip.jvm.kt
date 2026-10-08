package earth.walkthru.aqsync.data.history.parquet

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

internal actual fun gunzip(input: ByteArray, expectedSize: Int): ByteArray {
    val out = ByteArray(expectedSize)
    try {
        GZIPInputStream(ByteArrayInputStream(input)).use { zin ->
            var pos = 0
            while (pos < expectedSize) {
                val n = zin.read(out, pos, expectedSize - pos)
                if (n < 0) break
                pos += n
            }
            if (pos != expectedSize) throw ParquetFormatException("gzip: produced $pos of $expectedSize bytes")
            if (zin.read() != -1) throw ParquetFormatException("gzip: page holds more than $expectedSize bytes")
        }
    } catch (e: IOException) {
        throw ParquetFormatException("gzip: ${e.message}", e)
    }
    return out
}
