package space.opensensor.data.local.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

interface LanTokenCipherContract {
    fun encrypt(tokenHex: String): String
    fun decrypt(stored: String): String
    fun migrationRequired(): Boolean
    fun markMigrationComplete()
}

/** Encrypts LAN bearer tokens with a non-exportable Android Keystore key. */
@Singleton
class LanTokenCipher @Inject constructor(@ApplicationContext context: Context) : LanTokenCipherContract {
    private val migrationMarker = File(context.noBackupFilesDir, MIGRATION_MARKER)

    override fun encrypt(tokenHex: String): String {
        if (isEncrypted(tokenHex)) return tokenHex
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return listOf(
            PREFIX,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(cipher.doFinal(tokenHex.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP),
        ).joinToString(DELIMITER)
    }

    override fun decrypt(stored: String): String {
        if (!isEncrypted(stored)) return stored
        val parts = stored.split(DELIMITER)
        require(parts.size == 3 && parts[0] == PREFIX) { "Malformed encrypted LAN token" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(TAG_BITS, Base64.decode(parts[1], Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    override fun migrationRequired(): Boolean = !migrationMarker.isFile

    override fun markMigrationComplete() {
        migrationMarker.parentFile?.mkdirs()
        check(migrationMarker.createNewFile() || migrationMarker.isFile) { "Could not persist LAN token migration marker" }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "opensensor_lan_token_v1"
        private const val MIGRATION_MARKER = "lan-token-encryption-v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val PREFIX = "enc1"
        private const val DELIMITER = ":"

        fun isEncrypted(value: String): Boolean = value.startsWith("$PREFIX$DELIMITER")
    }
}
