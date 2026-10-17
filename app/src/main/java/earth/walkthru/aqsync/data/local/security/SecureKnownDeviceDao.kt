package earth.walkthru.aqsync.data.local.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import earth.walkthru.aqsync.data.local.database.KnownDevice
import earth.walkthru.aqsync.data.local.database.KnownDeviceDao
import kotlinx.coroutines.flow.Flow
import javax.crypto.AEADBadTagException

/** Startup task that must finish before services are allowed to consume stored LAN credentials. */
fun interface LanTokenMigration {
    suspend fun migratePlaintextTokens()
}

/**
 * The only way to read a LAN bearer token in plaintext. Called by the data layer at the moment a
 * LAN transport is built, never by a ViewModel.
 */
fun interface LanCredentialStore {
    /**
     * Plaintext token hex for [address], or null when none is stored or the stored ciphertext can
     * never be decrypted again (it is then cleared). A transient Keystore failure is thrown and
     * the stored credential is kept for the next attempt.
     */
    suspend fun lanToken(address: String): String?
}

/**
 * Keeps LAN bearer tokens encrypted at rest. Row reads return the stored ciphertext in
 * [KnownDevice.lanToken], so `hasLanToken` works for the UI while plaintext never reaches a
 * ViewModel. Plaintext comes only from [LanCredentialStore.lanToken]. Writes accept plaintext
 * (encrypted here) or ciphertext (stored as is), so a read row can be upserted back unchanged.
 */
class SecureKnownDeviceDao(
    private val delegate: KnownDeviceDao,
    private val cipher: LanTokenCipherContract,
) : KnownDeviceDao,
    LanTokenMigration,
    LanCredentialStore {
    override fun observeAll(): Flow<List<KnownDevice>> = delegate.observeAll()

    override suspend fun get(address: String): KnownDevice? = delegate.get(address)

    override suspend fun upsert(device: KnownDevice) = delegate.upsert(encrypt(device))

    override suspend fun insertIfAbsent(device: KnownDevice): Long = delegate.insertIfAbsent(encrypt(device))

    override suspend fun touch(address: String, name: String?, epochMs: Long) = delegate.touch(address, name, epochMs)

    override suspend fun setLanToken(address: String, tokenHex: String?, port: Int?) =
        delegate.setLanToken(address, tokenHex?.let(cipher::encrypt), port)

    override suspend fun clearLanTokenIfStored(address: String, expected: String): Int = delegate.clearLanTokenIfStored(address, expected)

    override suspend fun recordConnection(address: String, name: String?, station: String?, deviceId: String?, firmware: String?, epochMs: Long) =
        delegate.recordConnection(address, name, station, deviceId, firmware, epochMs)

    override suspend fun findByStation(station: String): KnownDevice? = delegate.findByStation(station)

    override suspend fun setAutoSync(address: String, enabled: Boolean) = delegate.setAutoSync(address, enabled)

    override suspend fun autoSyncDevices(): List<KnownDevice> = delegate.autoSyncDevices()

    override suspend fun allByLastConnected(): List<KnownDevice> = delegate.allByLastConnected()

    override fun observeAutoSyncCount(): Flow<Int> = delegate.observeAutoSyncCount()

    override suspend fun setCompanionAssociation(address: String, associationId: Int?) =
        delegate.setCompanionAssociation(address, associationId)

    override suspend fun autoSyncLanCount(): Int = delegate.autoSyncLanCount()

    override suspend fun delete(address: String) = delegate.delete(address)

    override suspend fun lanToken(address: String): String? {
        val device = delegate.get(address) ?: return null
        val stored = device.lanToken ?: return null
        return try {
            cipher.decrypt(stored)
        } catch (e: Exception) {
            if (!isUnusableCiphertext(e)) throw e
            // Only a ciphertext that can never decrypt again is deleted. One conditional update
            // clears it only while the row still holds that same ciphertext, so a token fetched
            // meanwhile survives.
            delegate.clearLanTokenIfStored(address, stored)
            null
        }
    }

    override suspend fun migratePlaintextTokens() {
        if (!cipher.migrationRequired()) return
        delegate.allByLastConnected().forEach { device ->
            val token = device.lanToken ?: return@forEach
            if (!LanTokenCipher.isEncrypted(token)) {
                delegate.setLanToken(device.address, cipher.encrypt(token), device.lanPort)
            }
        }
        cipher.markMigrationComplete()
    }

    private fun encrypt(device: KnownDevice): KnownDevice =
        device.copy(lanToken = device.lanToken?.let(cipher::encrypt))

    /**
     * True only for failures that prove the ciphertext is dead: an authentication tag mismatch
     * (wrong or regenerated key, tampering), a key Android invalidated for good, or a malformed
     * envelope. A `KeyStoreException`, an `UnrecoverableKeyException` or any other
     * `InvalidKeyException` can be a transient Keystore problem and keeps the credential.
     */
    private fun isUnusableCiphertext(error: Exception): Boolean =
        error is AEADBadTagException ||
            error is KeyPermanentlyInvalidatedException ||
            (error is IllegalArgumentException && error.message == MALFORMED_CIPHERTEXT)

    companion object {
        private const val MALFORMED_CIPHERTEXT = "Malformed encrypted LAN token"
    }
}
