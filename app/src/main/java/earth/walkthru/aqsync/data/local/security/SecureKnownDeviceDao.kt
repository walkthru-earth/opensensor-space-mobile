package earth.walkthru.aqsync.data.local.security

import earth.walkthru.aqsync.data.local.database.KnownDevice
import earth.walkthru.aqsync.data.local.database.KnownDeviceDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

/** Startup task that must finish before services are allowed to consume stored LAN credentials. */
fun interface LanTokenMigration {
    suspend fun migratePlaintextTokens()
}

/** Keeps LAN bearer tokens encrypted at rest while preserving the DAO's plaintext API. */
class SecureKnownDeviceDao(
    private val delegate: KnownDeviceDao,
    private val cipher: LanTokenCipherContract,
) : KnownDeviceDao,
    LanTokenMigration {
    override fun observeAll(): Flow<List<KnownDevice>> = delegate.observeAll().transform { devices ->
        emit(devices.map { decryptOrClear(it) })
    }

    override suspend fun get(address: String): KnownDevice? = delegate.get(address)?.let { decryptOrClear(it) }

    override suspend fun upsert(device: KnownDevice) = delegate.upsert(encrypt(device))

    override suspend fun insertIfAbsent(device: KnownDevice): Long = delegate.insertIfAbsent(encrypt(device))

    override suspend fun touch(address: String, name: String?, epochMs: Long) = delegate.touch(address, name, epochMs)

    override suspend fun setLanToken(address: String, tokenHex: String?, port: Int?) =
        delegate.setLanToken(address, tokenHex?.let(cipher::encrypt), port)

    override suspend fun findByStation(station: String): KnownDevice? = delegate.findByStation(station)?.let { decryptOrClear(it) }

    override suspend fun setAutoSync(address: String, enabled: Boolean) = delegate.setAutoSync(address, enabled)

    override suspend fun autoSyncDevices(): List<KnownDevice> = delegate.autoSyncDevices().map { decryptOrClear(it) }

    override suspend fun allByLastConnected(): List<KnownDevice> = delegate.allByLastConnected().map { decryptOrClear(it) }

    override fun observeAutoSyncCount(): Flow<Int> = delegate.observeAutoSyncCount()

    override suspend fun setCompanionAssociation(address: String, associationId: Int?) =
        delegate.setCompanionAssociation(address, associationId)

    override suspend fun autoSyncLanCount(): Int = delegate.autoSyncLanCount()

    override suspend fun delete(address: String) = delegate.delete(address)

    override suspend fun migratePlaintextTokens() {
        if (!cipher.migrationRequired()) return
        delegate.allByLastConnected().forEach { device ->
            val token = device.lanToken ?: return@forEach
            if (!LanTokenCipher.isEncrypted(token)) {
                delegate.setLanToken(device.address, cipher.encrypt(token), device.lanPort)
            } else {
                decryptOrClear(device)
            }
        }
        cipher.markMigrationComplete()
    }

    private fun encrypt(device: KnownDevice): KnownDevice =
        device.copy(lanToken = device.lanToken?.let(cipher::encrypt))

    private suspend fun decryptOrClear(device: KnownDevice): KnownDevice {
        val decrypted = decryptOrNull(device) ?: return device.copy(lanToken = null, lanPort = null).also {
            delegate.setLanToken(device.address, null, null)
        }
        return device.copy(lanToken = decrypted)
    }

    private fun decryptOrNull(device: KnownDevice): String? {
        val stored = device.lanToken ?: return null
        return try {
            cipher.decrypt(stored)
        } catch (e: Exception) {
            if (!isUnusableCiphertext(e)) throw e
            null
        }
    }

    private fun isUnusableCiphertext(error: Exception): Boolean =
        error is AEADBadTagException ||
            error is InvalidKeyException ||
            error is KeyStoreException ||
            error is UnrecoverableKeyException ||
            (error is IllegalArgumentException && error.message == MALFORMED_CIPHERTEXT)

    companion object {
        private const val MALFORMED_CIPHERTEXT = "Malformed encrypted LAN token"
    }
}
