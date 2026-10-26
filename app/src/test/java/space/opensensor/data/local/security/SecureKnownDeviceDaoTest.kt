package space.opensensor.data.local.security

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import space.opensensor.data.local.database.KnownDevice
import space.opensensor.data.local.database.KnownDeviceDao
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

class SecureKnownDeviceDaoTest {
    @Test
    fun `DAO stores ciphertext and only the credential store returns plaintext`() = runTest {
        val delegate = FakeKnownDeviceDao()
        val cipher = FakeTokenCipher()
        val secure = SecureKnownDeviceDao(delegate, cipher)
        val token = "ab".repeat(32)

        secure.upsert(device(token))

        assertEquals("enc1:$token", delegate.rows.single().lanToken)
        val row = secure.get(ADDRESS)!!
        assertTrue(row.hasLanToken)
        assertEquals("row reads stay ciphertext", "enc1:$token", row.lanToken)
        assertEquals("enc1:$token", secure.observeAll().first().single().lanToken)
        assertEquals(token, secure.lanToken(ADDRESS))
    }

    @Test
    fun `row reads never decrypt`() = runTest {
        val delegate = FakeKnownDeviceDao(device("enc1:tampered", autoSync = true))
        val cipher = FakeTokenCipher().apply { decryptFailure = AssertionError("row reads must not decrypt") }
        val secure = SecureKnownDeviceDao(delegate, cipher)

        secure.observeAll().first()
        secure.get(ADDRESS)
        secure.autoSyncDevices()
        secure.allByLastConnected()
        secure.findByStation("station")

        assertEquals(0, cipher.decrypts)
    }

    @Test
    fun `upserting a read row keeps its ciphertext unchanged`() = runTest {
        val delegate = FakeKnownDeviceDao(device("enc1:" + "cd".repeat(32)))
        val secure = SecureKnownDeviceDao(delegate, FakeTokenCipher())

        secure.upsert(secure.get(ADDRESS)!!.copy(firmware = "v6"))

        assertEquals("enc1:" + "cd".repeat(32), delegate.rows.single().lanToken)
    }

    @Test
    fun `setLanToken clears without attempting encryption`() = runTest {
        val cipher = FakeTokenCipher().apply { encryptFailure = AssertionError("must not encrypt null") }
        val delegate = FakeKnownDeviceDao(device("enc1:old"))
        val secure = SecureKnownDeviceDao(delegate, cipher)

        secure.setLanToken(ADDRESS, null, null)

        assertEquals(null, delegate.rows.single().lanToken)
        assertEquals(null, delegate.rows.single().lanPort)
    }

    @Test
    fun `migration encrypts plaintext before marking complete`() = runTest {
        val first = device("ab".repeat(32))
        val second = device(null, address = SECOND_ADDRESS)
        val cipher = FakeTokenCipher(migrationNeeded = true)
        val delegate = FakeKnownDeviceDao(first, second)
        val secure = SecureKnownDeviceDao(delegate, cipher)

        secure.migratePlaintextTokens()

        assertTrue(delegate.rows.first { it.address == ADDRESS }.lanToken!!.startsWith("enc1:"))
        assertEquals(47390, delegate.rows.first { it.address == ADDRESS }.lanPort)
        assertEquals(listOf("encrypt", "mark"), cipher.events)
        assertTrue(cipher.markedComplete)
    }

    @Test
    fun `migration failure is not marked complete`() = runTest {
        val cipher = FakeTokenCipher(migrationNeeded = true).apply { encryptFailure = IllegalStateException("write failed") }
        val secure = SecureKnownDeviceDao(FakeKnownDeviceDao(device("ab".repeat(32))), cipher)

        assertThrows(IllegalStateException::class.java) { runTest { secure.migratePlaintextTokens() } }

        assertFalse(cipher.markedComplete)
    }

    @Test
    fun `transient keystore failure keeps the credential`() = runTest {
        val original = device("enc1:kept", autoSync = true)
        val delegate = FakeKnownDeviceDao(original)
        val cipher = FakeTokenCipher().apply { decryptFailure = KeyStoreException("keystore busy") }
        val secure = SecureKnownDeviceDao(delegate, cipher)

        val failure = runCatching { secure.lanToken(ADDRESS) }.exceptionOrNull()

        assertTrue(failure is KeyStoreException)
        assertEquals(original, delegate.rows.single())
    }

    @Test
    fun `unrecoverable key failure keeps the credential`() = runTest {
        val original = device("enc1:kept")
        val delegate = FakeKnownDeviceDao(original)
        val cipher = FakeTokenCipher().apply { decryptFailure = UnrecoverableKeyException("locked") }
        val secure = SecureKnownDeviceDao(delegate, cipher)

        assertTrue(runCatching { secure.lanToken(ADDRESS) }.exceptionOrNull() is UnrecoverableKeyException)
        assertEquals(original, delegate.rows.single())
    }

    @Test
    fun `authentication failure clears only token and port`() = runTest {
        val original = device("enc1:tampered", autoSync = true)
        val delegate = FakeKnownDeviceDao(original)
        val cipher = FakeTokenCipher().apply { decryptFailure = AEADBadTagException("bad tag") }
        val secure = SecureKnownDeviceDao(delegate, cipher)

        assertEquals(null, secure.lanToken(ADDRESS))
        assertEquals(original.copy(lanToken = null, lanPort = null), delegate.rows.single())
    }

    @Test
    fun `a credential renewed between the failed decrypt and the clear survives`() = runTest {
        val original = device("enc1:tampered", autoSync = true)
        val delegate = FakeKnownDeviceDao(original)
        val cipher = FakeTokenCipher().apply { decryptFailure = AEADBadTagException("bad tag") }
        val secure = SecureKnownDeviceDao(delegate, cipher)
        val renewed = "enc1:" + "ef".repeat(32)
        // BLE renews the credential just before the clear reaches the database (#107).
        delegate.beforeClear = { delegate.renew(ADDRESS, renewed, 47391) }

        assertEquals(null, secure.lanToken(ADDRESS))

        assertEquals(null, delegate.beforeClear)
        assertTrue("the renewed credential survives", delegate.rows.single().lanToken == renewed)
        assertEquals(47391, delegate.rows.single().lanPort)
    }

    @Test
    fun `malformed encrypted ciphertext is cleared`() = runTest {
        val original = device("enc1:malformed")
        val delegate = FakeKnownDeviceDao(original)
        val cipher = FakeTokenCipher().apply { decryptFailure = IllegalArgumentException("Malformed encrypted LAN token") }
        val secure = SecureKnownDeviceDao(delegate, cipher)

        assertEquals(null, secure.lanToken(ADDRESS))
        assertEquals(original.copy(lanToken = null, lanPort = null), delegate.rows.single())
    }

    @Test
    fun `unrelated malformed plaintext failure propagates`() = runTest {
        val delegate = FakeKnownDeviceDao(device("not-encrypted"))
        val cipher = FakeTokenCipher().apply { decryptFailure = IllegalArgumentException("invalid token programming error") }
        val secure = SecureKnownDeviceDao(delegate, cipher)

        val failure = runCatching { secure.lanToken(ADDRESS) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals("not-encrypted", delegate.rows.single().lanToken)
    }

    @Test
    fun `unrelated database failure propagates`() = runTest {
        val delegate = FakeKnownDeviceDao(device("enc1:old")).apply { clearFailure = IllegalStateException("database closed") }
        val cipher = FakeTokenCipher().apply { decryptFailure = AEADBadTagException("bad tag") }
        val secure = SecureKnownDeviceDao(delegate, cipher)

        val failure = runCatching { secure.lanToken(ADDRESS) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    private fun device(
        token: String?,
        address: String = ADDRESS,
        autoSync: Boolean = false,
    ) = KnownDevice(
        address = address,
        name = "OS-test",
        station = "station",
        deviceId = "device",
        firmware = "firmware",
        lastSeenEpochMs = 1,
        lastConnectedEpochMs = 2,
        lanToken = token,
        lanPort = 47390,
        autoSync = autoSync,
        companionAssociationId = 7,
    )

    private class FakeTokenCipher(
        private val migrationNeeded: Boolean = false,
    ) : LanTokenCipherContract {
        var encryptFailure: Throwable? = null
        var decryptFailure: Throwable? = null
        var decrypts = 0
        var markedComplete = false
        val events = mutableListOf<String>()

        override fun encrypt(tokenHex: String): String {
            events += "encrypt"
            encryptFailure?.let { throw it }
            return if (tokenHex.startsWith("enc1:")) tokenHex else "enc1:$tokenHex"
        }

        override fun decrypt(stored: String): String {
            decrypts++
            decryptFailure?.let { throw it }
            return stored.removePrefix("enc1:")
        }

        override fun migrationRequired(): Boolean = migrationNeeded

        override fun markMigrationComplete() {
            events += "mark"
            markedComplete = true
        }
    }

    private class FakeKnownDeviceDao(vararg initial: KnownDevice) : KnownDeviceDao {
        private val state = MutableStateFlow(initial.toList())
        var clearFailure: RuntimeException? = null

        /** Runs once just before the next clear lands, as a concurrent renewal would. */
        var beforeClear: (() -> Unit)? = null
        val rows: List<KnownDevice> get() = state.value

        override fun observeAll(): Flow<List<KnownDevice>> = state
        override suspend fun get(address: String): KnownDevice? = rows.firstOrNull { it.address == address }
        override suspend fun upsert(device: KnownDevice) { replace(device) }
        override suspend fun insertIfAbsent(device: KnownDevice): Long {
            if (get(device.address) == null) state.value += device
            return 1
        }
        override suspend fun touch(address: String, name: String?, epochMs: Long) = Unit
        override suspend fun setLanToken(address: String, tokenHex: String?, port: Int?) {
            if (tokenHex == null) {
                clearFailure?.let { throw it }
                beforeClear?.also { beforeClear = null }?.invoke()
            }
            renew(address, tokenHex, port)
        }

        override suspend fun clearLanTokenIfStored(address: String, expected: String): Int {
            clearFailure?.let { throw it }
            beforeClear?.also { beforeClear = null }?.invoke()
            // The compare and the clear are one step, as in the single SQL update.
            if (get(address)?.lanToken != expected) return 0
            renew(address, null, null)
            return 1
        }

        override suspend fun recordConnection(address: String, name: String?, station: String?, deviceId: String?, firmware: String?, epochMs: Long) = Unit

        fun renew(address: String, tokenHex: String?, port: Int?) {
            state.value = rows.map { if (it.address == address) it.copy(lanToken = tokenHex, lanPort = port) else it }
        }
        override suspend fun findByStation(station: String): KnownDevice? = rows.firstOrNull { it.station == station }
        override suspend fun setAutoSync(address: String, enabled: Boolean) = Unit
        override suspend fun autoSyncDevices(): List<KnownDevice> = rows.filter { it.autoSync }
        override suspend fun allByLastConnected(): List<KnownDevice> = rows
        override fun observeAutoSyncCount(): Flow<Int> = MutableStateFlow(rows.count { it.autoSync })
        override suspend fun setCompanionAssociation(address: String, associationId: Int?) = Unit
        override suspend fun autoSyncLanCount(): Int = rows.count { it.autoSync && it.lanToken != null }
        override suspend fun delete(address: String) { state.value = rows.filterNot { it.address == address } }

        private fun replace(device: KnownDevice) {
            state.value = rows.filterNot { it.address == device.address } + device
        }
    }

    companion object {
        private const val ADDRESS = "00:11:22:33:44:55"
        private const val SECOND_ADDRESS = "00:11:22:33:44:66"
    }
}
