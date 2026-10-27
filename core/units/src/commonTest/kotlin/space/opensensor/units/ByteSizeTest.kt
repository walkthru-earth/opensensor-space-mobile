package space.opensensor.units

import kotlin.test.Test
import kotlin.test.assertEquals

class ByteSizeTest {
    @Test
    fun bytesStepByTenTwentyFour() {
        assertEquals(ByteSize("512", ByteUnit.B), byteSize(512))
        assertEquals(ByteSize("1.5", ByteUnit.KB), byteSize(1536))
        assertEquals(ByteSize("2.5", ByteUnit.MB), byteSize(2_621_440))
        assertEquals(ByteSize("1.50", ByteUnit.GB), byteSize(1_610_612_736))
    }

    @Test
    fun aValueThatRoundsUpToTheNextStepTakesTheBiggerUnit() {
        assertEquals(ByteSize("1023", ByteUnit.B), byteSize(1023))
        assertEquals(ByteSize("1.0", ByteUnit.KB), byteSize(1024))
        assertEquals(ByteSize("1023.9", ByteUnit.KB), byteSize(1_048_473))
        assertEquals(ByteSize("1.0", ByteUnit.MB), byteSize(1_048_575))
        assertEquals(ByteSize("1.0", ByteUnit.MB), byteSize(1_048_576))
        assertEquals(ByteSize("1.00", ByteUnit.GB), byteSize(1_073_741_823))
        assertEquals(ByteSize("2048.00", ByteUnit.GB), byteSize(2L * 1024 * 1024 * 1024 * 1024))
    }

    @Test
    fun ratesUseTheSameSteps() {
        assertEquals(ByteSize("900", ByteUnit.B), byteRate(900.2))
        assertEquals(ByteSize("1.0", ByteUnit.KB), byteRate(1023.6))
        assertEquals(ByteSize("12.5", ByteUnit.KB), byteRate(12_800.0))
        assertEquals(ByteSize("1.5", ByteUnit.MB), byteRate(1_572_864.0))
        assertEquals(ByteSize("2048.0", ByteUnit.MB), byteRate(2.0 * 1024 * 1024 * 1024))
    }
}
