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

    /**
     * Durable description of a key-changing operation whose server commit may be unknown.
     * The operation id is a random idempotency/reconciliation capability, not an entitlement.
     */
    data class PendingKeyOperation(
        val purpose: String,
        val operationId: String,
        val version: Int,
        val createdAtSeconds: Long,
    )

    val pendingKeyOperation: PendingKeyOperation?
        get() = synchronized(lock) { readPendingKeyOperation() }

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

    /**
     * Prepare and durably remember a key-changing operation before any network request is sent.
     * If the process dies or the HTTP response is lost, the pending key remains available for
     * reconciliation instead of being deleted on an ambiguous exception.
     */
    fun preparePendingKeyOperation(purpose: String, version: Int): Pair<PendingKeyOperation, KeyMaterial> = synchronized(lock) {
        require(purpose in setOf("rotation", "recovery")) { "unsupported key operation purpose" }
        require(version > keyVersion) { "pending key version must increase" }
        val existing = readPendingKeyOperation()
        if (existing != null) {
            require(existing.purpose == purpose && existing.version == version) {
                "another device key operation is still pending"
            }
            ensureEncryptionKey(version)
            ensureAuthKey(version)
            return@synchronized existing to keyMaterial(version)
        }
        ensureEncryptionKey(version)
        ensureAuthKey(version)
        val pending = PendingKeyOperation(
            purpose = purpose,
            operationId = UUID.randomUUID().toString().replace("-", ""),
            version = version,
            createdAtSeconds = System.currentTimeMillis() / 1000L,
        )
        val written = prefs.edit()
            .putString(PREF_PENDING_PURPOSE, pending.purpose)
            .putString(PREF_PENDING_OPERATION_ID, pending.operationId)
            .putInt(PREF_PENDING_VERSION, pending.version)
            .putLong(PREF_PENDING_CREATED_AT, pending.createdAtSeconds)
            .commit()
        check(written) { "unable to persist pending device key operation" }
        pending to keyMaterial(version)
    }

    fun commitRotation(version: Int) = synchronized(lock) {
        require(version >= keyVersion) { "device key version cannot go backwards" }
        val pending = readPendingKeyOperation()
        val editor = prefs.edit().putInt("device_key_version", version)
        if (pending?.version == version) clearPendingFields(editor)
        check(editor.commit()) { "unable to persist device key version" }
    }

    fun commitPendingKeyOperation(operation: PendingKeyOperation) = synchronized(lock) {
        val current = readPendingKeyOperation() ?: error("pending device key operation missing")
        require(current == operation) { "pending device key operation changed" }
        require(operation.version >= keyVersion) { "device key version cannot go backwards" }
        val editor = prefs.edit().putInt("device_key_version", operation.version)
        clearPendingFields(editor)
        check(editor.commit()) { "unable to commit pending device key operation" }
    }

    /** Only call after the server has explicitly confirmed that the operation was not applied. */
    fun discardPendingKeyOperation(operation: PendingKeyOperation) = synchronized(lock) {
        val current = readPendingKeyOperation() ?: return@synchronized
        require(current == operation) { "pending device key operation changed" }
        if (operation.version > keyVersion) deleteKeyMaterial(operation.version)
        val editor = prefs.edit(); clearPendingFields(editor)
        check(editor.commit()) { "unable to clear pending device key operation" }
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

    private fun readPendingKeyOperation(): PendingKeyOperation? {
        val purpose = prefs.getString(PREF_PENDING_PURPOSE, null)?.takeIf { it in setOf("rotation", "recovery") } ?: return null
        val operationId = prefs.getString(PREF_PENDING_OPERATION_ID, null)?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{20,128}")) } ?: return null
        val version = prefs.getInt(PREF_PENDING_VERSION, 0).takeIf { it > keyVersion } ?: return null
        val createdAt = prefs.getLong(PREF_PENDING_CREATED_AT, 0L).coerceAtLeast(0L)
        val ks = keyStore()
        if (!ks.containsAlias(encryptionAlias(version)) || !ks.containsAlias(authAlias(version))) return null
        return PendingKeyOperation(purpose, operationId, version, createdAt)
    }

    private fun clearPendingFields(editor: android.content.SharedPreferences.Editor) {
        editor.remove(PREF_PENDING_PURPOSE)
            .remove(PREF_PENDING_OPERATION_ID)
            .remove(PREF_PENDING_VERSION)
            .remove(PREF_PENDING_CREATED_AT)
    }

    private fun deleteKeyMaterial(version: Int) {
        val ks = keyStore()
        runCatching { if (ks.containsAlias(encryptionAlias(version))) ks.deleteEntry(encryptionAlias(version)) }
        runCatching { if (ks.containsAlias(authAlias(version))) ks.deleteEntry(authAlias(version)) }
    }

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
        private const val PREF_PENDING_PURPOSE = "pending_key_operation_purpose"
        private const val PREF_PENDING_OPERATION_ID = "pending_key_operation_id"
        private const val PREF_PENDING_VERSION = "pending_key_operation_version"
        private const val PREF_PENDING_CREATED_AT = "pending_key_operation_created_at"

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
