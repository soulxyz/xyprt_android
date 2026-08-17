package io.github.soulxyz.xyprt.data.remote

import android.content.Context
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PublicKey
import java.util.UUID
import javax.crypto.Cipher

/**
 * Anonymous installation identity used by the co-creator backend.
 *
 * The installation id lives in noBackupFilesDir so Android Auto Backup cannot restore an old id
 * without its Android Keystore key after a reinstall. Older builds stored the id in preferences;
 * that value is migrated once for existing installs.
 */
class DeviceIdentity(private val context: Context) {
    private val prefs = context.getSharedPreferences("xyprt_device_identity", Context.MODE_PRIVATE)
    private val idFile = File(context.noBackupFilesDir, "xyprt_installation_id_v2")
    private val alias = "xyprt_device_identity_rsa_v1"
    private val lock = Any()

    val installationId: String
        get() = synchronized(lock) { readOrCreateInstallationId() }

    val androidIdHash: String by lazy {
        val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        sha256Hex("${context.packageName}:$raw".toByteArray())
    }

    val publicKey: PublicKey get() = requireNotNull(ensureKeyPair().getCertificate(alias)).publicKey
    val publicKeyBase64: String get() = Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    val publicKeyFingerprint: String get() = sha256Hex(publicKey.encoded)

    /**
     * Used only after the server reports an identity mismatch. The app-scoped ANDROID_ID hash and
     * Keystore key still let the backend perform a controlled re-bind instead of trapping a user
     * behind an identity restored from an old backup.
     */
    fun rotateInstallationId(): String = synchronized(lock) {
        newInstallationId().also {
            idFile.parentFile?.mkdirs()
            idFile.writeText(it)
            prefs.edit().remove("installation_id").apply()
        }
    }

    /** Unwraps a server envelope that was encrypted to this installation's Android Keystore key. */
    fun unwrapDeviceKey(wrappedBase64: String): ByteArray {
        val wrapped = Base64.decode(wrappedBase64, Base64.DEFAULT)
        val privateKey = ensureKeyPair().getKey(alias, null)
        return Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding").run {
            init(Cipher.DECRYPT_MODE, privateKey)
            doFinal(wrapped)
        }
    }

    /** Compatibility name retained for the existing enhanced-model delivery path. */
    fun unwrapModelKey(wrappedBase64: String): ByteArray = unwrapDeviceKey(wrappedBase64)

    private fun readOrCreateInstallationId(): String {
        val fromFile = runCatching { idFile.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
            ?.takeIf { it.length in 16..128 }
        if (fromFile != null) return fromFile
        val legacy = prefs.getString("installation_id", null)?.takeIf { it.length in 16..128 }
        val value = legacy ?: newInstallationId()
        idFile.parentFile?.mkdirs()
        runCatching { idFile.writeText(value) }
        prefs.edit().remove("installation_id").apply()
        return value
    }

    private fun newInstallationId() = UUID.randomUUID().toString().replace("-", "")

    private fun ensureKeyPair(): KeyStore {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(alias)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT)
                    .setKeySize(2048)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .setDigests(KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256)
                    .build()
            )
            generator.generateKeyPair()
            ks.load(null)
        }
        return ks
    }

    companion object {
        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
