package space.opensensor.data.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bytes as `ble::fill_advert_payload` in the firmware lays them out (protocol 2.1). */
class AdvertisementTest {
    private fun payload(flags: Int, fin: Long, boot16: Int, version: Int = 1) = byteArrayOf(
        version.toByte(), flags.toByte(),
        (fin and 0xff).toByte(), ((fin shr 8) and 0xff).toByte(), ((fin shr 16) and 0xff).toByte(), ((fin shr 24) and 0xff).toByte(),
        (boot16 and 0xff).toByte(), ((boot16 shr 8) and 0xff).toByte(),
        0, 0,
    )

    @Test
    fun `decodes flags counter and boot suffix little-endian`() {
        val adv = Advertisement.decode(payload(flags = 0x0e, fin = 0x01020304, boot16 = 0x6b40))!!
        assertEquals(1, adv.version)
        assertTrue(adv.newFiles)
        assertTrue(adv.sdMounted)
        assertTrue(adv.lanUp)
        assertFalse(adv.noUtc)
        assertFalse(adv.storageFailed)
        assertFalse(adv.clockRestored)
        assertEquals(0x01020304L, adv.finalized)
        assertEquals("6b40", adv.boot16)
        assertTrue(adv.wantsSync)
    }

    @Test
    fun `fin is unsigned and boot16 keeps leading zeros`() {
        val adv = Advertisement.decode(payload(flags = 0, fin = 0xffffffffL, boot16 = 0x00ab))!!
        assertEquals(0xffffffffL, adv.finalized)
        assertEquals("00ab", adv.boot16)
        assertFalse(adv.wantsSync)
    }

    @Test
    fun `unknown version short payload and absence decode to null`() {
        assertNull(Advertisement.decode(null))
        assertNull(Advertisement.decode(payload(0, 0, 0, version = 2)))
        assertNull(Advertisement.decode(byteArrayOf(1, 0, 0, 0)))
        // The two reserved bytes are optional to the decoder.
        assertEquals(0L, Advertisement.decode(payload(0, 0, 0).copyOf(8))!!.finalized)
    }

    @Test
    fun `change key follows boot and counter and the two actionable flags only`() {
        val a = Advertisement.decode(payload(flags = Advertisement.FLAG_NEW_FILES or Advertisement.FLAG_SD, fin = 5, boot16 = 1))!!
        val sameButLan = Advertisement.decode(payload(flags = Advertisement.FLAG_NEW_FILES or Advertisement.FLAG_SD or Advertisement.FLAG_LAN, fin = 5, boot16 = 1))!!
        val nextFile = Advertisement.decode(payload(flags = Advertisement.FLAG_NEW_FILES, fin = 6, boot16 = 1))!!
        val rebooted = Advertisement.decode(payload(flags = Advertisement.FLAG_NO_UTC, fin = 0, boot16 = 2))!!
        assertEquals(a.changeKey, sameButLan.changeKey)
        assertNotEquals(a.changeKey, nextFile.changeKey)
        assertNotEquals(a.changeKey, rebooted.changeKey)
        assertTrue(rebooted.noUtc && rebooted.wantsSync)
    }

    @Test
    fun `flag filter masks the version byte and selects one bit`() {
        val (data, mask) = Advertisement.flagFilter(Advertisement.FLAG_NEW_FILES)
        assertContentEquals(byteArrayOf(0, 2), data)
        assertContentEquals(byteArrayOf(0, 2), mask)
    }
}
