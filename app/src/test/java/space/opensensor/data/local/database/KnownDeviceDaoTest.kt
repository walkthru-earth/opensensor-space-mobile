package space.opensensor.data.local.database

import android.content.ContextWrapper
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The field-scoped writes of [KnownDeviceDao] on an in-memory Room database (#106, #107). */
class KnownDeviceDaoTest {
    private val db = Room.inMemoryDatabaseBuilder(ContextWrapper(null), AppDatabase::class.java)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
    private val dao = db.knownDeviceDao()

    @After
    fun close() = db.close()

    private fun device(token: String?) = KnownDevice(
        address = ADDRESS,
        name = "OS-test",
        station = "old-station",
        deviceId = "old-device",
        firmware = "old-fw",
        lastSeenEpochMs = 1,
        lastConnectedEpochMs = 1,
        lanToken = token,
        lanPort = 47390,
        autoSync = true,
        companionAssociationId = 7,
    )

    @Test
    fun `conditional clear removes only the expected credential and its port`() = runBlocking {
        dao.upsert(device("enc1:dead"))

        assertEquals(0, dao.clearLanTokenIfStored(ADDRESS, "enc1:other"))
        assertTrue(dao.get(ADDRESS)!!.lanToken == "enc1:dead")

        assertEquals(1, dao.clearLanTokenIfStored(ADDRESS, "enc1:dead"))
        val row = dao.get(ADDRESS)!!
        assertNull(row.lanToken)
        assertNull(row.lanPort)
        assertEquals(device(null).copy(lanPort = null), row)
    }

    @Test
    fun `insertIfAbsent leaves an existing row untouched`() = runBlocking {
        dao.upsert(device("enc1:kept"))

        val result = dao.insertIfAbsent(KnownDevice(ADDRESS, "OS-new", "station", "device", "fw", 50, 50))

        assertEquals(-1L, result)
        assertEquals(device("enc1:kept"), dao.get(ADDRESS))
    }

    @Test
    fun `recording a connection keeps settings and the credential`() = runBlocking {
        dao.upsert(device("enc1:kept"))

        dao.recordConnection(ADDRESS, null, "station", "device", "fw", 50)

        assertEquals(
            device("enc1:kept").copy(station = "station", deviceId = "device", firmware = "fw", lastSeenEpochMs = 50, lastConnectedEpochMs = 50),
            dao.get(ADDRESS),
        )
        dao.recordConnection(ADDRESS, "OS-new", "station", "device", "fw", 60)
        assertEquals("OS-new", dao.get(ADDRESS)!!.name)
    }

    private companion object {
        const val ADDRESS = "00:11:22:33:44:55"
    }
}
