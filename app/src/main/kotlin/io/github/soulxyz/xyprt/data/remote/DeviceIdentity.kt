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
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import javax.crypto.Cipher

/**
 * Device identity has two independent jobs:
 *
 * 1) [installationId] is only an identifier. It is never an authentication secret.
 * 2) RSA is an encryption identity used only for per-device content-key envelopes.
 * 3) EC P-256 is an authentication identity used to prove requests originate from this Keystore.
 *
 * Sponsor/co-creator entitlement deliberately does not live here. The server owns authorization;
 * local state can only cache display information.
 */
class DeviceIdentity(private val context: Context) {
    data class KeyMaterial(
        val version: Int,
        val encryptionPublicKeyBase64: String,
        val encryptionKeyFingerprint: String,
        val authPublicKeyBase64: String,
        val authKeyFingerprint: String,
    )

    private val prefs = context.getSharedPreferences("xyprt_device_identity", Context.MODE_PRIVATE)
    private val idFile = File(context.noBackupFilesDir, "xyprt_installation_id_v2")
    private val lock = Any()
    private val random = SecureRandom()

    val installationId: String
        get() = synchronized(lock) { readOrCreateInstallationId() }

    val androidIdHash: String by lazy {
        val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        sha256Hex("${context.packageName}:$raw".toByteArray())
    }

    val keyVersion: Int
        get() = prefs.getInt("device_key_version", 1).coerceAtLeast(1)

    val publicKey: PublicKey get() = encryptionPublicKey(keyVersion)
    val publicKeyBase64: String get() = encode(publicKey.encoded)
    val publicKeyFingerprint: String get() = sha256Hex(publicKey.encoded)

    val authPublicKey: PublicKey get() = authPublicKey(keyVersion)
    val authPublicKeyBase64: String get() = encode(authPublicKey.encoded)
    val authPublicKeyFingerprint: String get() = sha256Hex(authPublicKey.encoded)

    fun currentKeyMaterial(): KeyMaterial = keyMaterial(keyVersion)

    fun keyMaterial(version: Int): KeyMaterial {
        val enc = encryptionPublicKey(version)
        val auth = authPublicKey(version)
        return KeyMaterial(
            version = version,
            encryptionPublicKeyBase64 = encode(enc.encoded),
            encryptionKeyFingerprint = sha256Hex(enc.encoded),
            authPublicKeyBase64 = encode(auth.encoded),
            authKeyFingerprint = sha256Hex(auth.encoded),
        )
    }

    /**
     * Prepare the next pair without switching the active device identity. The old EC private key
     * remains the signer until the server accepts the rotation.
     */
    fun prepareRotation(version: Int = keyVersion + 1): KeyMaterial {
        require(version > keyVersion) { "rotation version must increase" }
        ensureEncryptionKey(version)
        ensureAuthKey(version)
        return keyMaterial(version)
    }

    fun commitRotation(version: Int) {
        require(version >= keyVersion) { "device key version cannot go backwards" }
        prefs.edit().putInt("device_key_version", version).apply()
    }

    fun discardPreparedRotation(version: Int) {
        if (version <= keyVersion) return
        val ks = keyStore()
        runCatching { if (ks.containsAlias(encryptionAlias(version))) ks.deleteEntry(encryptionAlias(version)) }
        runCatching { if (ks.containsAlias(authAlias(version))) ks.deleteEntry(authAlias(version)) }
    }

    fun newNonce(): String {
        val bytes = ByteArray(18).also(random::nextBytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    fun signRequest(
        method: String,
        target: String,
        timestampSeconds: Long,
        nonce: String,
        body: ByteArray,
        version: Int = keyVersion,
    ): String {
        val canonical = requestCanonical(
            method = method,
            target = target,
            timestampSeconds = timestampSeconds,
            nonce = nonce,
            bodySha256 = sha256Hex(body),
            installationId = installationId,
            keyVersion = version,
        )
        return sign(canonical.toByteArray(Charsets.UTF_8), version)
    }

    fun signChallenge(
        purpose: String,
        challengeId: String,
        challenge: String,
        version: Int,
        encryptionKeyFingerprint: String,
        authKeyFingerprint: String,
    ): String {
        val canonical = challengeCanonical(
            purpose = purpose,
            installationId = installationId,
            challengeId = challengeId,
            challenge = challenge,
            keyVersion = version,
            authKeyFingerprint = authKeyFingerprint,
            encryptionKeyFingerprint = encryptionKeyFingerprint,
        )
        return sign(canonical.toByteArray(Charsets.UTF_8), version)
    }

    /** Unwrap a legacy registration challenge encrypted to the current RSA identity. */
    fun unwrapRegistrationChallenge(wrappedBase64: String, version: Int = keyVersion): String {
        return String(unwrapDeviceKey(wrappedBase64, version), Charsets.UTF_8)
    }

    /** Unwraps a server envelope encrypted to this device's versioned Android Keystore RSA key. */
    fun unwrapDeviceKey(wrappedBase64: String, version: Int = keyVersion): ByteArray {
        val wrapped = Base64.decode(wrappedBase64, Base64.DEFAULT)
        val privateKey = keyStore().getKey(encryptionAlias(version), null)
            ?: error("device encryption key unavailable")
        return Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding").run {
            init(Cipher.DECRYPT_MODE, privateKey)
            doFinal(wrapped)
        }
    }

    /** Compatibility name retained for the enhanced-model delivery path. */
    fun unwrapModelKey(wrappedBase64: String): ByteArray = unwrapDeviceKey(wrappedBase64)

    /**
     * Only rotates the anonymous identifier. This is not a key recovery mechanism and does not
     * grant entitlement. Keystore loss must use the explicit server recovery flow.
     */
    fun rotateInstallationId(): String = synchronized(lock) {
        newInstallationId().also {
            idFile.parentFile?.mkdirs()
            idFile.writeText(it)
            prefs.edit().remove("installation_id").apply()
        }
    }

    private fun sign(bytes: ByteArray, version: Int): String {
        val privateKey = keyStore().getKey(authAlias(version), null) as? PrivateKey ?: error("device auth key unavailable")
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(bytes)
        return encode(signature.sign())
    }

    private fun encryptionPublicKey(version: Int): PublicKey {
        ensureEncryptionKey(version)
        return requireNotNull(keyStore().getCertificate(encryptionAlias(version))).publicKey
    }

    private fun authPublicKey(version: Int): PublicKey {
        ensureAuthKey(version)
        return requireNotNull(keyStore().getCertificate(authAlias(version))).publicKey
    }

    private fun ensureEncryptionKey(version: Int) {
        val alias = encryptionAlias(version)
        val ks = keyStore()
        if (ks.containsAlias(alias)) return
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
        generator.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT)
                .setKeySize(2048)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setDigests(KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256)
                .build()
        )
        generator.generateKeyPair()
    }

    private fun ensureAuthKey(version: Int) {
        val alias = authAlias(version)
        val ks = keyStore()
        if (ks.containsAlias(alias)) return
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        generator.generateKeyPair()
    }

    /** Keep the original r1 alias so upgraded installations retain their existing RSA key. */
    private fun encryptionAlias(version: Int): String = if (version == 1) "xyprt_device_identity_rsa_v1" else "xyprt_device_identity_rsa_v$version"
    private fun authAlias(version: Int): String = if (version == 1) "xyprt_device_auth_ec_v1" else "xyprt_device_auth_ec_v$version"

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

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    companion object {
        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

        fun requestCanonical(
            method: String,
            target: String,
            timestampSeconds: Long,
            nonce: String,
            bodySha256: String,
            installationId: String,
            keyVersion: Int,
        ): String = listOf(
            "XYPRT-DEVICE-AUTH-V1",
            method.uppercase(),
            target,
            timestampSeconds.toString(),
            nonce,
            bodySha256.lowercase(),
            installationId,
            keyVersion.toString(),
        ).joinToString("\n")

        fun challengeCanonical(
            purpose: String,
            installationId: String,
            challengeId: String,
            challenge: String,
            keyVersion: Int,
            authKeyFingerprint: String,
            encryptionKeyFingerprint: String,
        ): String = listOf(
            "XYPRT-DEVICE-CHALLENGE-V1",
            purpose,
            installationId,
            challengeId,
            challenge,
            keyVersion.toString(),
            authKeyFingerprint.lowercase(),
            encryptionKeyFingerprint.lowercase(),
        ).joinToString("\n")
    }
}
